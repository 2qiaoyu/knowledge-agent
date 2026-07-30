package com.knowledge.config;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 简化的 OpenAI 兼容 ChatModel 实现。
 * 使用 RestClient 直接调用 OpenAI 兼容 API，避免 OpenAI Java SDK 的 credential 验证问题。
 * 支持真正的 token 级流式响应。
 */
public class SimpleOpenAiChatModel implements ChatModel {

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final double temperature;
    private final RestClient restClient;
    private final WebClient webClient;

    public SimpleOpenAiChatModel(String baseUrl, String apiKey, String model, double temperature) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.temperature = temperature;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(10));
        requestFactory.setReadTimeout(Duration.ofSeconds(120));

        this.restClient = RestClient.builder()
                .baseUrl(this.baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .requestFactory(requestFactory)
                .build();

        this.webClient = WebClient.builder()
                .baseUrl(this.baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .codecs(config -> config.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                .build();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        try {
            String response = callApi(prompt.getContents());
            List<Generation> generations = new ArrayList<>();
            generations.add(new Generation(new AssistantMessage(response)));
            return new ChatResponse(generations, ChatResponseMetadata.builder().build());
        } catch (Exception e) {
            throw new RuntimeException("LongCat API call failed: " + e.getMessage(), e);
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        // 修复：实现真正的 token 级流式响应
        return Flux.defer(() -> {
            Map<String, Object> requestBody = buildStreamingRequestBody(prompt);

            return webClient.post()
                    .uri("/chat/completions")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToFlux(String.class)
                    .timeout(Duration.ofSeconds(120))
                    .filter(line -> line != null && !line.isBlank() && !line.equals("data: [DONE]"))
                    .flatMap(this::parseStreamingChunk)
                    .onErrorResume(e -> {
                        org.slf4j.LoggerFactory.getLogger(SimpleOpenAiChatModel.class)
                                .warn("LongCat streaming failed, falling back to non-streaming: {}", e.getMessage());
                        return Mono.fromCallable(() -> call(prompt));
                    });
        });
    }

    private Map<String, Object> buildStreamingRequestBody(Prompt prompt) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("stream", true);
        requestBody.put("temperature", temperature);

        List<Map<String, String>> messages = new ArrayList<>();
        for (Message message : prompt.getInstructions()) {
            Map<String, String> msg = new HashMap<>();
            msg.put("role", message.getMessageType() == MessageType.USER ? "user" : "assistant");
            msg.put("content", message.getText());
            messages.add(msg);
        }
        requestBody.put("messages", messages);
        return requestBody;
    }

    /**
     * 解析 SSE 流式响应的每个 chunk。
     * LongCat 返回格式：data: {"choices":[{"delta":{"content":"token"}}]}
     */
    private Mono<ChatResponse> parseStreamingChunk(String line) {
        try {
            // 去掉 "data: " 前缀
            String json = line;
            if (json.startsWith("data: ")) {
                json = json.substring(6).trim();
            }
            if (json.isBlank() || json.equals("[DONE]")) {
                return Mono.empty();
            }

            // 手动解析 JSON（避免引入额外依赖）
            String content = extractDeltaContent(json);
            if (content == null || content.isEmpty()) {
                return Mono.empty();
            }

            List<Generation> generations = new ArrayList<>();
            generations.add(new Generation(new AssistantMessage(content)));
            return Mono.just(new ChatResponse(generations, ChatResponseMetadata.builder().build()));
        } catch (Exception e) {
            return Mono.empty();
        }
    }

    /**
     * 从 SSE chunk JSON 中提取 delta content。
     */
    private String extractDeltaContent(String json) {
        // 查找 "delta":{"content":"..."} 模式
        int deltaIdx = json.indexOf("\"delta\"");
        if (deltaIdx < 0) return null;

        int contentIdx = json.indexOf("\"content\"", deltaIdx);
        if (contentIdx < 0) return null;

        // 找到 content 值开始的引号
        int valueStart = json.indexOf("\"", contentIdx + 9);
        if (valueStart < 0) return null;
        valueStart++; // 跳过开始的引号

        // 提取 content 值（处理转义字符）
        StringBuilder sb = new StringBuilder();
        for (int i = valueStart; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                switch (next) {
                    case '"': sb.append('"'); i++; break;
                    case '\\': sb.append('\\'); i++; break;
                    case 'n': sb.append('\n'); i++; break;
                    case 't': sb.append('\t'); i++; break;
                    default: sb.append(c); break;
                }
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private String callApi(String message) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        Map<String, String> userMessage = new HashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", message);
        requestBody.put("messages", new ArrayList<>(List.of(userMessage)));
        requestBody.put("temperature", temperature);
        requestBody.put("stream", false);

        ResponseEntity<Map> response = restClient.post()
                .uri("/chat/completions")
                .body(requestBody)
                .retrieve()
                .toEntity(Map.class);

        Map<String, Object> body = response.getBody();
        if (body == null || body.get("choices") == null) {
            throw new RuntimeException("Invalid response from LongCat API");
        }

        List<Map<String, Object>> choices = (List<Map<String, Object>>) body.get("choices");
        if (choices.isEmpty()) {
            throw new RuntimeException("Empty choices from LongCat API");
        }

        Map<String, Object> messageResponse = (Map<String, Object>) choices.get(0).get("message");
        return (String) messageResponse.get("content");
    }
}
