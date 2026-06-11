package com.knowledge.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatMessage {
    private String id;
    private String role;      // user / assistant
    private String content;
    private Instant timestamp;

    // Web search citations in assistant messages
    private List<Citation> citations;

    // Knowledge domain this answer belongs to
    private String domain;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Citation {
        private String title;
        private String url;
        private String snippet;
    }
}
