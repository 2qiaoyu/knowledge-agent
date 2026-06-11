package com.knowledge.service;

import com.knowledge.model.ChatMessage;
import com.knowledge.model.ChatMessage.Citation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final Path basePath;
    private final VectorStore vectorStore;

    public KnowledgeService(
            @Value("${knowledge.base-path}") String basePath,
            VectorStore vectorStore) {
        this.basePath = Paths.get(basePath);
        this.vectorStore = vectorStore;
        try {
            Files.createDirectories(this.basePath);
        } catch (IOException e) {
            throw new RuntimeException("Cannot create knowledge directory", e);
        }
    }

    /**
     * Find the best matching domain file for a question, or create a new one.
     */
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
