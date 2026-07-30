package com.knowledge.service;

import com.knowledge.model.ChatMessage.Citation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Web search service using Serper.dev (Google Search results).
 *
 * Serper.dev provides Google Search results via a simple JSON API.
 * Free tier: 2,500 queries/month. No credit card required for free plan.
 * Sign up at: https://serper.dev
 *
 * API docs: https://serper.dev/docs
 */
@Service
public class WebSearchService {

    private static final Logger log = LoggerFactory.getLogger(WebSearchService.class);
    private static final String SERPER_API_URL = "https://google.serper.dev/search";

    private final String apiKey;
    private final WebClient webClient;

    public WebSearchService(
            @Value("${websearch.serper.api-key:}") String apiKey) {
        this.apiKey = apiKey;
        this.webClient = WebClient.builder()
                .codecs(config -> config.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                .build();
        if (apiKey != null && !apiKey.isBlank()) {
            log.info("WebSearchService initialized with Serper.dev (free tier: 2500 queries/month)");
        } else {
            log.warn("WebSearchService: Serper.dev API key not configured. Web search will be disabled.");
        }
    }

    /**
     * Search the web and return formatted results.
     */
    public Mono<SearchResult> search(String query) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Serper.dev API key not set, skipping search");
            return Mono.just(SearchResult.empty());
        }
        return searchSerper(query);
    }

    // ---- Serper.dev (Google Search API) ----

    @SuppressWarnings("unchecked")
    private Mono<SearchResult> searchSerper(String query) {
        return webClient.post()
                .uri(SERPER_API_URL)
                .header("X-API-KEY", apiKey)
                .bodyValue(Map.of(
                        "q", query,
                        "gl", "cn",
                        "hl", "zh-cn"
                ))
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(15))
                .map(response -> parseSerperResponse(query, response))
                .onErrorResume(e -> {
                    log.warn("Serper.dev search failed: {}", e.getMessage());
                    return Mono.just(SearchResult.empty());
                });
    }

    @SuppressWarnings("unchecked")
    private SearchResult parseSerperResponse(String query, Map<String, Object> response) {
        List<Citation> citations = new ArrayList<>();

        // Parse organic results
        List<Map<String, Object>> organic = (List<Map<String, Object>>) response.getOrDefault("organic", List.of());
        for (Map<String, Object> r : organic) {
            String title = (String) r.getOrDefault("title", "");
            String link = (String) r.getOrDefault("link", "");
            String snippet = (String) r.getOrDefault("snippet", "");

            if (!title.isBlank() && !link.isBlank()) {
                citations.add(Citation.builder()
                        .title(title)
                        .url(link)
                        .snippet(snippet != null ? snippet : "")
                        .build());
            }

            if (citations.size() >= 8) break;
        }

        // If no organic results, try answerBox
        if (citations.isEmpty()) {
            Map<String, Object> answerBox = (Map<String, Object>) response.get("answerBox");
            if (answerBox != null) {
                String title = (String) answerBox.getOrDefault("title", "");
                String answer = (String) answerBox.getOrDefault("answer", "");
                String snippet = (String) answerBox.getOrDefault("snippet", "");
                if (answer != null && !answer.isBlank()) {
                    citations.add(Citation.builder()
                            .title(title.isBlank() ? "Featured Answer" : title)
                            .url((String) answerBox.getOrDefault("link", ""))
                            .snippet(answer)
                            .build());
                } else if (snippet != null && !snippet.isBlank()) {
                    citations.add(Citation.builder()
                            .title(title.isBlank() ? "Featured Snippet" : title)
                            .url((String) answerBox.getOrDefault("link", ""))
                            .snippet(snippet)
                            .build());
                }
            }
        }

        log.debug("Serper.dev returned {} results for query: {}", citations.size(), query);
        return new SearchResult(query, citations);
    }

    // ---- SearchResult record ----

    public record SearchResult(String query, List<Citation> citations) {
        public static SearchResult empty() {
            return new SearchResult("", List.of());
        }

        public boolean isEmpty() {
            return citations.isEmpty();
        }

        public String formatForPrompt() {
            if (isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            sb.append("## 搜索结果\n\n");
            for (int i = 0; i < citations.size(); i++) {
                Citation c = citations.get(i);
                sb.append("**").append(i + 1).append(".** [")
                        .append(c.getTitle()).append("](").append(c.getUrl()).append(")\n");
                if (c.getSnippet() != null && !c.getSnippet().isBlank()) {
                    sb.append("> ").append(c.getSnippet()).append("\n\n");
                }
            }
            return sb.toString();
        }
    }
}
