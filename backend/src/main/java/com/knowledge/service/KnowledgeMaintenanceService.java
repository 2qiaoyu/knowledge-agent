package com.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledge.model.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 知识域整理服务：提供域拆分、合并、去重的建议与执行。
 */
@Service
public class KnowledgeMaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeMaintenanceService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final KnowledgeService knowledgeService;
    private final ChatClient chatClient;

    public KnowledgeMaintenanceService(KnowledgeService knowledgeService, ChatClient defaultChatClient) {
        this.knowledgeService = knowledgeService;
        this.chatClient = defaultChatClient;
    }

    // ---- 数据模型 ----

    /**
     * 拆分建议：将一个域拆分为多个新域的方案。
     */
    public record SplitSuggestion(String domain, List<SplitGroup> groups) {}

    /**
     * 拆分分组：建议的一个新域及其包含的条目。
     */
    public record SplitGroup(String suggestedName, List<Integer> entryIndices, String reason) {}

    /**
     * 拆分执行计划：用户确认后的最终方案。
     */
    public record SplitPlan(List<SplitGroup> groups) {}

    /**
     * 拆分执行结果。
     */
    public record SplitResult(List<String> createdDomains, int totalEntriesMoved) {}

    /**
     * 重复条目建议。
     */
    public record DuplicateGroup(List<Integer> entryIndices, String reason) {}

    /**
     * 去重建议。
     */
    public record MergeSuggestion(List<DuplicateGroup> duplicates) {}

    // ---- API ----

    /**
     * 分析域拆分方案（只建议，不执行）。
     */
    public SplitSuggestion suggestSplit(String domain) {
        List<KnowledgeService.EntryRef> entries = knowledgeService.listEntries(domain);
        if (entries.size() < 2) {
            return new SplitSuggestion(domain, List.of(
                    new SplitGroup(domain, List.of(0), "域内条目过少，无需拆分")
            ));
        }

        // 构建条目列表（问题 + 回答前 100 字）
        StringBuilder entriesText = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            var e = entries.get(i);
            String answerPreview = e.answer().length() > 100
                    ? e.answer().substring(0, 100) + "..." : e.answer();
            entriesText.append(i).append(". Q: ").append(e.question()).append("\n");
            entriesText.append("   A: ").append(answerPreview).append("\n\n");
        }

        // 获取现有域名（排除当前域）
        List<String> existingDomains = knowledgeService.listDomains().stream()
                .filter(d -> !d.equals(domain))
                .toList();
        String existingNames = existingDomains.isEmpty() ? "（暂无）" : String.join(", ", existingDomains);

        String prompt = """
                分析以下知识域中的问答条目，将它们按主题分组。

                知识域：%s
                现有其他知识域：%s

                问答列表：
                %s

                分组规则：
                1. 将主题相关的问答归为一组
                2. 每组对应一个独立的知识域
                3. 组名用 2-5 个中文字，简洁明确（如现有域有相同主题则使用现有域名）
                4. 如果所有条目主题一致，返回一个组（即无需拆分）
                5. 以 JSON 返回，不要任何解释

                返回格式：
                {
                  "groups": [
                    {
                      "name": "新域名",
                      "entries": [0, 2, 5],
                      "reason": "这些都是关于XX的内容"
                    }
                  ]
                }

                分组结果：
                """.formatted(domain, existingNames, entriesText);

        try {
            String result = callLlmWithRetry(prompt);
            result = stripJsonBlock(result);
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = MAPPER.readValue(result, Map.class);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> groups = (List<Map<String, Object>>) parsed.getOrDefault("groups", List.of());

            List<SplitGroup> splitGroups = new ArrayList<>();
            for (Map<String, Object> group : groups) {
                String name = (String) group.getOrDefault("name", "未命名");
                @SuppressWarnings("unchecked")
                List<Integer> indices = (List<Integer>) group.getOrDefault("entries", List.of());
                String reason = (String) group.getOrDefault("reason", "");
                if (!indices.isEmpty()) {
                    splitGroups.add(new SplitGroup(name, indices, reason));
                }
            }

            if (splitGroups.isEmpty()) {
                // Fallback: 所有条目归为一组
                List<Integer> allIndices = new ArrayList<>();
                for (int i = 0; i < entries.size(); i++) allIndices.add(i);
                splitGroups.add(new SplitGroup(domain, allIndices, "LLM 未返回有效分组"));
            }

            return new SplitSuggestion(domain, splitGroups);
        } catch (Exception e) {
            log.warn("拆分建议生成失败 [{}]: {}", domain, e.getMessage());
            // Fallback: 所有条目归为一组
            List<Integer> allIndices = new ArrayList<>();
            for (int i = 0; i < entries.size(); i++) allIndices.add(i);
            return new SplitSuggestion(domain, List.of(
                    new SplitGroup(domain, allIndices, "AI 分析失败，保持原样")
            ));
        }
    }

    /**
     * 分析域内重复/相似条目。
     */
    public MergeSuggestion suggestMerge(String domain) {
        List<KnowledgeService.EntryRef> entries = knowledgeService.listEntries(domain);
        if (entries.size() < 2) {
            return new MergeSuggestion(List.of());
        }

        StringBuilder entriesText = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            var e = entries.get(i);
            entriesText.append(i).append(". ").append(e.question()).append("\n");
        }

        String prompt = """
                分析以下知识域中的问答条目，找出重复或高度相似的条目。

                知识域：%s
                问答列表：
                %s

                规则：
                1. 只在问题实质相同或高度重叠时标记为重复（如问法不同但答案是同一知识点）
                2. 轻微相关不算重复
                3. 以 JSON 返回，不要解释
                4. 如果没有重复，返回空数组

                返回格式：
                {
                  "duplicates": [
                    {
                      "entries": [0, 3],
                      "reason": "两个问题都在问 Spring IoC 的实现原理"
                    }
                  ]
                }

                结果：
                """.formatted(domain, entriesText);

        try {
            String result = callLlmWithRetry(prompt);
            result = stripJsonBlock(result);
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = MAPPER.readValue(result, Map.class);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> dups = (List<Map<String, Object>>) parsed.getOrDefault("duplicates", List.of());

            List<DuplicateGroup> groups = new ArrayList<>();
            for (Map<String, Object> dup : dups) {
                @SuppressWarnings("unchecked")
                List<Integer> indices = (List<Integer>) dup.getOrDefault("entries", List.of());
                String reason = (String) dup.getOrDefault("reason", "");
                if (indices.size() >= 2) {
                    groups.add(new DuplicateGroup(indices, reason));
                }
            }

            return new MergeSuggestion(groups);
        } catch (Exception e) {
            log.warn("去重建议生成失败 [{}]: {}", domain, e.getMessage());
            return new MergeSuggestion(List.of());
        }
    }

    /**
     * 执行拆分（用户确认后调用）。
     */
    public SplitResult executeSplit(String domain, SplitPlan plan) {
        List<KnowledgeService.EntryRef> entries = knowledgeService.listEntries(domain);
        List<String> createdDomains = new ArrayList<>();
        int totalMoved = 0;
        Set<Integer> migratedIndices = new HashSet<>();

        for (SplitGroup group : plan.groups()) {
            String targetDomain = group.suggestedName();
            // 跳过与原域同名的组（表示保留在原域）
            if (targetDomain.equals(domain)) continue;

            List<Integer> indices = group.entryIndices();
            if (indices.isEmpty()) continue;

            // 迁移条目到新域（保留 sources）
            for (int idx : indices) {
                if (idx < 0 || idx >= entries.size()) continue;
                KnowledgeService.EntryRef entry = entries.get(idx);
                knowledgeService.appendEntry(targetDomain, entry.question(), entry.answer(),
                        parseSources(entry.sources()));
                migratedIndices.add(idx);
                totalMoved++;
            }

            createdDomains.add(targetDomain);
            log.info("拆分: 迁移 {} 条条目从 '{}' 到 '{}'", indices.size(), domain, targetDomain);
        }

        // 从原域删除已迁移的条目
        if (!migratedIndices.isEmpty()) {
            removeMigratedEntries(domain, entries, migratedIndices);
        }

        // 重新索引所有受影响的新域
        for (String newDomain : createdDomains) {
            knowledgeService.reindexDomain(newDomain);
        }

        // 如果原域已空，删除它
        if (knowledgeService.listEntries(domain).isEmpty()) {
            knowledgeService.deleteDomain(domain);
            log.info("拆分完成: 原域 '{}' 已空，已删除", domain);
        } else {
            // 重建原域向量索引
            knowledgeService.reindexDomain(domain);
        }

        return new SplitResult(createdDomains, totalMoved);
    }

    /**
     * 从原域 markdown 文件中删除已迁移的条目。
     */
    private void removeMigratedEntries(String domain, List<KnowledgeService.EntryRef> entries, Set<Integer> migratedIndices) {
        String content = knowledgeService.getKnowledgeContent(domain);
        if (content.isEmpty()) return;

        // 保留未迁移的条目，重新组装文件
        StringBuilder sb = new StringBuilder();
        sb.append("# 知识域: ").append(domain).append("\n\n");

        boolean first = true;
        for (int i = 0; i < entries.size(); i++) {
            if (migratedIndices.contains(i)) continue;
            KnowledgeService.EntryRef entry = entries.get(i);
            if (!first) sb.append("\n");
            // 使用原始文件中的精确文本（保留完整格式）
            String entryText = content.substring(entry.start(), entry.end());
            sb.append(entryText);
            // 确保条目以分隔符结尾
            if (!entryText.endsWith("\n---\n") && !entryText.endsWith("---\n") && !entryText.endsWith("---")) {
                sb.append("\n---\n");
            } else if (entryText.endsWith("---")) {
                sb.append("\n");
            }
            first = false;
        }

        knowledgeService.rewriteDomainFile(domain, sb.toString());
        log.info("从 '{}' 删除了 {} 条已迁移条目", domain, migratedIndices.size());
    }

    // ---- 内部方法 ----

    /**
     * 解析 sources 字符串为 Citation 列表。
     * sources 格式: [title](url), [title](url)
     */
    private List<ChatMessage.Citation> parseSources(String sources) {
        if (sources == null || sources.isBlank()) return null;

        List<ChatMessage.Citation> citations = new ArrayList<>();
        Pattern pattern = Pattern.compile("\\[([^]]+)\\]\\(([^)]+)\\)");
        Matcher matcher = pattern.matcher(sources);
        while (matcher.find()) {
            String title = matcher.group(1);
            String url = matcher.group(2);
            citations.add(ChatMessage.Citation.builder().title(title).url(url).build());
        }
        return citations.isEmpty() ? null : citations;
    }

    /**
     * 调用 LLM，遇到内容类型解析错误时自动重试。
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
                        Thread.sleep(500L * attempt);
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

    private String stripJsonBlock(String text) {
        if (text == null) return "{}";
        text = text.trim();
        if (text.startsWith("```")) {
            text = text.replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "");
        }
        return text.trim();
    }
}
