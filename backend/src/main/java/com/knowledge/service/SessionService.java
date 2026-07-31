package com.knowledge.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledge.model.ChatMessage;
import com.knowledge.model.Session;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);
    private static final Path DEFAULT_storePath = Paths.get("data", "sessions.json");

    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final Path storePath;

    @Autowired
    public SessionService(ObjectMapper objectMapper) {
        this(objectMapper, DEFAULT_storePath);
    }

    SessionService(ObjectMapper objectMapper, Path storePath) {
        this.objectMapper = objectMapper;
        this.storePath = storePath;
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(storePath.getParent());
            if (Files.exists(storePath)) {
                String json = Files.readString(storePath);
                if (!json.isBlank()) {
                    List<Session> list = objectMapper.readValue(json,
                            new TypeReference<List<Session>>() {});
                    for (Session s : list) {
                        sessions.put(s.getId(), s);
                    }
                    log.info("Loaded {} sessions from {}", sessions.size(), storePath);
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
            Files.writeString(storePath, json,
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

    public boolean deleteMessage(String sessionId, String messageId) {
        Session session = getSession(sessionId);
        boolean removed = session.getMessages().removeIf(m -> messageId.equals(m.getId()));
        if (removed) {
            session.setUpdatedAt(Instant.now());
            saveToFile();
        }
        return removed;
    }

    public boolean updateMessage(String sessionId, String messageId, String newContent) {
        Session session = getSession(sessionId);
        for (ChatMessage msg : session.getMessages()) {
            if (messageId.equals(msg.getId())) {
                msg.setContent(newContent);
                session.setUpdatedAt(Instant.now());
                saveToFile();
                return true;
            }
        }
        return false;
    }

    /**
     * 删除指定消息索引之后的所有消息（不包括该消息本身）。
     * 用于编辑用户消息后清除后续联动回复。
     */
    public void deleteMessagesAfter(String sessionId, String messageId) {
        Session session = getSession(sessionId);
        List<ChatMessage> messages = session.getMessages();
        int idx = -1;
        for (int i = 0; i < messages.size(); i++) {
            if (messageId.equals(messages.get(i).getId())) {
                idx = i;
                break;
            }
        }
        if (idx >= 0 && idx < messages.size() - 1) {
            messages.subList(idx + 1, messages.size()).clear();
            session.setUpdatedAt(Instant.now());
            saveToFile();
        }
    }
}
