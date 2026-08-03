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
import java.util.Map;

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

    @Test
    void exportAllDomains_shouldReturnAllDomainContents() {
        knowledgeService.appendEntry("域A", "Q1", "A1", null);
        knowledgeService.appendEntry("域B", "Q2", "A2", null);

        Map<String, String> exported = knowledgeService.exportAllDomains();

        assertEquals(2, exported.size());
        assertTrue(exported.containsKey("域A"));
        assertTrue(exported.containsKey("域B"));
        assertTrue(exported.get("域A").contains("## Q: Q1"));
        assertTrue(exported.get("域B").contains("## Q: Q2"));
    }

    @Test
    void exportAllDomains_shouldReturnEmptyForNoDomains() {
        Map<String, String> exported = knowledgeService.exportAllDomains();
        assertTrue(exported.isEmpty());
    }

    @Test
    void importEntries_shouldParseAndAppendEntries() {
        String markdown = """
                # 知识域: 导入测试域

                ## Q: 导入问题1

                **日期**: 2025-01-01 10:00

                这是第一个导入的回答

                ---

                ## Q: 导入问题2

                **日期**: 2025-01-02 12:00 | **来源**: [来源链接](https://example.com)

                这是第二个导入的回答

                ---
                """;

        int count = knowledgeService.importEntries("导入测试域", markdown);

        assertEquals(2, count);
        String content = knowledgeService.getKnowledgeContent("导入测试域");
        assertTrue(content.contains("## Q: 导入问题1"));
        assertTrue(content.contains("这是第一个导入的回答"));
        assertTrue(content.contains("## Q: 导入问题2"));
        assertTrue(content.contains("这是第二个导入的回答"));
        // Verify vector store was called for each entry
        verify(vectorStore, times(2)).add(any(List.class));
    }

    @Test
    void importEntries_shouldReturnZeroForEmptyContent() {
        assertEquals(0, knowledgeService.importEntries("空域", ""));
        assertEquals(0, knowledgeService.importEntries("空域", null));
        assertEquals(0, knowledgeService.importEntries("空域", "   \n  "));
    }

    @Test
    void importEntries_shouldImportSimpleFormatWithoutDateLine() {
        String markdown = """
                ## Q: 简单格式问题1

                这是第一个简单格式的回答

                ---

                ## Q: 简单格式问题2

                这是第二个简单格式的回答
                ---
                """;

        int count = knowledgeService.importEntries("简单格式域", markdown);

        assertEquals(2, count);
        String content = knowledgeService.getKnowledgeContent("简单格式域");
        assertTrue(content.contains("## Q: 简单格式问题1"));
        assertTrue(content.contains("这是第一个简单格式的回答"));
        assertTrue(content.contains("## Q: 简单格式问题2"));
        assertTrue(content.contains("这是第二个简单格式的回答"));
    }

    @Test
    void importEntries_shouldHandleMixedFormats() {
        String markdown = """
                ## Q: 有日期的问题

                **日期**: 2025-01-01 10:00

                有日期的回答

                ---

                ## Q: 无日期的问题

                无日期的回答

                ---
                """;

        int count = knowledgeService.importEntries("混合格式域", markdown);

        assertEquals(2, count);
        String content = knowledgeService.getKnowledgeContent("混合格式域");
        assertTrue(content.contains("## Q: 有日期的问题"));
        assertTrue(content.contains("## Q: 无日期的问题"));
    }

    @Test
    void importQAPairs_shouldInsertPairsAsEntries() {
        // Test the second step of smart import: inserting LLM-extracted Q&A pairs
        List<KnowledgeService.QAPair> pairs = List.of(
                new KnowledgeService.QAPair("什么是 Docker？", "Docker 是一个应用打包工具。"),
                new KnowledgeService.QAPair("Docker 的优点", "快速安装、大量镜像。")
        );

        int count = knowledgeService.importQAPairs("智能导入域", pairs);

        assertEquals(2, count);
        String content = knowledgeService.getKnowledgeContent("智能导入域");
        assertTrue(content.contains("## Q: 什么是 Docker？"));
        assertTrue(content.contains("Docker 是一个应用打包工具。"));
        assertTrue(content.contains("## Q: Docker 的优点"));
        assertTrue(content.contains("快速安装、大量镜像。"));
        verify(vectorStore, times(2)).add(any(List.class));
    }

    @Test
    void importEntries_shouldParseHeadingAsQuestionFormat() {
        // 模拟用户粘贴的 Markdown 笔记：## 标题作为 Q，正文作为 A
        String markdown = """
                # Docker

                Docker 是一个应用打包、分发、部署的工具。

                ## 原理

                Docker 使用容器技术实现资源隔离...

                ## 下载

                https://docs.docker.com/desktop/release-notes/

                ## 打包、分发、部署

                打包：把软件运行所需的依赖打包到一起
                分发：上传到镜像仓库
                部署：一个命令运行应用

                ## Docker 安装的优点

                - 一个命令就可以安装好
                - 有大量的镜像可直接使用
                - 没有系统兼容问题
                """;

        int count = knowledgeService.importEntries("Docker", markdown);

        assertEquals(4, count);
        String content = knowledgeService.getKnowledgeContent("Docker");
        assertTrue(content.contains("## Q: 原理"));
        assertTrue(content.contains("Docker 使用容器技术"));
        assertTrue(content.contains("## Q: 下载"));
        assertTrue(content.contains("docs.docker.com"));
        assertTrue(content.contains("## Q: 打包、分发、部署"));
        assertTrue(content.contains("## Q: Docker 安装的优点"));
        assertTrue(content.contains("一个命令就可以安装好"));
    }
}
