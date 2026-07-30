package com.knowledge.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class WebSearchServiceTest {

    private WebSearchService webSearchService;

    @BeforeEach
    void setUp() {
        webSearchService = new WebSearchService("test-api-key");
    }

    @Test
    void search_shouldReturnEmptyWhenApiKeyIsNull() {
        WebSearchService service = new WebSearchService(null);
        WebSearchService.SearchResult result = service.search("test query").block();

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void search_shouldReturnEmptyWhenApiKeyIsBlank() {
        WebSearchService service = new WebSearchService("  ");
        WebSearchService.SearchResult result = service.search("test query").block();

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void parseSerperResponse_shouldParseOrganicResults() {
        // Given: a mock Serper.dev response with organic results
        Map<String, Object> response = Map.of(
                "organic", List.of(
                        Map.of("title", "Test Title 1", "link", "https://example.com/1", "snippet", "Snippet 1"),
                        Map.of("title", "Test Title 2", "link", "https://example.com/2", "snippet", "Snippet 2")
                )
        );

        // When: parsing the response
        WebSearchService.SearchResult result = invokeParseSerperResponse("test query", response);

        // Then: should contain 2 citations
        assertFalse(result.isEmpty());
        assertEquals(2, result.citations().size());

        assertEquals("Test Title 1", result.citations().get(0).getTitle());
        assertEquals("https://example.com/1", result.citations().get(0).getUrl());
        assertEquals("Snippet 1", result.citations().get(0).getSnippet());
    }

    @Test
    void parseSerperResponse_shouldLimitTo8Results() {
        // Given: more than 8 organic results
        List<Map<String, Object>> organic = new java.util.ArrayList<>();
        for (int i = 0; i < 15; i++) {
            organic.add(Map.of("title", "Title " + i, "link", "https://example.com/" + i, "snippet", "Snippet " + i));
        }
        Map<String, Object> response = Map.of("organic", organic);

        // When
        WebSearchService.SearchResult result = invokeParseSerperResponse("test query", response);

        // Then: should be capped at 8
        assertEquals(8, result.citations().size());
    }

    @Test
    void parseSerperResponse_shouldFallbackToAnswerBox() {
        // Given: no organic results but has answerBox
        Map<String, Object> answerBox = Map.of(
                "title", "Answer Title",
                "answer", "The answer is 42",
                "link", "https://example.com/answer"
        );
        Map<String, Object> response = Map.of("answerBox", answerBox);

        // When
        WebSearchService.SearchResult result = invokeParseSerperResponse("test query", response);

        // Then: should extract from answerBox
        assertFalse(result.isEmpty());
        assertEquals(1, result.citations().size());
        assertEquals("Answer Title", result.citations().get(0).getTitle());
        assertEquals("The answer is 42", result.citations().get(0).getSnippet());
    }

    @Test
    void parseSerperResponse_shouldReturnEmptyForNoResults() {
        // Given: empty response
        Map<String, Object> response = Map.of();

        // When
        WebSearchService.SearchResult result = invokeParseSerperResponse("test query", response);

        // Then
        assertTrue(result.isEmpty());
    }

    @Test
    void parseSerperResponse_shouldSkipResultsWithBlankTitleOrLink() {
        // Given: results with missing fields
        Map<String, Object> response = Map.of(
                "organic", List.of(
                        Map.of("title", "", "link", "https://example.com/1", "snippet", "Snippet 1"),
                        Map.of("title", "Valid Title", "link", "", "snippet", "Snippet 2"),
                        Map.of("title", "Good Title", "link", "https://example.com/3", "snippet", "Snippet 3")
                )
        );

        // When
        WebSearchService.SearchResult result = invokeParseSerperResponse("test query", response);

        // Then: only the valid one remains
        assertEquals(1, result.citations().size());
        assertEquals("Good Title", result.citations().get(0).getTitle());
    }

    @Test
    void formatForPrompt_shouldFormatCorrectly() {
        // Given
        Map<String, Object> response = Map.of(
                "organic", List.of(
                        Map.of("title", "Test Title", "link", "https://example.com", "snippet", "A snippet")
                )
        );
        WebSearchService.SearchResult result = invokeParseSerperResponse("test query", response);

        // When
        String formatted = result.formatForPrompt();

        // Then
        assertTrue(formatted.contains("## 搜索结果"));
        assertTrue(formatted.contains("**1.** [Test Title](https://example.com)"));
        assertTrue(formatted.contains("> A snippet"));
    }

    @Test
    void formatForPrompt_shouldReturnEmptyForEmptyResult() {
        WebSearchService.SearchResult empty = WebSearchService.SearchResult.empty();
        assertEquals("", empty.formatForPrompt());
    }

    @SuppressWarnings("unchecked")
    private WebSearchService.SearchResult invokeParseSerperResponse(String query, Map<String, Object> response) {
        try {
            var method = WebSearchService.class.getDeclaredMethod("parseSerperResponse", String.class, Map.class);
            method.setAccessible(true);
            return (WebSearchService.SearchResult) method.invoke(webSearchService, query, response);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke parseSerperResponse", e);
        }
    }
}
