package com.knowledge.service;

import com.knowledge.model.ChatMessage;
import com.knowledge.model.ChatMessage.Citation;
import com.knowledge.model.ChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

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

    // ... existing code ...

    /**
     * 处理流式聊天请求，实时返回AI助手的响应内容。
     * <p>
     * 该方法执行以下主要流程：
     * 1. 创建并保存用户消息到会话历史
     * 2. 构建包含知识库检索和网络搜索结果的提示词
     * 3. 调用LLM进行流式对话，启用消息记忆功能
     * 4. 在流式响应过程中累积完整答案
     * 5. 响应完成后异步保存AI回答到知识库
     *
     * @param sessionId 会话唯一标识符，用于维护对话上下文
     * @param request 聊天请求对象，包含用户消息、是否启用网络搜索、领域分类等信息
     * @return Flux<String> 流式返回的AI响应内容片段
     */
    public Flux<String> streamChat(String sessionId, ChatRequest request) {
        // 创建用户消息对象并保存到会话历史中
        ChatMessage userMsg = ChatMessage.builder()
                .id(UUID.randomUUID().toString().substring(0, 8))
                .role("user")
                .content(request.getMessage())
                .timestamp(Instant.now())
                .build();
        sessionService.addMessage(sessionId, userMsg);

        // 用于累积流式响应的完整答案
        StringBuilder answerBuffer = new StringBuilder();

        // 根据 provider 参数选择 ChatClient
        ChatClient client = resolveClient(request.getProvider());
        log.info("Using LLM provider: {}", request.getProvider() != null ? request.getProvider() : "default");

        // 构建提示词并调用LLM获取流式响应
        return buildPrompt(request)
                .flatMapMany(prompt -> {
                    log.debug("Querying LLM with prompt: {}", prompt);
                    // 注意：MessageChatMemoryAdvisor 需要 conversationId，
                    // 但 SimpleOpenAiChatModel 不自动设置 observation context。
                    // 对于 LongCat，我们不使用记忆功能（因为它是无状态的），
                    // 对于 DeepSeek，Spring AI 会自动设置 observation context。
                    return client.prompt()
                            .user(prompt)
                            .stream()
                            .content();
                })
                // 累积每个响应片段到缓冲区
                .doOnNext(answerBuffer::append)
                // 流式响应完成后，异步保存完整的问答对到知识库
                .doOnComplete(() ->
                    Schedulers.boundedElastic().schedule(() ->
                        saveAnswer(sessionId, request.getMessage(), answerBuffer.toString(), request)
                    )
                )
                // 记录流式处理过程中的错误
                .doOnError(e -> log.error("Chat stream error", e));
    }

// ... existing code ...


    private Mono<String> buildPrompt(ChatRequest request) {
        return Mono.fromCallable(() -> {
                    StringBuilder systemPrompt = new StringBuilder();
                    systemPrompt.append("你是一个个人知识库助手。请用中文回答问题，帮助用户构建知识体系。\n");
                    systemPrompt.append("回答应清晰、结构化，使用Markdown格式。\n");

                    // Retrieve relevant past knowledge from Chroma (blocking)
//                    String pastContext = knowledgeService.retrieveContext(request.getMessage(), 3);
//                    if (!pastContext.isEmpty()) {
//                        systemPrompt.append(pastContext);
//                    }
                    systemPrompt.append(request.getMessage());
                    return systemPrompt.toString();
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(systemPrompt -> {
                    if (request.isEnableWebSearch()) {
                        return webSearchService.search(request.getMessage())
                                .map(searchResult -> {
                                    String prompt = systemPrompt;
                                    if (!searchResult.isEmpty()) {
                                        log.debug("Search result: {}", searchResult);
                                        prompt += searchResult.formatForPrompt();
                                    }
                                    return prompt;
                                })
                                .defaultIfEmpty(systemPrompt);
                    }

                    return Mono.just(systemPrompt);
                });
    }

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
