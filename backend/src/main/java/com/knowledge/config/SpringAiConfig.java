package com.knowledge.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Spring AI auto-configuration handles:
 * - DeepSeek chat client (via OpenAI starter, base URL overridden in application.yml)
 * - Ollama embedding model (via Ollama starter, for Chroma vector store)
 * <p>
 * No manual beans needed — everything is configured in application.yml.
 * <p>
 * 显式提供 Jackson 2.x ObjectMapper bean (注册 JavaTimeModule)，
 * 兼容 Spring Boot 4.x 默认使用 Jackson 3.x (tools.jackson) 的变化。
 */
@Configuration
public class SpringAiConfig {

    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }
}
