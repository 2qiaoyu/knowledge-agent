package com.knowledge.config;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;

import java.io.IOException;

/**
 * 多 LLM 供应商 ChatClient 配置。
 */
@Configuration
public class ChatClientConfig {

    private static final Logger log = LoggerFactory.getLogger(ChatClientConfig.class);

    @Value("${llm.default-provider:deepseek}")
    private String defaultProvider;

    /**
     * 默认 ChatClient。
     */
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

    /**
     * DeepSeek ChatClient（使用 Spring AI 自动配置的 builder）
     */
    @Bean
    @Qualifier("deepseekChatClient")
    public ChatClient deepseekChatClient(ChatClient.Builder builder) {
        return builder.build();
    }

    /**
     * LongCat ChatClient（手动配置）
     * 只在配置了有效的 API key 时创建。
     */
    @Bean
    @Qualifier("longcatChatClient")
    @ConditionalOnExpression("'${llm.providers.longcat.api-key:}' != ''")
    public ChatClient longcatChatClient(
            @Value("${llm.providers.longcat.api-key}") String apiKey,
            @Value("${llm.providers.longcat.base-url:https://api.longcat.chat/openai}") String baseUrl,
            @Value("${llm.providers.longcat.model:LongCat-Flash-Chat}") String model) {

        log.info("Initializing LongCat client with baseUrl={}, model={}", baseUrl, model);

        // 使用 SpringAiOpenAiHttpClient 构建 httpClient，通过 interceptor 设置 Authorization header
        com.openai.core.http.HttpClient httpClient = org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient.builder()
                .interceptor(new AuthInterceptor(apiKey))
                .build();

        // 构建 ClientOptions，使用 adminApiKey 作为 credential 标记
        // 注意：这里我们使用一个非空的 adminApiKey 来通过验证
        // 实际的认证通过 interceptor 完成
        ClientOptions clientOptions = ClientOptions.builder()
                .adminApiKey("placeholder")  // 仅用于通过验证，实际认证由 interceptor 完成
                .baseUrl(baseUrl)
                .httpClient(httpClient)
                .build();

        OpenAIClient openAiClient = new OpenAIClientImpl(clientOptions);

        var chatOptions = OpenAiChatOptions.builder()
                .model(model)
                .temperature(0.7)
                .build();

        var chatModel = OpenAiChatModel.builder()
                .openAiClient(openAiClient)
                .options(chatOptions)
                .build();

        return ChatClient.builder(chatModel).build();
    }

    /**
     * OkHttp Interceptor，用于设置 Authorization header。
     */
    private static class AuthInterceptor implements Interceptor {
        private final String apiKey;

        AuthInterceptor(String apiKey) {
            this.apiKey = apiKey;
        }

        @Override
        public Response intercept(Chain chain) throws IOException {
            Request request = chain.request().newBuilder()
                    .header("Authorization", "Bearer " + apiKey)
                    .build();
            return chain.proceed(request);
        }
    }
}
