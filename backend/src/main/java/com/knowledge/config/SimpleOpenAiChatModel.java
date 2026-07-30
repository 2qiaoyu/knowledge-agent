package com.knowledge.config;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 简化的 OpenAI 兼容 ChatModel 实现。
 * 使用 RestClient 直接调用 OpenAI 兼容 API，避免 OpenAI Java SDK 的 credential 验证问题。
 */
public class SimpleOpenAiChatModel implements ChatModel {

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final double temperature;
    private final RestClient restClient;

    public SimpleOpenAiChatModel(String baseUrl, String apiKey, String model, double temperature) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.temperature = temperature;
        this.restClient = RestClient.builder()
                .baseUrl(this.baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
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
        return Mono.fromCallable(() -> call(prompt)).flux();
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
