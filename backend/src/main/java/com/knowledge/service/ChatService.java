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
     *   <li>检索知识库已有知识（向量检索）</li>
     *   <li>可选：联网搜索</li>
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

        // 2. 创建多轮记忆 Advisor（修复：真正启用对话记忆）
        // Spring AI 2.0 中 conversationId 通过 advisor param 传递
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
     * 构建提示词：整合知识库检索 + 可选联网搜索。
     *
     * <p>修复：现在会调用 {@link KnowledgeService#retrieveContext} 获取已有知识，
     * 让 LLM 在回答时可以参考历史问答内容。
     */
    private Mono<Prompt> buildPrompt(ChatRequest request) {
        String systemPrompt = """
                你是一个个人知识库助手，擅长基于已有知识和搜索结果回答问题。
                回答规则：
                1. 优先参考「已有相关知识」章节中的历史问答内容，这些是你之前积累的知识
                2. 如果有搜索结果，搜索结果的知识优先于你自身的参数知识
                3. 如果已有知识和搜索结果都不足以回答问题，请明确说明「未找到足够信息」
                4. 回答应清晰、结构化，使用 Markdown 格式
                5. 在答案正文中引用信息时，使用内联链接格式 [标题](链接)，不要用 [1] 这样的纯编号
                6. 在答案末尾添加「---\\n参考来源：」章节，列出所有引用的来源，格式为：\\n- [来源标题](完整链接)
                7. 用中文回答
                """;

        return Mono.fromCallable(request::getMessage)
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(question -> {
                    // 修复：始终检索已有知识库上下文
                    String knowledgeContext = knowledgeService.retrieveContext(question, KNOWLEDGE_TOP_K);

                    if (request.isEnableWebSearch()) {
                        return webSearchService.search(question)
                                .map(searchResult -> new Prompt(systemPrompt, buildUserPrompt(question, knowledgeContext, searchResult)))
                                .defaultIfEmpty(new Prompt(systemPrompt, buildUserPrompt(question, knowledgeContext, null)));
                    }

                    return Mono.just(new Prompt(systemPrompt, buildUserPrompt(question, knowledgeContext, null)));
                });
    }

    /**
     * 构建用户提示词，整合知识库上下文和搜索结果。
     */
    private String buildUserPrompt(String question, String knowledgeContext, WebSearchService.SearchResult searchResult) {
        StringBuilder sb = new StringBuilder();

        if (searchResult != null && !searchResult.isEmpty()) {
            sb.append(searchResult.formatForPrompt()).append("\n\n");
        }

        if (!knowledgeContext.isEmpty()) {
            sb.append(knowledgeContext).append("\n\n");
        }

        sb.append("## 问题\n\n").append(question);

        if (searchResult != null && !searchResult.isEmpty()) {
            sb.append("\n\n请根据以上搜索结果和已有知识回答问题。");
        } else if (!knowledgeContext.isEmpty()) {
            sb.append("\n\n请参考已有知识回答问题。");
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
