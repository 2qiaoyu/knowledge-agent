package com.knowledge.config;

import org.springframework.context.annotation.Configuration;

/**
 * Spring AI auto-configuration handles:
 * - DeepSeek chat client (via OpenAI starter, base URL overridden in application.yml)
 * - Ollama embedding model (via Ollama starter, for Chroma vector store)
 * <p>
 * No manual beans needed — everything is configured in application.yml.
 */
@Configuration
public class SpringAiConfig {
}
