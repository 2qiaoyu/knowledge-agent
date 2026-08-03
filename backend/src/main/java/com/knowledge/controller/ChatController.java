package com.knowledge.controller;

import com.knowledge.model.ChatMessage;
import com.knowledge.model.ChatRequest;
import com.knowledge.model.Session;
import com.knowledge.service.ChatService;
import com.knowledge.service.SessionService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ChatController {

    private final ChatService chatService;
    private final SessionService sessionService;

    public ChatController(ChatService chatService, SessionService sessionService) {
        this.chatService = chatService;
        this.sessionService = sessionService;
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamChat(@RequestBody ChatRequest request) {
        // Create session if needed
        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            String title = request.getMessage();
            if (title != null && title.length() > 30) {
                title = title.substring(0, 30) + "...";
            }
            sessionId = sessionService.createSession(title).getId();
        }

        final String sid = sessionId;
        // Spring WebFlux already wraps each Flux element as SSE (data: ...\n\n).
        // Do NOT add "data:" here — double encoding breaks the client parser.
        return chatService.streamChat(sid, request)
                .map(chunk -> chunk == null ? "" : chunk)
                .startWith("[SESSION_ID:" + sid + "]")
                .concatWithValues("[DONE]");
    }

    @GetMapping("/sessions")
    public List<Session> listSessions() {
        return sessionService.listSessions();
    }

    @GetMapping("/sessions/{id}")
    public Session getSession(@PathVariable String id) {
        return sessionService.getSession(id);
    }

    @DeleteMapping("/sessions/{id}")
    public Map<String, String> deleteSession(@PathVariable String id) {
        sessionService.deleteSession(id);
        return Map.of("status", "deleted");
    }

    @DeleteMapping("/sessions/{sessionId}/messages/{messageId}")
    public Map<String, String> deleteMessage(
            @PathVariable String sessionId,
            @PathVariable String messageId) {
        boolean removed = sessionService.deleteMessage(sessionId, messageId);
        return Map.of("status", removed ? "deleted" : "not_found");
    }

    @PutMapping("/sessions/{sessionId}/messages/{messageId}")
    public Map<String, String> updateMessage(
            @PathVariable String sessionId,
            @PathVariable String messageId,
            @RequestBody Map<String, String> body) {
        String content = body.getOrDefault("content", "");
        boolean updated = sessionService.updateMessage(sessionId, messageId, content);
        return Map.of("status", updated ? "updated" : "not_found");
    }

    @DeleteMapping("/sessions/{sessionId}/messages/{messageId}/after")
    public Map<String, String> deleteMessagesAfter(
            @PathVariable String sessionId,
            @PathVariable String messageId) {
        sessionService.deleteMessagesAfter(sessionId, messageId);
        return Map.of("status", "deleted");
    }

    /**
     * List archived sessions.
     */
    @GetMapping("/sessions/archived")
    public List<Session> listArchivedSessions() {
        return sessionService.listArchivedSessions();
    }

    /**
     * Archive a session.
     */
    @PostMapping("/sessions/{id}/archive")
    public Map<String, String> archiveSession(@PathVariable String id) {
        sessionService.archiveSession(id);
        return Map.of("status", "archived");
    }

    /**
     * Unarchive a session.
     */
    @PostMapping("/sessions/{id}/unarchive")
    public Map<String, String> unarchiveSession(@PathVariable String id) {
        sessionService.unarchiveSession(id);
        return Map.of("status", "unarchived");
    }
}
