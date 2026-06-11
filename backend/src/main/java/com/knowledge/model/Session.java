package com.knowledge.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Session {
    private String id;
    private String title;
    @Builder.Default
    private List<ChatMessage> messages = new ArrayList<>();
    private Instant createdAt;
    private Instant updatedAt;
}
