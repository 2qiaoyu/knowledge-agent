package com.knowledge.service;

import com.knowledge.model.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.chat.client.ChatClient;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KnowledgeServiceTest {

    private KnowledgeService knowledgeService;
    private VectorStore vectorStore;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        vectorStore = mock(VectorStore.class);

        // Create a minimal ChatClient mock that won't be called in most tests
        ChatClient chatClient = mock(ChatClient.class);

        knowledgeService = new KnowledgeService(
                tempDir.resolve("knowledge").toString(),
                vectorStore,
                chatClient
        );
    }

    @Test
    void listDomains_shouldReturnEmptyForNewDirectory() {
        var domains = knowledgeService.listDomains();
        assertTrue(domains.isEmpty());
    }

    @Test
    void listDomains_shouldListExistingDomains() {
        knowledgeService.appendEntry("测试域", "Q1", "A1", null);
        knowledgeService.appendEntry("另一个域", "Q2", "A2", null);

        var domains = knowledgeService.listDomains();

        assertEquals(2, domains.size());
        assertTrue(domains.contains("测试域"));
        assertTrue(domains.contains("另一个域"));
    }

    @Test
    void getKnowledgeContent_shouldReturnFileContent() {
        knowledgeService.appendEntry("测试域", "问题", "回答", null);

        String content = knowledgeService.getKnowledgeContent("测试域");

        assertTrue(content.contains("# 知识域: 测试域"));
        assertTrue(content.contains("## Q: 问题"));
        assertTrue(content.contains("回答"));
    }

    @Test
    void getKnowledgeContent_shouldReturnEmptyForNonExistent() {
        String content = knowledgeService.getKnowledgeContent("不存在的域");
        assertEquals("", content);
    }

    @Test
    void appendEntry_shouldCreateNewFile() {
        knowledgeService.appendEntry("新域", "问题1", "回答1", null);

        String content = knowledgeService.getKnowledgeContent("新域");
        assertTrue(content.contains("# 知识域: 新域"));
    }

    @Test
    void appendEntry_shouldAppendToFile() {
        knowledgeService.appendEntry("追加域", "Q1", "A1", null);
        knowledgeService.appendEntry("追加域", "Q2", "A2", null);

        String content = knowledgeService.getKnowledgeContent("追加域");
        assertTrue(content.contains("## Q: Q1"));
        assertTrue(content.contains("## Q: Q2"));
    }

    @Test
    void appendEntry_shouldIndexInVectorStore() {
        knowledgeService.appendEntry("向量域", "问题", "回答", null);

        verify(vectorStore, times(1)).add(any(List.class));
    }

    @Test
    void appendEntry_shouldIncludeCitations() {
        List<ChatMessage.Citation> citations = List.of(
                ChatMessage.Citation.builder().title("Source").url("https://example.com").build()
        );

        knowledgeService.appendEntry("引用域", "问题", "回答", citations);

        String content = knowledgeService.getKnowledgeContent("引用域");
        assertTrue(content.contains("[Source](https://example.com)"));
    }

    @Test
    void deleteDomain_shouldRemoveFile() {
        knowledgeService.appendEntry("待删除", "Q", "A", null);
        assertTrue(knowledgeService.listDomains().contains("待删除"));

        knowledgeService.deleteDomain("待删除");

        assertFalse(knowledgeService.listDomains().contains("待删除"));
    }

    @Test
    void retrieveContext_shouldReturnFormattedResults() {
        Document doc = new Document("Q: 之前的问题\nA: 之前的回答",
                java.util.Map.of("domain", "测试", "question", "之前的问题"));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc));

        String context = knowledgeService.retrieveContext("相关问题", 3);

        assertFalse(context.isEmpty());
        assertTrue(context.contains("## 已有相关知识"));
        assertTrue(context.contains("之前的问题"));
        assertTrue(context.contains("之前的回答"));
    }

    @Test
    void retrieveContext_shouldReturnEmptyWhenNoResults() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        String context = knowledgeService.retrieveContext("无关问题", 3);

        assertEquals("", context);
    }

    @Test
    void classifyDomain_shouldFallbackToGenericWhenNoDomains() {
        String domain = knowledgeService.classifyDomain("任意问题");
        assertEquals("通用知识", domain);
    }

    @Test
    void classifyDomain_shouldReturnDomainFromVectorMatch() {
        // 先添加域，使 listDomains() 非空
        knowledgeService.appendEntry("已有域", "Q", "A", null);

        Document doc = new Document("内容", java.util.Map.of("domain", "匹配域"));
        when(vectorStore.similaritySearch(any(String.class))).thenReturn(List.of(doc));

        String domain = knowledgeService.classifyDomain("查询");

        assertEquals("匹配域", domain);
    }

    @Test
    void classifyDomain_shouldFallbackWhenNoDomainInMetadata() {
        // 先添加域，使 listDomains() 非空
        knowledgeService.appendEntry("已有域", "Q", "A", null);

        Document doc = new Document("内容", java.util.Map.of());
        when(vectorStore.similaritySearch(any(String.class))).thenReturn(List.of(doc));

        String domain = knowledgeService.classifyDomain("查询");

        assertEquals("通用知识", domain);
    }
}
