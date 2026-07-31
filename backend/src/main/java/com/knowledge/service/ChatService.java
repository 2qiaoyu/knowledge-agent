package com.knowledge.service;

import com.knowledge.model.ChatMessage;
import com.knowledge.model.ChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import reactor.core.scheduler.Schedulers;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /** 知识库检索返回的最大条数 */
    private static final int KNOWLEDGE_TOP_K = 3;

    private final ChatClient defaultChatClient;
    private final ChatClient deepseekChatClient;
    private final ChatClient longcatChatClient;
    private final SessionService sessionService;
    private final KnowledgeService knowledgeService;
    private final WebSearchService webSearchService;
    private final ChatMemory chatMemory;

    public ChatService(@Qualifier("defaultChatClient") ChatClient defaultChatClient,
                       @Qualifier("deepseekChatClient") ChatClient deepseekChatClient,
                       @Lazy @Qualifier("longcatChatClient") ChatClient longcatChatClient,
                       SessionService sessionService,
                       KnowledgeService knowledgeService,
                       WebSearchService webSearchService) {
        this.defaultChatClient = defaultChatClient;
        this.deepseekChatClient = deepseekChatClient;
        this.longcatChatClient = longcatChatClient;
        this.sessionService = sessionService;
        this.knowledgeService = knowledgeService;
        this.webSearchService = webSearchService;
        this.chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .build();
    }

    /**
     * 根据请求中的 provider 参数选择对应的 ChatClient。
     */
    private ChatClient resolveClient(String provider) {
        if ("longcat".equals(provider) && longcatChatClient != null) {
            return longcatChatClient;
        }
        if ("deepseek".equals(provider) && deepseekChatClient != null) {
            return deepseekChatClient;
        }
        return defaultChatClient;
    }

    /**
     * 处理流式聊天请求，实时返回 AI 助手的响应内容。
     *
     * <p>流程：
     * <ol>
     *   <li>保存用户消息到会话历史</li>
     *   <li>智能路由：根据配置决定检索知识库、联网搜索、或两者并用</li>
     *   <li>构建提示词 + 多轮记忆，调用 LLM 流式生成</li>
     *   <li>异步保存回答到知识库</li>
     * </ol>
     *
     * @param sessionId 会话唯一标识符，用于维护对话上下文
     * @param request    聊天请求对象
     * @return Flux&lt;String&gt; 流式返回的 AI 响应内容片段
     */
    public Flux<String> streamChat(String sessionId, ChatRequest request) {
        // 1. 保存用户消息
        ChatMessage userMsg = ChatMessage.builder()
                .id(UUID.randomUUID().toString().substring(0, 8))
                .role("user")
                .content(request.getMessage())
                .timestamp(Instant.now())
                .build();
        sessionService.addMessage(sessionId, userMsg);

        // 用于累积流式响应的完整答案
        StringBuilder answerBuffer = new StringBuilder();

        // 根据 provider 选择 ChatClient
        ChatClient client = resolveClient(request.getProvider());
        log.info("Using LLM provider: {}", request.getProvider() != null ? request.getProvider() : "default");

        // 2. 创建多轮记忆 Advisor
        MessageChatMemoryAdvisor memoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory)
                .build();

        // 3. 构建提示词并调用 LLM
        return buildPrompt(request)
                .flatMapMany(prompt -> {
                    log.debug("Querying LLM with system prompt: {}\nuser prompt: {}", prompt.system(), prompt.user());
                    return client.prompt()
                            .system(prompt.system())
                            .user(prompt.user())
                            .advisors(spec -> spec
                                    .param(ChatMemory.CONVERSATION_ID, sessionId)
                                    .advisors(memoryAdvisor))
                            .stream()
                            .content();
                })
                .doOnNext(answerBuffer::append)
                .doOnComplete(() ->
                    Schedulers.boundedElastic().schedule(() ->
                        saveAnswer(sessionId, request.getMessage(), answerBuffer.toString(), request)
                    )
                )
                .doOnError(e -> log.error("Chat stream error", e));
    }

    /**
     * 构建提示词：智能路由 — 根据配置和检索结果质量决定使用哪些信息源。
     *
     * <p>路由逻辑：
     * <ul>
     *   <li>联网搜索关闭 → 仅检索知识库，有则用知识库，无则让 LLM 用自身知识回答</li>
     *   <li>联网搜索开启 → 知识库检索 + 联网搜索并行执行，综合两者回答</li>
     * </ul>
     *
     * <p>System prompt 根据可用信息源动态选择，避免规则冲突。
     */
    private Mono<Prompt> buildPrompt(ChatRequest request) {
        String question = request.getMessage();

        // 先检索知识库（本地快速），根据结果和配置决定后续流程
        return Mono.fromCallable(() -> knowledgeService.retrieveContext(question, KNOWLEDGE_TOP_K))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(knowledgeContext -> {
                    boolean hasKnowledge = !knowledgeContext.isEmpty();

                    if (!request.isEnableWebSearch()) {
                        // 未开启联网搜索 → 仅用知识库
                        String systemPrompt = buildSystemPrompt(false, hasKnowledge);
                        String userPrompt = buildUserPrompt(question, knowledgeContext, null, hasKnowledge);
                        return Mono.just(new Prompt(systemPrompt, userPrompt));
                    }

                    // 开启联网搜索 → 知识库已检索，再搜联网
                    return webSearchService.search(question)
                            .map(searchResult -> {
                                boolean hasSearch = !searchResult.isEmpty();
                                String systemPrompt = buildSystemPrompt(true, hasKnowledge);
                                String userPrompt = buildUserPrompt(question, knowledgeContext, searchResult, hasKnowledge);
                                return new Prompt(systemPrompt, userPrompt);
                            })
                            .defaultIfEmpty(new Prompt(
                                    buildSystemPrompt(true, hasKnowledge),
                                    buildUserPrompt(question, knowledgeContext, null, hasKnowledge)
                            ));
                });
    }

    /**
     * 根据可用信息源动态构建 System Prompt，消除固定 prompt 的规则冲突问题。
     *
     * @param webSearchEnabled 是否开启了联网搜索
     * @param hasKnowledge    知识库是否有相关结果
     */
    private String buildSystemPrompt(boolean webSearchEnabled, boolean hasKnowledge) {
        if (webSearchEnabled && hasKnowledge) {
            // 两者都有：明确分工，避免优先级冲突
            return """
                    你是一个个人知识库助手，擅长综合已有知识和搜索结果回答问题。
                    回答规则：
                    1. 「已有相关知识」是你积累的个人知识，优先用于回答与历史学习/记录相关的问题
                    2. 「搜索结果」提供最新的外部信息，用于补充已有知识的不足或获取最新动态
                    3. 当两者信息冲突时，明确说明差异，并以搜索结果为准（更新、更权威）
                    4. 信息不足时请明确说明「未找到足够信息」
                    5. 回答应清晰、结构化，使用 Markdown 格式
                    6. 引用来源时使用 [1]、[2] 编号，对应末尾参考来源列表
                    7. 末尾添加「---\n参考来源：」章节，格式：\n[1] [来源标题](完整链接)
                    8. 用中文回答
                    """;
        } else if (webSearchEnabled) {
            // 仅搜索结果
            return """
                    你是一个知识助手，擅长基于搜索结果回答问题。
                    回答规则：
                    1. 搜索结果优先于你自身的参数知识
                    2. 信息不足时请明确说明「未找到足够信息」
                    3. 回答应清晰、结构化，使用 Markdown 格式
                    4. 引用来源时使用 [1]、[2] 编号，对应末尾参考来源列表
                    5. 末尾添加「---\n参考来源：」章节，格式：\n[1] [来源标题](完整链接)
                    6. 用中文回答
                    """;
        } else if (hasKnowledge) {
            // 仅知识库
            return """
                    你是一个个人知识库助手，擅长基于已有知识回答问题。
                    回答规则：
                    1. 优先参考「已有相关知识」中的历史问答内容
                    2. 如果已有知识不足，可以结合你自身的通用知识回答，但需标注"此部分来自通用知识"
                    3. 信息不足时请明确说明「未找到足够信息」
                    4. 回答应清晰、结构化，使用 Markdown 格式
                    5. 用中文回答
                    """;
        } else {
            // 两者都没有
            return """
                    你是一个知识助手。
                    回答规则：
                    1. 本地知识库中无相关信息，请基于你自身的通用知识回答
                    2. 信息不足时请明确说明「未找到足够信息」
                    3. 回答应清晰、结构化，使用 Markdown 格式
                    4. 用中文回答
                    """;
        }
    }

    /**
     * 构建用户提示词，整合知识库上下文和搜索结果。
     *
     * @param hasKnowledge 知识库是否有相关结果（控制提示语）
     */
    private String buildUserPrompt(String question, String knowledgeContext,
                                   WebSearchService.SearchResult searchResult,
                                   boolean hasKnowledge) {
        StringBuilder sb = new StringBuilder();
        boolean hasSearch = searchResult != null && !searchResult.isEmpty();

        if (hasSearch) {
            sb.append(searchResult.formatForPrompt()).append("\n\n");
        }

        if (hasKnowledge) {
            sb.append(knowledgeContext).append("\n\n");
        }

        sb.append("## 问题\n\n").append(question).append("\n\n");

        if (hasSearch && hasKnowledge) {
            sb.append("请综合以上搜索结果和已有知识回答问题。当两者冲突时，说明差异并以搜索结果为准。");
        } else if (hasSearch) {
            sb.append("请根据以上搜索结果回答问题。");
        } else if (hasKnowledge) {
            sb.append("请参考已有知识回答问题。");
        } else {
            sb.append("本地知识库中无相关信息，请基于通用知识回答。");
        }

        return sb.toString();
    }

    private record Prompt(String system, String user) {}

    private void saveAnswer(String sessionId, String question, String answer, ChatRequest request) {
        String domain = request.getDomain();
        if (domain == null || domain.isBlank()) {
            try {
                domain = knowledgeService.classifyDomainWithLlm(question, answer);
            } catch (Exception e) {
                log.warn("LLM classification failed, falling back to vector similarity", e);
                domain = knowledgeService.classifyDomain(question);
            }
        }

        ChatMessage assistantMsg = ChatMessage.builder()
                .id(UUID.randomUUID().toString().substring(0, 8))
                .role("assistant")
                .content(answer)
                .domain(domain)
                .timestamp(Instant.now())
                .build();
        sessionService.addMessage(sessionId, assistantMsg);

        knowledgeService.appendEntry(domain, question, answer, null);
        log.info("Answer saved to domain: {}", domain);
    }
}
