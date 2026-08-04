package com.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledge.model.GraphData;
import com.knowledge.model.GraphData.Edge;
import com.knowledge.model.GraphData.Node;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 知识图谱服务：从知识库中提取概念和关系，构建交互式图谱。
 */
@Service
public class KnowledgeGraphService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeGraphService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final KnowledgeService knowledgeService;
    private final ChatClient chatClient;

    private GraphData graphCache;

    public KnowledgeGraphService(KnowledgeService knowledgeService, ChatClient defaultChatClient) {
        this.knowledgeService = knowledgeService;
        this.chatClient = defaultChatClient;
    }

    /**
     * 获取知识图谱数据（优先返回缓存）。
     */
    public synchronized GraphData getGraph() {
        if (graphCache == null) {
            buildGraph();
        }
        return graphCache;
    }

    /**
     * 重建知识图谱。
     */
    public synchronized GraphData rebuildGraph() {
        buildGraph();
        return graphCache;
    }

    /**
     * 遍历所有知识域，通过 LLM 提取概念和关系，构建图谱。
     */
    private void buildGraph() {
        List<Node> nodes = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        // 用于概念去重：概念名 -> 节点 id
        Map<String, String> conceptIndex = new HashMap<>();
        int edgeCounter = 0;

        List<String> domains = knowledgeService.listDomains();
        for (String domain : domains) {
            // 添加域节点
            String domainNodeId = "domain-" + sanitizeId(domain);
            var entries = knowledgeService.listEntries(domain);
            nodes.add(new Node(domainNodeId, domain, "domain", entries.size()));

            // 通过 LLM 提取该域的概念和关系
            List<String> concepts = extractConcepts(domain, entries);

            for (String concept : concepts) {
                String conceptId = conceptIndex.computeIfAbsent(concept,
                        k -> "concept-" + sanitizeId(k));
                // 概念节点（如果首次出现）
                if (!conceptId.isEmpty() && nodes.stream().noneMatch(n -> n.id().equals(conceptId))) {
                    nodes.add(new Node(conceptId, concept, "concept", 1));
                }
                // 概念 -> 域的边（属于关系）
                edges.add(new Edge("e" + (++edgeCounter), conceptId, domainNodeId, "属于"));
            }

            // 提取概念间关系
            List<ConceptRelation> relations = extractRelations(domain, concepts);
            for (ConceptRelation rel : relations) {
                String sourceId = conceptIndex.get(rel.from());
                String targetId = conceptIndex.get(rel.to());
                if (sourceId != null && targetId != null && !sourceId.equals(targetId)) {
                    edges.add(new Edge("e" + (++edgeCounter), sourceId, targetId, rel.type()));
                }
            }
        }

        // 去重边（source+target+label 相同）
        List<Edge> dedupedEdges = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Edge e : edges) {
            String key = e.source() + "|" + e.target() + "|" + e.label();
            String reverseKey = e.target() + "|" + e.source() + "|" + e.label();
            if (!seen.contains(key) && !seen.contains(reverseKey)) {
                seen.add(key);
                dedupedEdges.add(e);
            }
        }

        graphCache = new GraphData(nodes, dedupedEdges);
        log.info("知识图谱构建完成: {} 个节点, {} 条边", nodes.size(), dedupedEdges.size());
    }

    /**
     * 通过 LLM 从知识域中提取核心概念。
     */
    private List<String> extractConcepts(String domain, List<KnowledgeService.EntryRef> entries) {
        if (entries.isEmpty()) return List.of();

        StringBuilder entriesText = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            var e = entries.get(i);
            String answerPreview = e.answer().length() > 200
                    ? e.answer().substring(0, 200) : e.answer();
            entriesText.append(i + 1).append(". Q: ").append(e.question()).append("\n");
            entriesText.append("   A: ").append(answerPreview).append("\n\n");
        }

        String prompt = """
                你是知识图谱构建助手。分析以下知识域中的问答，提取关键概念。

                知识域：%s
                问答条目（问题+回答前200字）：
                %s

                规则：
                1. 提取 3-8 个核心技术概念（简短中文，2-6字）
                2. 概念应该是具体的技术、工具、模式或原理，避免过于宽泛
                3. 以 JSON 数组返回，只包含概念名称字符串
                4. 不要任何解释，只返回 JSON

                返回格式示例：["概念A", "概念B", "概念C"]

                概念列表：
                """.formatted(domain, entriesText);

        try {
            String result = callLlmWithRetry(prompt);
            result = stripJsonBlock(result);

            @SuppressWarnings("unchecked")
            List<String> concepts = MAPPER.readValue(result, List.class);
            return concepts != null ? concepts : List.of();
        } catch (Exception e) {
            log.warn("提取概念失败 [{}]: {}", domain, e.getMessage());
            return List.of();
        }
    }

    /**
     * 通过 LLM 提取概念间的关系。
     */
    private List<ConceptRelation> extractRelations(String domain, List<String> concepts) {
        if (concepts.size() < 2) return List.of();

        String conceptList = String.join(", ", concepts);
        String prompt = """
                分析以下概念之间的关系。

                知识域：%s
                概念列表：%s

                规则：
                1. 找出概念间的技术关系：依赖(depends_on)、相关(related)、上下位(is_a)、包含(contains)
                2. 只返回确实存在的关系，不要编造
                3. 最多返回 %d 条关系
                4. 以 JSON 数组返回
                5. 不要任何解释

                返回格式示例：[{"from": "概念A", "to": "概念B", "type": "depends_on"}]

                关系列表：
                """.formatted(domain, conceptList, Math.min(concepts.size() * 2, 15));

        try {
            String result = callLlmWithRetry(prompt);
            result = stripJsonBlock(result);

            @SuppressWarnings("unchecked")
            List<Map<String, String>> raw = MAPPER.readValue(result, List.class);
            List<ConceptRelation> relations = new ArrayList<>();
            if (raw != null) {
                for (Map<String, String> item : raw) {
                    String from = item.get("from");
                    String to = item.get("to");
                    String type = item.getOrDefault("type", "related");
                    if (from != null && to != null) {
                        relations.add(new ConceptRelation(from, to, type));
                    }
                }
            }
            return relations;
        } catch (Exception e) {
            log.warn("提取关系失败 [{}]: {}", domain, e.getMessage());
            return List.of();
        }
    }

    /**
     * 调用 LLM，遇到内容类型解析错误时自动重试。
     * LongCat API 偶尔返回 application/octet-stream 导致 Spring AI 无法解析响应，
     * 重试通常能恢复正常。
     */
    private String callLlmWithRetry(String prompt) {
        int maxRetries = 3;
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                return chatClient.prompt().user(prompt).call().content();
            } catch (Exception e) {
                lastException = e;
                String msg = e.getMessage() != null ? e.getMessage() : "";
                boolean isContentTypeError = msg.contains("application/octet-stream")
                        || msg.contains("Error while extracting response");

                if (isContentTypeError && attempt < maxRetries) {
                    log.warn("LLM 调用返回异常内容类型，第 {}/{} 次重试: {}", attempt, maxRetries, e.getMessage());
                    try {
                        Thread.sleep(500L * attempt); // 递增退避
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("重试被中断", ie);
                    }
                } else {
                    throw e;
                }
            }
        }

        throw new RuntimeException("LLM 调用在 " + maxRetries + " 次尝试后仍然失败", lastException);
    }

    /**
     * 将字符串转为安全的节点 id。
     */
    private String sanitizeId(String input) {
        return input.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fff]", "-").toLowerCase();
    }

    /**
     * 去除 LLM 返回的 JSON 代码块标记。
     */
    private String stripJsonBlock(String text) {
        if (text == null) return "[]";
        text = text.trim();
        if (text.startsWith("```")) {
            text = text.replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "");
        }
        return text.trim();
    }

    /**
     * 概念关系的内部表示。
     */
    private record ConceptRelation(String from, String to, String type) {
    }
}
