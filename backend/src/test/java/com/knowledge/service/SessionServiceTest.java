package com.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.knowledge.model.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

class SessionServiceTest {

    private SessionService sessionService;
    private Path storePath;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        storePath = tempDir.resolve("data").resolve("sessions.json");
        sessionService = new SessionService(objectMapper, storePath);
        sessionService.init();
    }

    @Test
    void createSession_shouldCreateWithGeneratedId() {
        var session = sessionService.createSession("Test Session");

        assertNotNull(session.getId());
        assertFalse(session.getId().isBlank());
        assertEquals("Test Session", session.getTitle());
        assertNotNull(session.getMessages());
        assertTrue(session.getMessages().isEmpty());
        assertNotNull(session.getCreatedAt());
        assertNotNull(session.getUpdatedAt());
    }

    @Test
    void createSession_shouldHandleNullTitle() {
        var session = sessionService.createSession(null);

        assertNotNull(session.getId());
        assertEquals("New Chat", session.getTitle());
    }

    @Test
    void getSession_shouldReturnExistingSession() {
        var created = sessionService.createSession("Test");

        var found = sessionService.getSession(created.getId());

        assertEquals(created.getId(), found.getId());
        assertEquals("Test", found.getTitle());
    }

    @Test
    void getSession_shouldThrowForNonExistent() {
        assertThrows(NoSuchElementException.class, () -> sessionService.getSession("nonexistent"));
    }

    @Test
    void listSessions_shouldReturnAllSortedByUpdatedAt() throws InterruptedException {
        var s1 = sessionService.createSession("Session 1");
        Thread.sleep(10);
        var s2 = sessionService.createSession("Session 2");

        var sessions = sessionService.listSessions();

        assertEquals(2, sessions.size());
        assertEquals(s2.getId(), sessions.get(0).getId());
        assertEquals(s1.getId(), sessions.get(1).getId());
    }

    @Test
    void addMessage_shouldAddToSession() {
        var session = sessionService.createSession("Test");
        var msg = ChatMessage.builder()
                .id("msg1")
                .role("user")
                .content("Hello")
                .build();

        sessionService.addMessage(session.getId(), msg);

        var updated = sessionService.getSession(session.getId());
        assertEquals(1, updated.getMessages().size());
        assertEquals("Hello", updated.getMessages().get(0).getContent());
    }

    @Test
    void deleteSession_shouldRemoveFromList() {
        var session = sessionService.createSession("To Delete");
        String id = session.getId();

        sessionService.deleteSession(id);

        assertFalse(sessionService.listSessions().stream().anyMatch(s -> s.getId().equals(id)));
        assertThrows(NoSuchElementException.class, () -> sessionService.getSession(id));
    }

    @Test
    void persistence_shouldReloadFromFile() {
        var session = sessionService.createSession("Persist Test");
        String id = session.getId();

        // Force immediate save (bypass debounce for test)
        sessionService.flushSave();

        // Create new service instance pointing to same file (simulates restart)
        ObjectMapper newMapper = new ObjectMapper();
        newMapper.registerModule(new JavaTimeModule());
        SessionService newService = new SessionService(newMapper, storePath);
        newService.init();

        var loaded = newService.getSession(id);
        assertEquals("Persist Test", loaded.getTitle());
    }
}
