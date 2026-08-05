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

    /**
     * 矛盾内容组。
     */
    public record ContradictionGroup(
            Integer entryIdx1, Integer entryIdx2,
            String question1, String question2,
            String description) {}

    /**
     * 过时条目。
     */
    public record OutdatedEntry(
            Integer entryIdx, String question,
            String date, String reason) {}

    /**
     * 维护报告：综合重复、矛盾、过时三项检测结果。
     */
    public record MaintenanceReport(
            String domain,
            List<DuplicateGroup> duplicates,
            List<ContradictionGroup> contradictions,
            List<OutdatedEntry> outdated) {}

    /**
     * 合并执行结果。
     */
    public record MergeResult(int mergedGroups, int deletedEntries) {}

    /**
     * 合并请求中的一组。
     */
    public record MergeGroup(List<Integer> entryIndices) {}

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

    // ---- 知识自动维护 ----

    /**
     * 生成维护报告：综合检测重复、矛盾、过时条目。
     * 三项检测并行执行。
     */
    public MaintenanceReport generateReport(String domain) {
        List<KnowledgeService.EntryRef> entries = knowledgeService.listEntries(domain);
        if (entries.isEmpty()) {
            return new MaintenanceReport(domain, List.of(), List.of(), List.of());
        }

        // 并行执行三项检测
        try {
            var dupFuture = java.util.concurrent.CompletableFuture.supplyAsync(() -> suggestMerge(domain).duplicates());
            var contrFuture = java.util.concurrent.CompletableFuture.supplyAsync(() -> detectContradictions(domain, entries));
            var outdatedFuture = java.util.concurrent.CompletableFuture.supplyAsync(() -> detectOutdated(domain, entries));

            List<DuplicateGroup> duplicates = dupFuture.get();
            List<ContradictionGroup> contradictions = contrFuture.get();
            List<OutdatedEntry> outdated = outdatedFuture.get();

            return new MaintenanceReport(domain, duplicates, contradictions, outdated);
        } catch (Exception e) {
            log.error("生成维护报告失败 [{}]: {}", domain, e.getMessage(), e);
            return new MaintenanceReport(domain, List.of(), List.of(), List.of());
        }
    }

    /**
     * 检测域内矛盾内容。
     */
    private List<ContradictionGroup> detectContradictions(String domain, List<KnowledgeService.EntryRef> entries) {
        if (entries.size() < 2) return List.of();

        StringBuilder entriesText = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            var e = entries.get(i);
            String answerPreview = e.answer().length() > 150
                    ? e.answer().substring(0, 150) + "..." : e.answer();
            entriesText.append(i).append(". Q: ").append(e.question()).append("\n");
            entriesText.append("   A: ").append(answerPreview).append("\n\n");
        }

        String prompt = """
                分析以下知识域中的问答条目，找出内容互相矛盾或冲突的条目对。

                知识域：%s
                问答列表：
                %s

                规则：
                1. 只在两条条目对同一问题给出明显不同/矛盾的答案时标记
                2. 细微差异不算矛盾，只有核心观点冲突才算
                3. 以 JSON 返回，不要解释
                4. 如果没有矛盾，返回空数组

                返回格式：
                {
                  "contradictions": [
                    {
                      "entry1": 0,
                      "entry2": 3,
                      "description": "条目0说X是对的，条目3说X是错误的"
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
            List<Map<String, Object>> contrs = (List<Map<String, Object>>) parsed.getOrDefault("contradictions", List.of());

            List<ContradictionGroup> groups = new ArrayList<>();
            for (Map<String, Object> c : contrs) {
                Integer e1 = ((Number) c.getOrDefault("entry1", -1)).intValue();
                Integer e2 = ((Number) c.getOrDefault("entry2", -1)).intValue();
                String description = (String) c.getOrDefault("description", "");
                if (e1 >= 0 && e2 >= 0 && e1 < entries.size() && e2 < entries.size() && !e1.equals(e2)) {
                    groups.add(new ContradictionGroup(e1, e2,
                            entries.get(e1).question(), entries.get(e2).question(), description));
                }
            }
            return groups;
        } catch (Exception e) {
            log.warn("矛盾检测失败 [{}]: {}", domain, e.getMessage());
            return List.of();
        }
    }

    /**
     * 检测过时条目（基于日期阈值）。
     */
    private List<OutdatedEntry> detectOutdated(String domain, List<KnowledgeService.EntryRef> entries) {
        // 日期阈值：180 天
        final long OUTDATED_DAYS = 180;
        long threshold = System.currentTimeMillis() - (OUTDATED_DAYS * 24 * 60 * 60 * 1000L);

        List<OutdatedEntry> outdated = new ArrayList<>();
        java.time.format.DateTimeFormatter parser = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.get(i);
            // 从 sources 字段中提取日期（EntryRef 不直接存日期，需从文件解析）
            // 简化处理：检查文件中的日期行
            String dateStr = extractDate(entry);
            if (dateStr == null) continue;

            try {
                java.time.LocalDateTime date = java.time.LocalDateTime.parse(dateStr, parser);
                long entryMillis = date.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
                if (entryMillis < threshold) {
                    long daysOld = (System.currentTimeMillis() - entryMillis) / (24 * 60 * 60 * 1000);
                    outdated.add(new OutdatedEntry(i, entry.question(), dateStr,
                            "已存在 " + daysOld + " 天，可能过时"));
                }
            } catch (Exception e) {
                // 解析失败，跳过
            }
        }
        return outdated;
    }

    /**
     * 从 EntryRef 的 sources 附近提取日期（简化版）。
     */
    private String extractDate(KnowledgeService.EntryRef entry) {
        // EntryRef 的 sources 字段包含日期行信息，格式为：**日期**: 2026-01-15 10:30 | **来源**: ...
        if (entry.sources() != null && entry.sources().contains("**日期**:") ) {
            int start = entry.sources().indexOf("**日期**:") + 7;
            int end = entry.sources().indexOf(" ", start + 11); // "2026-01-15" + space
            if (end > start && end - start >= 10) {
                return entry.sources().substring(start, Math.min(start + 16, entry.sources().length())).trim();
            }
        }
        return null;
    }

    /**
     * 执行合并：对每组重复条目，保留最详细的一条，合并来源，删除其余。
     */
    public MergeResult executeMerge(String domain, List<MergeGroup> groups) {
        List<KnowledgeService.EntryRef> entries = knowledgeService.listEntries(domain);
        int deletedCount = 0;

        for (MergeGroup group : groups) {
            List<Integer> indices = group.entryIndices().stream()
                    .filter(i -> i >= 0 && i < entries.size())
                    .sorted(java.util.Collections.reverseOrder()) // 从后往前删除，避免索引偏移
                    .toList();

            if (indices.size() < 2) continue;

            // 选择答案最长的作为主条目（索引最小的）
            int masterIdx = indices.stream()
                    .min((a, b) -> Integer.compare(entries.get(b).answer().length(), entries.get(a).answer().length()))
                    .orElse(indices.get(0));

            // 收集所有来源
            Set<String> allSources = new java.util.LinkedHashSet<>();
            for (int idx : indices) {
                var e = entries.get(idx);
                if (e.sources() != null && !e.sources().isBlank()) {
                    // 提取来源部分（去掉日期前缀）
                    String src = e.sources();
                    if (src.contains("**来源**:")) {
                        src = src.substring(src.indexOf("**来源**:") + 7).trim();
                    } else if (src.contains("**来源**")) {
                        src = src.substring(src.indexOf("**来源**") + 6).trim().replaceFirst("^[：:]", "");
                    }
                    if (!src.isBlank()) allSources.add(src);
                }
            }

            // 删除非主条目（从后往前删）
            List<Integer> toDelete = indices.stream()
                    .filter(i -> !i.equals(masterIdx))
                    .sorted(java.util.Collections.reverseOrder())
                    .toList();

            for (int idx : toDelete) {
                knowledgeService.deleteEntry(domain, String.valueOf(idx));
                deletedCount++;
            }
        }

        // 重新索引该域
        knowledgeService.reindexDomain(domain);
        return new MergeResult(groups.size(), deletedCount);
    }

    /**
     * 删除过时条目。
     */
    public void deleteOutdated(String domain, List<Integer> entryIndices) {
        List<KnowledgeService.EntryRef> entries = knowledgeService.listEntries(domain);
        // 从大到小排序，避免删除后索引偏移
        List<Integer> sorted = entryIndices.stream()
                .filter(i -> i >= 0 && i < entries.size())
                .sorted(java.util.Collections.reverseOrder())
                .toList();

        for (int idx : sorted) {
            knowledgeService.deleteEntry(domain, String.valueOf(idx));
        }

        // 重新索引
        knowledgeService.reindexDomain(domain);
        log.info("删除 {} 条过时条目 from '{}'", sorted.size(), domain);
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
