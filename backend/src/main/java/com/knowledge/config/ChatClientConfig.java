package com.knowledge.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;

/**
 * 多 LLM 供应商 ChatClient 配置。
 *
 * DeepSeek 使用 Spring AI 自动配置的 ChatClient.Builder。
 * LongCat 使用自定义的 SimpleOpenAiChatModel（基于 RestClient）。
 */
@Configuration
public class ChatClientConfig {

    @Value("${llm.default-provider:deepseek}")
    private String defaultProvider;

    @Bean
    @Primary
    public ChatClient defaultChatClient(
            ChatClient.Builder autoConfiguredBuilder,
            @Lazy @Qualifier("longcatChatClient") ChatClient longcatClient) {

        if ("longcat".equals(defaultProvider)) {
            return longcatClient;
        }
        return autoConfiguredBuilder.build();
    }

    @Bean
    @Qualifier("deepseekChatClient")
    public ChatClient deepseekChatClient(ChatClient.Builder builder) {
        return builder.build();
    }

    /**
     * LongCat ChatClient
     * 使用自定义的 SimpleOpenAiChatModel 实现，避免 OpenAI Java SDK 的 credential 验证问题。
     */
    @Bean
    @Qualifier("longcatChatClient")
    @ConditionalOnExpression("'${llm.providers.longcat.api-key:}' != ''")
    public ChatClient longcatChatClient(
            @Value("${llm.providers.longcat.api-key}") String apiKey,
            @Value("${llm.providers.longcat.base-url:https://api.longcat.chat/openai}") String baseUrl,
            @Value("${llm.providers.longcat.model:LongCat-Flash-Chat}") String model) {

        // 使用自定义的 SimpleOpenAiChatModel
        var chatModel = new com.knowledge.config.SimpleOpenAiChatModel(
                baseUrl, apiKey, model, 0.7);

        return ChatClient.builder(chatModel).build();
    }
}
