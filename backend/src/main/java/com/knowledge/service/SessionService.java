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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);
    private static final Path DEFAULT_storePath = Paths.get("data", "sessions.json");

    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final Path storePath;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> pendingSave;
    private static final long SAVE_DEBOUNCE_MS = 500;

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

    /**
     * Schedule a debounced save. Multiple rapid mutations coalesce into one write.
     */
    private void scheduleSave() {
        synchronized (scheduler) {
            if (pendingSave != null) {
                pendingSave.cancel(false);
            }
            pendingSave = scheduler.schedule(this::saveToFile, SAVE_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void saveToFile() {
        try {
            List<Session> list = new ArrayList<>(sessions.values());
            String json = objectMapper.writeValueAsString(list);
            Files.writeString(storePath, json,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            log.error("Failed to save sessions to file", e);
        }
    }

    /**
     * Force immediate save (bypass debounce). For tests only.
     */
    void flushSave() {
        synchronized (scheduler) {
            if (pendingSave != null) {
                pendingSave.cancel(false);
                pendingSave = null;
            }
        }
        saveToFile();
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
        scheduleSave();
        return session;
    }

    public Session getSession(String id) {
        Session session = sessions.get(id);
        if (session == null) {
            throw new NoSuchElementException("Session not found: " + id);
        }
        return session;
    }

    /**
     * List active (non-archived) sessions, sorted by updatedAt descending.
     */
    public List<Session> listSessions() {
        return sessions.values().stream()
                .filter(s -> !s.isArchived())
                .sorted(Comparator.comparing(Session::getUpdatedAt).reversed())
                .toList();
    }

    /**
     * List session summaries (no messages) for efficient sidebar loading.
     */
    public List<Session> listSessionSummaries() {
        return sessions.values().stream()
                .filter(s -> !s.isArchived())
                .sorted(Comparator.comparing(Session::getUpdatedAt).reversed())
                .map(s -> Session.builder()
                        .id(s.getId())
                        .title(s.getTitle())
                        .createdAt(s.getCreatedAt())
                        .updatedAt(s.getUpdatedAt())
                        .archived(s.isArchived())
                        .messages(List.of()) // empty to reduce payload
                        .build())
                .toList();
    }

    /**
     * List archived sessions, sorted by updatedAt descending.
     */
    public List<Session> listArchivedSessions() {
        return sessions.values().stream()
                .filter(Session::isArchived)
                .sorted(Comparator.comparing(Session::getUpdatedAt).reversed())
                .toList();
    }

    /**
     * Archive a session (hide from main list, keep data).
     */
    public Session archiveSession(String id) {
        Session session = getSession(id);
        session.setArchived(true);
        session.setUpdatedAt(Instant.now());
        scheduleSave();
        return session;
    }

    /**
     * Unarchive a session (restore to main list).
     */
    public Session unarchiveSession(String id) {
        Session session = getSession(id);
        session.setArchived(false);
        session.setUpdatedAt(Instant.now());
        scheduleSave();
        return session;
    }

    /**
     * Auto-archive sessions older than the given number of days.
     * Returns the number of sessions archived.
     */
    public int autoArchiveOldSessions(int days) {
        Instant cutoff = Instant.now().minusSeconds(days * 86400L);
        int count = 0;
        for (Session s : sessions.values()) {
            if (!s.isArchived() && s.getUpdatedAt().isBefore(cutoff)) {
                s.setArchived(true);
                count++;
            }
        }
        if (count > 0) {
            scheduleSave();
            log.info("Auto-archived {} sessions older than {} days", count, days);
        }
        return count;
    }

    public void addMessage(String sessionId, ChatMessage message) {
        Session session = getSession(sessionId);
        session.getMessages().add(message);
        session.setUpdatedAt(Instant.now());
        scheduleSave();
    }

    public List<ChatMessage> getHistory(String sessionId) {
        return getSession(sessionId).getMessages();
    }

    public void deleteSession(String id) {
        sessions.remove(id);
        scheduleSave();
    }

    public boolean deleteMessage(String sessionId, String messageId) {
        Session session = getSession(sessionId);
        boolean removed = session.getMessages().removeIf(m -> messageId.equals(m.getId()));
        if (removed) {
            session.setUpdatedAt(Instant.now());
            scheduleSave();
        }
        return removed;
    }

    public boolean updateMessage(String sessionId, String messageId, String newContent) {
        Session session = getSession(sessionId);
        for (ChatMessage msg : session.getMessages()) {
            if (messageId.equals(msg.getId())) {
                msg.setContent(newContent);
                session.setUpdatedAt(Instant.now());
                scheduleSave();
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
            scheduleSave();
        }
    }
}
