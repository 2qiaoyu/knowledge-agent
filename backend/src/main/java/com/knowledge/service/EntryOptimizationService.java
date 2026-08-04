package com.knowledge.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 知识条目优化服务：使用 LLM 优化单个知识条目。
 */
@Service
public class EntryOptimizationService {

    private static final Logger log = LoggerFactory.getLogger(EntryOptimizationService.class);

    private final ChatClient defaultChatClient;
    private final ChatClient deepseekChatClient;
    private final ChatClient longcatChatClient;
    private final WebSearchService webSearchService;

    public EntryOptimizationService(
            ChatClient defaultChatClient,
            @org.springframework.beans.factory.annotation.Qualifier("deepseekChatClient") ChatClient deepseekChatClient,
            @org.springframework.beans.factory.annotation.Qualifier("longcatChatClient") ChatClient longcatChatClient,
            WebSearchService webSearchService) {
        this.defaultChatClient = defaultChatClient;
        this.deepseekChatClient = deepseekChatClient;
        this.longcatChatClient = longcatChatClient;
        this.webSearchService = webSearchService;
    }

    /**
     * 流式优化知识条目。
     */
    public Flux<String> optimizeEntry(String question, String answer,
                                       String provider, boolean enableWebSearch) {
        ChatClient client = resolveClient(provider);

        // 构建基础 prompt — 参考 ChatService 的结构化格式
        String systemPrompt = """
                你是一个知识库优化助手。请优化以下知识条目的回答。

                优化规则：
                1. 保持回答的准确性和专业性，修正可能的错误或过时信息
                2. 优化结构和可读性，使用清晰的 Markdown 格式
                3. 补充重要但缺失的信息（可参考搜索结果）
                4. 保持简洁，避免冗余
                5. **必须保留原始回答中的所有参考来源链接**
                6. 使用 [1]、[2] 编号引用来源，对应末尾参考来源列表
                7. 末尾添加「---\\n参考来源：」章节，格式：\\n[1] [来源标题](完整链接)

                格式要求：
                - 标题使用 ## 层级
                - 列表项之间有空行
                - 段落之间用空行分隔
                - 参考来源单独一节

                只返回优化后的回答内容，不要包含任何解释或额外文字。
                """;

        String userPrompt = buildUserPrompt(question, answer, null);

        if (enableWebSearch) {
            // 先执行联网搜索，然后调用 LLM
            return webSearchService.search(question)
                    .flatMapMany(searchResult -> {
                        String promptWithSearch = buildUserPrompt(question, answer, searchResult);
                        return streamOptimize(client, systemPrompt, promptWithSearch);
                    })
                    .onErrorResume(e -> {
                        log.warn("联网搜索失败，回退到无搜索模式: {}", e.getMessage());
                        return streamOptimize(client, systemPrompt, userPrompt);
                    });
        } else {
            return streamOptimize(client, systemPrompt, userPrompt);
        }
    }

    private Flux<String> streamOptimize(ChatClient client, String systemPrompt, String userPrompt) {
        return client.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .stream()
                .content();
    }

    private String buildUserPrompt(String question, String answer, WebSearchService.SearchResult searchResult) {
        StringBuilder sb = new StringBuilder();
        boolean hasSearch = searchResult != null && !searchResult.isEmpty();

        // 参考搜索结果（如有）
        if (hasSearch) {
            sb.append(searchResult.formatForPrompt()).append("\n\n");
        }

        // 原始知识条目
        sb.append("## 原始条目\n\n");
        sb.append("**问题**：").append(question).append("\n\n");
        sb.append("**回答**：\n").append(answer).append("\n\n");

        // 优化指令
        if (hasSearch) {
            sb.append("请优化以上回答，综合原始内容和搜索结果，补充最新信息并修正可能的错误。保留原始参考来源链接。");
        } else {
            sb.append("请优化以上回答，保持准确性和专业性，优化结构和可读性。保留原始参考来源链接。");
        }

        return sb.toString();
    }

    private ChatClient resolveClient(String provider) {
        if ("longcat".equals(provider) && longcatChatClient != null) return longcatChatClient;
        if ("deepseek".equals(provider) && deepseekChatClient != null) return deepseekChatClient;
        return defaultChatClient;
    }
}
