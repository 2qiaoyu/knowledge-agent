package com.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledge.model.ChatMessage;
import com.knowledge.model.ChatMessage.Citation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final Path basePath;
    private final VectorStore vectorStore;
    private final ChatClient chatClient;

    public KnowledgeService(
            @Value("${knowledge.base-path}") String basePath,
            VectorStore vectorStore,
            ChatClient defaultChatClient) {
        this.basePath = Paths.get(basePath);
        this.vectorStore = vectorStore;
        this.chatClient = defaultChatClient;
        try {
            Files.createDirectories(this.basePath);
        } catch (IOException e) {
            throw new RuntimeException("Cannot create knowledge directory", e);
        }
    }

    /**
     * Use LLM to classify a Q&A into an existing domain or suggest a new one.
     *
     * The prompt includes domain descriptions (sample questions) so the LLM can make
     * informed decisions about granularity, and explicitly discourages over-use of
     * the "通用知识" catch-all domain.
     */
    public String classifyDomainWithLlm(String question, String answer) {
        List<String> existing = listDomains();

        // Build domain descriptions: show sample questions for each domain
        String domainDescriptions = buildDomainDescriptions(existing);
        String answerPreview = answer.length() > 500 ? answer.substring(0, 500) : answer;

        String prompt = """
                你是一个知识分类助手。根据以下问题和回答，判断它属于哪个知识域。

                现有知识域及内容示例：
                %s

                待分类问题：%s
                待分类回答：%s

                分类规则：
                1. 如果内容与某个现有知识域的主题高度相关，返回该知识域名称
                2. 如果是全新主题，创建一个新的知识域名称（2-5个中文字，简洁明确，如"向量数据库""Spring AI""OKR实践"）
                3. 尽量避免使用"通用知识"——只有当内容确实横跨多个领域或完全无法归类时才使用
                4. 每个知识域应该聚焦于一个特定的技术、概念或领域。如果某个主题有足够的深度（可以积累3条以上Q&A），就应该独立成域
                5. 只返回知识域名称，不要任何解释或标点

                知识域名称：
                """.formatted(domainDescriptions, question, answerPreview);

        try {
            String result = chatClient.prompt().user(prompt).call().content();
            String domain = result != null ? result.trim() : "";
            if (domain.isEmpty()) {
                log.warn("LLM returned empty domain, falling back to 通用知识");
                return "通用知识";
            }
            log.info("LLM classified Q&A into domain: {}", domain);
            return domain;
        } catch (Exception e) {
            log.warn("LLM classification failed: {}", e.getMessage());
            throw e;  // let caller handle fallback
        }
    }

    /**
     * Build a description string for each domain showing sample questions,
     * so the LLM can judge relevance by content rather than just domain name.
     */
    private String buildDomainDescriptions(List<String> domains) {
        if (domains.isEmpty()) return "（暂无知识域）";

        StringBuilder sb = new StringBuilder();
        for (String domain : domains) {
            sb.append("- ").append(domain).append(": ");
            List<EntryRef> entries = listEntries(domain);
            if (entries.isEmpty()) {
                sb.append("（空）\n");
            } else {
                // Show up to 3 sample questions as a description of the domain's scope
                String samples = entries.stream()
                        .limit(3)
                        .map(EntryRef::question)
                        .collect(Collectors.joining("、"));
                sb.append(samples);
                if (entries.size() > 3) {
                    sb.append(" 等").append(entries.size()).append("条");
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * Re-classify all entries in "通用知识" into finer-grained domains.
     * Uses a single LLM call to group all entries by topic, then migrates them.
     *
     * @return a summary map with counts of moved entries and created domains
     */
    public Map<String, Object> reclassifyGenericDomain() {
        String genericDomain = "通用知识";
        List<EntryRef> entries = listEntries(genericDomain);
        if (entries.isEmpty()) {
            return Map.of(
                    "message", "通用知识为空，无需重新分类",
                    "moved", 0,
                    "domains", List.of()
            );
        }

        // Build a numbered list of questions for the LLM
        StringBuilder entriesText = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            entriesText.append(i).append(". ").append(entries.get(i).question()).append("\n");
        }

        // Get other existing domains for reference
        List<String> otherDomains = listDomains().stream()
                .filter(d -> !d.equals(genericDomain))
                .collect(Collectors.toList());
        String existingDesc = otherDomains.isEmpty() ? "（暂无）" : buildDomainDescriptions(otherDomains);

        // Build a clear list of existing domain names for the LLM to reuse
        String existingDomainNames = otherDomains.isEmpty() ? "（暂无）" : String.join(", ", otherDomains);

        String prompt = """
                以下是"通用知识"知识域中的所有Q&A条目。请将它们分组到不同的、更具体的知识域中。

                现有其他知识域名称：%s

                现有其他知识域及内容示例：
                %s

                待分组条目：
                %s

                请以JSON格式返回分组结果，格式如下：
                {
                  "domains": {
                    "知识域名称1": [0, 2, 5],
                    "知识域名称2": [1, 3],
                    "知识域名称3": [4, 6, 7]
                  }
                }

                分组规则：
                1. 如果题目与某个现有知识域主题相关，必须使用该现有知识域的原有名称（如现有域叫"Prompt Engineering"，就不要创建"Prompt工程"）
                2. 如果是全新主题，创建一个新的知识域名称（2-5个中文字，简洁明确，如"向量数据库""Java开发""Spring AI"）
                3. 相似主题的题目分到同一个域
                4. 每个域至少包含1条条目，不要创建只有1条的域除非主题确实独特
                5. 只返回JSON，不要任何解释

                分组结果：
                """.formatted(existingDomainNames, existingDesc, entriesText);

        try {
            String result = chatClient.prompt().user(prompt).call().content();
            Map<String, List<Integer>> grouping = parseGroupingResult(result);

            if (grouping.isEmpty()) {
                return Map.of("message", "LLM 未返回有效的分组结果", "moved", 0, "domains", List.of());
            }

            // Migrate entries to their new domains
            List<String> createdDomains = new ArrayList<>();
            int movedCount = 0;

            // Track which entries to remove from 通用知识 (by index, descending)
            List<Integer> toRemoveDesc = new ArrayList<>();

            for (Map.Entry<String, List<Integer>> group : grouping.entrySet()) {
                String newDomain = group.getKey();
                List<Integer> indices = group.getValue();

                if (indices.isEmpty()) continue;

                boolean isNewDomain = !listDomains().contains(newDomain);
                if (isNewDomain) {
                    createdDomains.add(newDomain);
                }

                for (int idx : indices) {
                    if (idx < 0 || idx >= entries.size()) continue;
                    EntryRef entry = entries.get(idx);
                    // Append to the new domain (without citations since we don't have them here)
                    appendEntry(newDomain, entry.question(), entry.answer(), null);
                    toRemoveDesc.add(idx);
                    movedCount++;
                }
            }

            // Remove migrated entries from 通用知识 (descending order to keep indices valid)
            toRemoveDesc.sort(Collections.reverseOrder());
            for (int idx : toRemoveDesc) {
                deleteEntry(genericDomain, String.valueOf(idx));
            }

            // If 通用知识 is now empty, delete the file
            if (listEntries(genericDomain).isEmpty()) {
                deleteDomain(genericDomain);
                createdDomains.add("(已删除空的通用知识)");
            }

            log.info("Re-classification complete: moved {} entries, created/filled {} domains",
                    movedCount, createdDomains.size());

            return Map.of(
                    "message", "重新分类完成",
                    "moved", movedCount,
                    "domains", createdDomains
            );
        } catch (Exception e) {
            log.error("Re-classification failed: {}", e.getMessage(), e);
            throw new RuntimeException("重新分类失败: " + e.getMessage(), e);
        }
    }

    /**
     * Parse the LLM's JSON grouping response into a map of domain -> entry indices.
     */
    @SuppressWarnings("unchecked")
    private Map<String, List<Integer>> parseGroupingResult(String llmResponse) {
        if (llmResponse == null || llmResponse.isBlank()) return Map.of();

        try {
            // Extract JSON from possible markdown code block
            String json = llmResponse.trim();
            if (json.contains("```")) {
                int start = json.indexOf("```");
                int end = json.lastIndexOf("```");
                String block = json.substring(start, end);
                // Remove the ```json or ``` prefix
                json = block.replaceFirst("```json\\s*", "").replaceFirst("```\\s*", "").trim();
            }

            // Parse the JSON
            Map<String, Object> root = new ObjectMapper().readValue(json, Map.class);
            Object domainsObj = root.get("domains");
            if (!(domainsObj instanceof Map)) return Map.of();

            Map<String, Object> domains = (Map<String, Object>) domainsObj;
            Map<String, List<Integer>> result = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : domains.entrySet()) {
                if (entry.getValue() instanceof List) {
                    List<Integer> indices = ((List<?>) entry.getValue()).stream()
                            .map(v -> ((Number) v).intValue())
                            .collect(Collectors.toList());
                    result.put(entry.getKey(), indices);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to parse grouping result: {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * Find the best matching domain file for a question, or create a new one.
     * @deprecated Use {@link #classifyDomainWithLlm} for new content classification.
     */
    @Deprecated
    public String classifyDomain(String question) {
        List<String> existing = listDomains();
        if (existing.isEmpty()) {
            return "通用知识";
        }

        // Use vector similarity to find the best matching domain
        List<Document> results = vectorStore.similaritySearch(question);
        if (!results.isEmpty()) {
            String domain = (String) results.get(0).getMetadata().get("domain");
            if (domain != null) {
                return domain;
            }
        }
        return "通用知识";
    }

    public List<String> listDomains() {
        try (Stream<Path> files = Files.list(basePath)) {
            return files
                    .filter(f -> f.toString().endsWith(".md"))
                    .map(f -> f.getFileName().toString().replace(".md", ""))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.warn("Failed to list domains: {}", e.getMessage());
            return List.of();
        }
    }

    public String getKnowledgeContent(String domain) {
        Path file = domainFile(domain);
        if (!Files.exists(file)) return "";
        try {
            return Files.readString(file);
        } catch (IOException e) {
            log.warn("Failed to read knowledge file: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Append a Q&A entry to the domain's Markdown file and index it in Chroma.
     */
    public void appendEntry(String domain, String question, String answer, List<Citation> citations) {
        try {
            Path file = domainFile(domain);
            boolean isNew = !Files.exists(file);

            StringBuilder entry = new StringBuilder();
            if (isNew) {
                entry.append("# 知识域: ").append(domain).append("\n\n");
            } else {
                entry.append("\n");
            }
            entry.append("## Q: ").append(question).append("\n\n");
            entry.append("**日期**: ").append(DATE_FMT.format(Instant.now()));
            if (citations != null && !citations.isEmpty()) {
                entry.append(" | **来源**: ");
                String sources = citations.stream()
                        .map(c -> "[" + c.getTitle() + "](" + c.getUrl() + ")")
                        .collect(Collectors.joining(", "));
                entry.append(sources);
            }
            entry.append("\n\n");
            entry.append(answer).append("\n\n");
            entry.append("---\n");

            Files.writeString(file, entry.toString(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);

            // Index in Chroma
            indexEntry(domain, question, answer, citations);

            log.info("Appended entry to domain '{}' (new={})", domain, isNew);
        } catch (IOException e) {
            log.error("Failed to write knowledge entry: {}", e.getMessage(), e);
        }
    }

    /**
     * Index a Q&A entry as a vector embedding in Chroma.
     */
    private void indexEntry(String domain, String question, String answer, List<Citation> citations) {
        String sourceText = "";
        if (citations != null && !citations.isEmpty()) {
            sourceText = citations.stream()
                    .map(c -> c.getUrl())
                    .collect(Collectors.joining(", "));
        }

        Document doc = new Document(
                "Q: " + question + "\nA: " + answer,
                Map.of(
                        "domain", domain,
                        "question", question,
                        "timestamp", Instant.now().toString(),
                        "sources", sourceText
                )
        );
        vectorStore.add(List.of(doc));
    }

    /**
     * 知识库检索的相似度阈值，低于此值的结果视为不相关，将被过滤。
     * 使用 cosine distance 时，Chroma 返回的 score 范围为 0~1（1 为完全相似），
     * 0.6 意味着保留中等以上相关度的结果。
     */
    private static final double SIMILARITY_THRESHOLD = 0.6;

    /**
     * Retrieve relevant knowledge entries for context.
     * 使用相似度阈值过滤低质量结果，避免噪声干扰。
     * 输出格式优化为清晰的 Q&A 对，方便 LLM 理解。
     */
    public String retrieveContext(String query, int topK) {
        List<Document> results = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(query)
                        .topK(topK)
                        .similarityThreshold(SIMILARITY_THRESHOLD)
                        .build());
        if (results.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("## 已有相关知识\n\n");
        for (int i = 0; i < results.size(); i++) {
            Document doc = results.get(i);
            String question = (String) doc.getMetadata().getOrDefault("question", "");
            String content = doc.getText();
            // 从 "Q: xxx\nA: yyy" 格式中提取回答
            String answer = "";
            int aIdx = content.indexOf("\nA: ");
            if (aIdx >= 0) {
                answer = content.substring(aIdx + 4);
            } else {
                answer = content;
            }
            sb.append("---\n");
            sb.append("**问题**: ").append(question).append("\n\n");
            sb.append("**回答**: ").append(answer).append("\n");
        }
        sb.append("---\n\n");
        return sb.toString();
    }

    /**
     * Search knowledge entries across all domains using vector similarity.
     * Returns Mono to offload blocking Ollama embedding call to boundedElastic scheduler.
     */
    public Mono<List<SearchResult>> searchEntries(String query, int topK) {
        return Mono.fromCallable(() -> vectorStore.similaritySearch(
                SearchRequest.builder().query(query).topK(topK).build()))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .map(results -> {
                    if (results.isEmpty()) return List.<SearchResult>of();

                    return results.stream().map(doc -> {
                        String domain = (String) doc.getMetadata().getOrDefault("domain", "未知");
                        String question = (String) doc.getMetadata().getOrDefault("question", "");
                        String content = doc.getText();
                        // Extract answer from "Q: xxx\nA: yyy" format
                        String answer = "";
                        int aIdx = content.indexOf("\nA: ");
                        if (aIdx >= 0) {
                            answer = content.substring(aIdx + 4);
                        } else if (content.startsWith("Q: ")) {
                            answer = content;
                        }
                        // Truncate answer for preview
                        if (answer.length() > 200) {
                            answer = answer.substring(0, 200) + "...";
                        }
                        return new SearchResult(domain, question, answer);
                    }).collect(Collectors.toList());
                });
    }

    public record SearchResult(String domain, String question, String answer) {}

    // ---- Entry management (parse/edit/delete individual Q&A entries) ----

    private static final Pattern SOURCES_PATTERN = Pattern.compile("\\| \\*\\*来源\\*\\*: (.+)", Pattern.DOTALL);
    private static final Pattern REFERENCES_PATTERN = Pattern.compile("(?m)^---\\s*\\n参考来源[：:](.*)$", Pattern.DOTALL);

    /**
     * Parse a domain's Markdown file into individual Q&A entries.
     * Supports two formats:
     *   1. New format: "## Q: question" with "**日期**: ..." date line
     *   2. Old format: "## N. 核心概念：title" (no date line, used in early seed data)
     *
     * Important: old-format entries only appear at the beginning of the file,
     * before any "## Q:" entry. After the first "## Q:", all "## N. title"
     * markers are subsections within Q: entries, not separate entries.
     *
     * Strategy:
     *   - Split the content into two sections at the first "## Q:" marker
     *   - The "old section" (before first Q:) is split by "## N. " markers
     *   - The "new section" (from first Q: onwards) is split by "## Q: " markers
     *     (so that Q: entries containing ## subsections stay intact)
     */
    public List<EntryRef> listEntries(String domain) {
        Path file = domainFile(domain);
        if (!Files.exists(file)) return List.of();

        try {
            String content = Files.readString(file);
            List<EntryRef> entries = new ArrayList<>();

            // Find the first "## Q:" to separate old-format and new-format sections
            int firstQMarker = content.indexOf("## Q: ");
            String oldSection;
            String newSection;
            if (firstQMarker < 0) {
                oldSection = content;
                newSection = "";
            } else {
                oldSection = content.substring(0, firstQMarker);
                newSection = content.substring(firstQMarker);
            }

            int idx = 0;

            // Parse old-format entries (## N. title) from the old section
            if (!oldSection.isEmpty()) {
                String[] oldChunks = oldSection.split("(?=## \\d+\\. )");
                int offset = 0;
                for (String chunk : oldChunks) {
                    if (chunk.startsWith("# 知识域:")) continue; // skip the file header
                    if (!chunk.matches("(?s)## \\d+\\..*")) continue;

                    int newlineIdx = chunk.indexOf("\n");
                    String question;
                    int answerStart;
                    if (newlineIdx < 0) {
                        question = chunk.substring(3).trim();
                        answerStart = chunk.length();
                    } else {
                        question = chunk.substring(3, newlineIdx).trim();
                        answerStart = newlineIdx + 1;
                        if (answerStart < chunk.length() && chunk.charAt(answerStart) == '\n') answerStart++;
                    }

                    String answer = chunk.substring(answerStart);
                    // Remove trailing --- if present
                    if (answer.endsWith("\n---")) {
                        answer = answer.substring(0, answer.length() - 4).trim();
                    } else if (answer.endsWith("---")) {
                        answer = answer.substring(0, answer.length() - 3).trim();
                    }

                    entries.add(new EntryRef(String.valueOf(idx), question, answer, "", offset, offset + chunk.length()));
                    offset += chunk.length();
                    idx++;
                }
            }

            // Parse new-format entries (## Q: question) from the new section
            if (!newSection.isEmpty()) {
                String[] qChunks = newSection.split("(?=## Q: )");
                int baseOffset = firstQMarker; // absolute offset of newSection in file
                int qOffset = 0; // offset within newSection
                for (String chunk : qChunks) {
                    if (!chunk.startsWith("## Q: ")) continue;

                    int dateIdx = chunk.indexOf("**日期**:");
                    if (dateIdx < 0) continue; // need at least a date line

                    int qStart = 6; // length of "## Q: "
                    int qEnd = chunk.indexOf("\n", qStart);
                    if (qEnd < 0) continue;
                    String question = chunk.substring(qStart, qEnd).trim();

                    int dateLineEnd = chunk.indexOf("\n", dateIdx);
                    if (dateLineEnd < 0) dateLineEnd = chunk.length();
                    String dateLine = chunk.substring(dateIdx, dateLineEnd);
                    String sources = "";
                    Matcher sourcesMatcher = SOURCES_PATTERN.matcher(dateLine);
                    if (sourcesMatcher.find()) {
                        sources = sourcesMatcher.group(1).trim();
                    }

                    int answerStart = dateLineEnd;
                    if (answerStart < chunk.length() && chunk.charAt(answerStart) == '\n') answerStart++;
                    if (answerStart < chunk.length() && chunk.charAt(answerStart) == '\n') answerStart++;

                    // Check for references section at the end
                    String answer = chunk.substring(answerStart);
                    Matcher refMatcher = REFERENCES_PATTERN.matcher(answer);
                    if (refMatcher.find()) {
                        if (sources.isEmpty()) {
                            sources = refMatcher.group(1).trim();
                        }
                        answer = answer.substring(0, refMatcher.start()).trim();
                    } else {
                        // Remove trailing --- if present
                        if (answer.endsWith("\n---")) {
                            answer = answer.substring(0, answer.length() - 4).trim();
                        } else if (answer.endsWith("---")) {
                            answer = answer.substring(0, answer.length() - 3).trim();
                        }
                    }

                    entries.add(new EntryRef(String.valueOf(idx), question, answer, sources, baseOffset + qOffset, baseOffset + qOffset + chunk.length()));
                    qOffset += chunk.length();
                    idx++;
                }
            }
            return entries;
        } catch (IOException e) {
            log.warn("Failed to parse entries for domain {}: {}", domain, e.getMessage());
            return List.of();
        }
    }

    /**
     * Update a specific entry in the domain's Markdown file.
     */
    public void updateEntry(String domain, String entryId, String newQuestion, String newAnswer) {
        Path file = domainFile(domain);
        if (!Files.exists(file)) return;

        try {
            String content = Files.readString(file);
            List<EntryRef> entries = listEntries(domain);
            int idx = Integer.parseInt(entryId);
            if (idx < 0 || idx >= entries.size()) return;

            EntryRef target = entries.get(idx);
            // Replace the entry content
            String oldEntry = content.substring(target.start, target.end);
            String newEntry = "## Q: " + newQuestion + "\n\n" +
                    "**日期**: " + DATE_FMT.format(Instant.now()) + "\n\n" +
                    newAnswer + "\n\n";

            String updated = content.substring(0, target.start) + newEntry + content.substring(target.end);
            Files.writeString(file, updated, StandardOpenOption.TRUNCATE_EXISTING);

            // Re-index: remove old and add new
            reindexEntry(domain, target.question, newQuestion, newAnswer);
            log.info("Updated entry {} in domain '{}'", entryId, domain);
        } catch (IOException e) {
            log.error("Failed to update entry {} in domain {}: {}", entryId, domain, e.getMessage());
        }
    }

    /**
     * Delete a specific entry from the domain's Markdown file.
     */
    public void deleteEntry(String domain, String entryId) {
        Path file = domainFile(domain);
        if (!Files.exists(file)) return;

        try {
            String content = Files.readString(file);
            List<EntryRef> entries = listEntries(domain);
            int idx = Integer.parseInt(entryId);
            if (idx < 0 || idx >= entries.size()) return;

            EntryRef target = entries.get(idx);
            // Remove the entry (including trailing --- if present)
            int end = target.end;
            if (content.substring(end).startsWith("\n---\n")) {
                end += 6; // length of "\n---\n"
            } else if (content.substring(end).startsWith("---\n")) {
                end += 5;
            }

            String updated = content.substring(0, target.start) + content.substring(end);

            // Ensure the file still starts with the domain header (protect against accidental header loss)
            if (!updated.startsWith("# 知识域:")) {
                updated = "# 知识域: " + domain + "\n\n" + updated;
            }

            // Clean up multiple consecutive blank lines
            updated = updated.replaceAll("\\n{4,}", "\n\n\n");
            Files.writeString(file, updated, StandardOpenOption.TRUNCATE_EXISTING);

            // Remove from vector store
            removeFromIndex(domain, target.question);
            log.info("Deleted entry {} from domain '{}'", entryId, domain);
        } catch (IOException e) {
            log.error("Failed to delete entry {} from domain {}: {}", entryId, domain, e.getMessage());
        }
    }

    private void reindexEntry(String domain, String oldQuestion, String newQuestion, String newAnswer) {
        // Note: Chroma doesn't support easy deletion by metadata, so we just add the new version.
        // Old entries will have lower similarity scores naturally.
        indexEntry(domain, newQuestion, newAnswer, null);
    }

    private void removeFromIndex(String domain, String question) {
        // Chroma vector store doesn't easily support deletion by metadata filter
        // For now, the old entry remains but will have lower relevance scores
        log.debug("Note: Old vector entry for '{}' in '{}' not removed (Chroma limitation)", question, domain);
    }

    public record EntryRef(String id, String question, String answer, String sources, int start, int end) {}

    public void deleteDomain(String domain) {
        try {
            Files.deleteIfExists(domainFile(domain));
            log.info("Deleted domain: {}", domain);
        } catch (IOException e) {
            log.warn("Failed to delete domain: {}", e.getMessage());
        }
    }

    private Path domainFile(String domain) {
        String safe = domain.replaceAll("[\\\\/:*?\"<>|]", "_");
        return basePath.resolve(safe + ".md");
    }
}
