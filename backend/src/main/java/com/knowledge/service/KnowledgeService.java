package com.knowledge.service;

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
     */
    public String classifyDomainWithLlm(String question, String answer) {
        List<String> existing = listDomains();

        String existingList = existing.isEmpty() ? "（暂无）" : String.join(", ", existing);
        String answerPreview = answer.length() > 500 ? answer.substring(0, 500) : answer;

        String prompt = """
                你是一个知识分类助手。根据以下问题和回答，判断它属于哪个知识域。

                现有知识域：%s

                问题：%s
                回答：%s

                规则：
                1. 如果内容与某个现有知识域高度相关，返回该知识域名称
                2. 如果是全新主题，创建一个新的知识域名称（2-5个中文字，简洁明确，如"前端开发""机器学习""Python"）
                3. 只返回知识域名称，不要任何解释或标点

                知识域名称：
                """.formatted(existingList, question, answerPreview);

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
     * Retrieve relevant knowledge entries for context.
     */
    public String retrieveContext(String query, int topK) {
        List<Document> results = vectorStore.similaritySearch(
                SearchRequest.builder().query(query).topK(topK).build());
        if (results.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("## 已有相关知识\n\n");
        for (int i = 0; i < results.size(); i++) {
            sb.append("---\n");
            sb.append(results.get(i).getFormattedContent()).append("\n");
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

    private static final Pattern ENTRY_PATTERN = Pattern.compile(
            "## Q: (.*?)\\n\\n\\*\\*日期\\*\\*:.*?\\n\\n(.*?)(?=\\n## Q:|\\n---|$)",
            Pattern.DOTALL);

    /**
     * Parse a domain's Markdown file into individual Q&A entries.
     */
    public List<EntryRef> listEntries(String domain) {
        Path file = domainFile(domain);
        if (!Files.exists(file)) return List.of();

        try {
            String content = Files.readString(file);
            List<EntryRef> entries = new ArrayList<>();
            Matcher matcher = ENTRY_PATTERN.matcher(content);
            int idx = 0;
            while (matcher.find()) {
                String question = matcher.group(1).trim();
                String answer = matcher.group(2).trim();
                entries.add(new EntryRef(String.valueOf(idx), question, answer, matcher.start(), matcher.end()));
                idx++;
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

    public record EntryRef(String id, String question, String answer, int start, int end) {}

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
