package com.knowledge.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledge.model.ChatMessage;
import com.knowledge.model.Session;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);
    private static final Path STORE_PATH = Paths.get("data", "sessions.json");

    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public SessionService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(STORE_PATH.getParent());
            if (Files.exists(STORE_PATH)) {
                String json = Files.readString(STORE_PATH);
                if (!json.isBlank()) {
                    List<Session> list = objectMapper.readValue(json,
                            new TypeReference<List<Session>>() {});
                    for (Session s : list) {
                        sessions.put(s.getId(), s);
                    }
                    log.info("Loaded {} sessions from {}", sessions.size(), STORE_PATH);
                }
            }
        } catch (IOException e) {
            log.error("Failed to load sessions from file", e);
        }
    }

    private synchronized void saveToFile() {
        try {
            List<Session> list = new ArrayList<>(sessions.values());
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(list);
            Files.writeString(STORE_PATH, json,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            log.error("Failed to save sessions to file", e);
        }
    }

    public Session createSession(String title) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        Session session = Session.builder()
                .id(id)
                .title(title != null ? title : "New Chat")
                .messages(new ArrayList<>())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        sessions.put(id, session);
        saveToFile();
        return session;
    }

    public Session getSession(String id) {
        Session session = sessions.get(id);
        if (session == null) {
            throw new NoSuchElementException("Session not found: " + id);
        }
        return session;
    }

    public List<Session> listSessions() {
        return sessions.values().stream()
                .sorted(Comparator.comparing(Session::getUpdatedAt).reversed())
                .toList();
    }

    public void addMessage(String sessionId, ChatMessage message) {
        Session session = getSession(sessionId);
        session.getMessages().add(message);
        session.setUpdatedAt(Instant.now());
        saveToFile();
    }

    public List<ChatMessage> getHistory(String sessionId) {
        return getSession(sessionId).getMessages();
    }

    public void deleteSession(String id) {
        sessions.remove(id);
        saveToFile();
    }
}
