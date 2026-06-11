package com.knowledge.service;

import com.knowledge.model.ChatMessage.Citation;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Web search service supporting multiple providers.
 *
 * Providers:
 * - searxng: Local SearXNG instance (recommended) - JSON API, no CAPTCHA
 * - duckduckgo: DuckDuckGo HTML scraping (may need proxy + may trigger CAPTCHA)
 *
 * Proxy: Configure websearch.proxy.* when behind a firewall/VPN (e.g. Clash, Surge).
 */
@Service
public class WebSearchService {

    private static final Logger log = LoggerFactory.getLogger(WebSearchService.class);

    private final String provider;
    private final String ddgUrl;
    private final String searxngUrl;
    private final boolean proxyEnabled;
    private final String proxyHost;
    private final int proxyPort;

    private final WebClient webClient;

    public WebSearchService(
            @Value("${websearch.provider:searxng}") String provider,
            @Value("${websearch.searxng.url:http://localhost:8081}") String searxngUrl,
            @Value("${websearch.duckduckgo.url:https://html.duckduckgo.com/html/}") String ddgUrl,
            @Value("${websearch.proxy.enabled:false}") boolean proxyEnabled,
            @Value("${websearch.proxy.host:127.0.0.1}") String proxyHost,
            @Value("${websearch.proxy.port:7897}") int proxyPort) {
        this.provider = provider;
        this.searxngUrl = searxngUrl;
        this.ddgUrl = ddgUrl;
        this.proxyEnabled = proxyEnabled;
        this.proxyHost = proxyHost;
        this.proxyPort = proxyPort;
        this.webClient = WebClient.builder()
                .codecs(config -> config.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                .build();
        log.info("WebSearchService initialized: provider={}, proxy={}:{} (enabled={})",
                provider, proxyHost, proxyPort, proxyEnabled);
    }

    /**
     * Search the web and return formatted results.
     */
    public Mono<SearchResult> search(String query) {
        return switch (provider) {
            case "searxng" -> searchSearXNG(query);
            case "duckduckgo" -> searchDuckDuckGo(query);
            default -> {
                log.warn("Unknown search provider: {}, falling back to searxng", provider);
                yield searchSearXNG(query);
            }
        };
    }

    // ---- SearXNG (JSON API) ----

    private Mono<SearchResult> searchSearXNG(String query) {
        return webClient.get()
                .uri(searxngUrl + "/search?format=json&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8))
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(10))
                .map(response -> parseSearXNGResponse(query, response))
                .onErrorResume(e -> {
                    log.warn("SearXNG search failed: {} (is searxng container running? try: docker-compose up -d searxng)", e.getMessage());
                    log.info("Falling back to DuckDuckGo...");
                    return searchDuckDuckGo(query);
                });
    }

    @SuppressWarnings("unchecked")
    private SearchResult parseSearXNGResponse(String query, Map<String, Object> response) {
        List<Citation> citations = new ArrayList<>();
        List<Map<String, Object>> results = (List<Map<String, Object>>) response.getOrDefault("results", List.of());

        for (Map<String, Object> r : results) {
            String title = (String) r.getOrDefault("title", "");
            String url = (String) r.getOrDefault("url", "");
            String snippet = (String) r.getOrDefault("content", "");
            if (snippet == null || snippet.isBlank()) {
                snippet = (String) r.getOrDefault("snippet", "");
            }

            if (!title.isBlank() && !url.isBlank()) {
                citations.add(Citation.builder()
                        .title(title)
                        .url(url)
                        .snippet(snippet != null ? snippet : "")
                        .build());
            }

            if (citations.size() >= 5) break;
        }

        log.debug("SearXNG returned {} results for query", citations.size());
        return new SearchResult(query, citations);
    }

    // ---- DuckDuckGo (HTML scraping) ----

    private Mono<SearchResult> searchDuckDuckGo(String query) {
        return Mono.fromCallable(() -> doDuckDuckGoSearch(query))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.warn("DuckDuckGo search failed: {}", e.getMessage());
                    return Mono.just(SearchResult.empty());
                });
    }

    private SearchResult doDuckDuckGoSearch(String query) throws Exception {
        String url = ddgUrl + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);

        var connection = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .timeout(10000);

        // Configure proxy if enabled
        if (proxyEnabled) {
            connection.proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort)));
            log.debug("Using proxy {}:{} for DuckDuckGo", proxyHost, proxyPort);
        }

        Document doc = connection.get();

        List<Citation> citations = new ArrayList<>();
        for (Element result : doc.select(".result")) {
            Element link = result.selectFirst(".result__a");
            Element snippet = result.selectFirst(".result__snippet");
            if (link == null) continue;

            String title = link.text();
            String href = link.attr("href");
            // DuckDuckGo wraps URLs in a redirect; extract the real URL
            if (href != null && href.contains("uddg=")) {
                href = java.net.URLDecoder.decode(
                        href.replaceAll(".*uddg=", "").replaceAll("&.*", ""),
                        StandardCharsets.UTF_8
                );
            }

            citations.add(Citation.builder()
                    .title(title)
                    .url(href != null ? href : "")
                    .snippet(snippet != null ? snippet.text() : "")
                    .build());

            if (citations.size() >= 5) break;
        }

        log.debug("DuckDuckGo search returned {} results for: {}", citations.size(), query);
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
            sb.append("\n\n## 搜索结果\n\n");
            for (int i = 0; i < citations.size(); i++) {
                Citation c = citations.get(i);
                sb.append("**").append(i + 1).append(".** [")
                        .append(c.getTitle()).append("](").append(c.getUrl()).append(")\n");
                if (c.getSnippet() != null && !c.getSnippet().isBlank()) {
                    sb.append("> ").append(c.getSnippet()).append("\n\n");
                }
            }
            sb.append("\n请根据以上搜索结果回答问题，并在答案末尾标注引用来源。\n");
            return sb.toString();
        }
    }
}
