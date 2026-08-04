package com.knowledge.controller;

import com.knowledge.model.GraphData;
import com.knowledge.service.KnowledgeGraphService;
import com.knowledge.service.KnowledgeMaintenanceService;
import com.knowledge.service.KnowledgeService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RestController
@RequestMapping("/api/knowledge")
@CrossOrigin(origins = "*")
public class KnowledgeController {

    private final KnowledgeService knowledgeService;
    private final KnowledgeGraphService graphService;
    private final KnowledgeMaintenanceService maintenanceService;

    public KnowledgeController(KnowledgeService knowledgeService, KnowledgeGraphService graphService,
                               KnowledgeMaintenanceService maintenanceService) {
        this.knowledgeService = knowledgeService;
        this.graphService = graphService;
        this.maintenanceService = maintenanceService;
    }

    @GetMapping("/domains")
    public List<String> listDomains() {
        return knowledgeService.listDomains();
    }

    @GetMapping("/domains/{domain}")
    public Map<String, Object> getDomain(@PathVariable String domain) {
        return Map.of(
                "domain", domain,
                "content", knowledgeService.getKnowledgeContent(domain)
        );
    }

    /**
     * Recommend related knowledge entries based on a query.
     * Used for proactive knowledge recommendation after conversations.
     */
    @GetMapping("/recommend")
    public Mono<List<KnowledgeService.SearchResult>> recommend(
            @RequestParam("q") String query,
            @RequestParam(value = "limit", defaultValue = "3") int limit) {
        return knowledgeService.recommendEntries(query, limit);
    }

    @GetMapping("/search")
    public Mono<List<KnowledgeService.SearchResult>> search(
            @RequestParam("q") String query,
            @RequestParam(value = "topK", defaultValue = "5") int topK) {
        return knowledgeService.searchEntries(query, topK);
    }

    @GetMapping("/domains/{domain}/entries")
    public List<KnowledgeService.EntryRef> listEntries(@PathVariable String domain) {
        return knowledgeService.listEntries(domain);
    }

    @PutMapping("/domains/{domain}/entries/{entryId}")
    public Map<String, String> updateEntry(
            @PathVariable String domain,
            @PathVariable String entryId,
            @RequestBody Map<String, String> body) {
        String question = body.getOrDefault("question", "");
        String answer = body.getOrDefault("answer", "");
        knowledgeService.updateEntry(domain, entryId, question, answer);
        return Map.of("status", "updated");
    }

    @DeleteMapping("/domains/{domain}/entries/{entryId}")
    public Map<String, String> deleteEntry(
            @PathVariable String domain,
            @PathVariable String entryId) {
        knowledgeService.deleteEntry(domain, entryId);
        return Map.of("status", "deleted");
    }

    @DeleteMapping("/domains/{domain}")
    public Map<String, String> deleteDomain(@PathVariable String domain) {
        knowledgeService.deleteDomain(domain);
        return Map.of("status", "deleted");
    }

    /**
     * Rebuild the vector index for a domain from its Markdown file.
     * Useful for cleaning up stale entries after manual file edits or data corruption.
     *
     * Wrapped in Mono + boundedElastic because reindexing triggers blocking Ollama embedding calls.
     */
    @PostMapping("/domains/{domain}/reindex")
    public Mono<Map<String, Object>> reindexDomain(@PathVariable String domain) {
        return Mono.fromCallable(() -> {
            int count = knowledgeService.reindexDomain(domain);
            return Map.<String, Object>of("status", "reindexed", "entries", count);
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    /**
     * Re-classify entries in "通用知识" into finer-grained domains.
     * Uses LLM to group all entries by topic, then migrates them to new/existing domains.
     *
     * Wrapped in Mono + boundedElastic because appendEntry() triggers a blocking
     * Ollama embedding call (vectorStore.add), which is forbidden on Reactor threads.
     */
    @PostMapping("/reclassify")
    public Mono<Map<String, Object>> reclassify() {
        return Mono.fromCallable(knowledgeService::reclassifyGenericDomain)
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    /**
     * Export all knowledge domains as a zip archive.
     * Each domain becomes a .md file inside the zip.
     */
    @GetMapping("/export")
    public Mono<ResponseEntity<ByteArrayResource>> exportAll() {
        return Mono.fromCallable(() -> {
            Map<String, String> domains = knowledgeService.exportAllDomains();
            String date = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault()).format(Instant.now());

            byte[] zipBytes;
            try (java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                 ZipOutputStream zos = new ZipOutputStream(baos)) {
                for (Map.Entry<String, String> entry : domains.entrySet()) {
                    String safeName = entry.getKey().replaceAll("[\\\\/:*?\"<>|]", "_");
                    zos.putNextEntry(new ZipEntry(safeName + ".md"));
                    zos.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    zos.closeEntry();
                }
                zos.finish();
                zipBytes = baos.toByteArray();
            }

            ByteArrayResource resource = new ByteArrayResource(zipBytes);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"knowledge-export-" + date + ".zip\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(zipBytes.length)
                    .body(resource);
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    /**
     * Import a .md file into a specific knowledge domain.
     * Parses Q&A entries from the file and appends them to the domain.
     *
     * The entire operation (file read + import with Ollama embedding) is wrapped
     * in Mono.fromCallable + boundedElastic because:
     *   1. filePart.content() is a reactive stream that must be consumed on Reactor thread
     *   2. importEntries() triggers blocking Ollama embedding calls (vectorStore.add)
     * We use .block() to read the file content (safe here as it's small), then
     * offload the blocking import work to boundedElastic.
     */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<Map<String, Object>> importKnowledge(
            @RequestParam("domain") String domain,
            @RequestPart("file") Mono<FilePart> filePartMono) {
        return filePartMono.flatMap(filePart -> {
            // Read file content reactively (non-blocking, on Reactor thread)
            Mono<String> contentMono = filePart.content()
                    .reduce(new java.io.ByteArrayOutputStream(), (baos, dataBuffer) -> {
                        byte[] bytes = new byte[dataBuffer.readableByteCount()];
                        dataBuffer.read(bytes);
                        baos.write(bytes, 0, bytes.length);
                        org.springframework.core.io.buffer.DataBufferUtils.release(dataBuffer);
                        return baos;
                    })
                    .map(baos -> baos.toString(java.nio.charset.StandardCharsets.UTF_8));

            // Offload the blocking import work to boundedElastic
            return contentMono.flatMap(content ->
                    Mono.fromCallable(() -> {
                        int count = knowledgeService.importEntries(domain, content);
                        return Map.<String, Object>of("status", "imported", "entries", count);
                    }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
            );
        });
    }

    /**
     * Smart import: use LLM to extract well-structured Q&A pairs from a .md file.
     *
     * If 'domain' is provided, import into that domain.
     * If 'domain' is omitted, LLM auto-classifies the content into the best domain.
     *
     * Two-step process:
     *   1. LLM analyzes the content, classifies domain (if needed), and extracts Q&A pairs
     *   2. Pairs are inserted into the domain (blocking Ollama calls, offloaded to boundedElastic)
     *
     * Step 1 runs on Reactor thread (LLM call is non-blocking WebClient).
     * Step 2 is wrapped in boundedElastic for the blocking embedding calls.
     */
    @PostMapping(value = "/smart-import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<Map<String, Object>> smartImportKnowledge(
            @RequestParam(value = "domain", required = false) String domain,
            @RequestPart("file") Mono<FilePart> filePartMono) {
        return filePartMono.flatMap(filePart -> {
            // Read file content reactively
            Mono<String> contentMono = filePart.content()
                    .reduce(new java.io.ByteArrayOutputStream(), (baos, dataBuffer) -> {
                        byte[] bytes = new byte[dataBuffer.readableByteCount()];
                        dataBuffer.read(bytes);
                        baos.write(bytes, 0, bytes.length);
                        org.springframework.core.io.buffer.DataBufferUtils.release(dataBuffer);
                        return baos;
                    })
                    .map(baos -> baos.toString(java.nio.charset.StandardCharsets.UTF_8));

            // LLM extraction + domain classification (if needed), then insert
            return contentMono.flatMap(content ->
                    Mono.fromCallable(() -> {
                        KnowledgeService.SmartImportResult result =
                                knowledgeService.smartImportWithClassification(content, domain);
                        int count = knowledgeService.importQAPairs(result.domain(), result.pairs());
                        return Map.<String, Object>of(
                                "status", "smart_imported",
                                "domain", result.domain(),
                                "entries", count
                        );
                    }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
            );
        });
    }

    /**
     * 获取知识图谱数据（概念节点 + 关系边）。
     * 首次调用会通过 LLM 提取概念和关系，后续返回缓存。
     */
    @GetMapping("/graph")
    public Mono<GraphData> getGraph() {
        return Mono.fromCallable(graphService::getGraph)
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    /**
     * 重建知识图谱（清空缓存，重新从知识库提取）。
     */
    @PostMapping("/graph/rebuild")
    public Mono<GraphData> rebuildGraph() {
        return Mono.fromCallable(graphService::rebuildGraph)
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    /**
     * 获取域拆分建议（LLM 分析，不执行）。
     */
    @PostMapping("/domains/{domain}/suggest-split")
    public Mono<KnowledgeMaintenanceService.SplitSuggestion> suggestSplit(@PathVariable String domain) {
        return Mono.fromCallable(() -> maintenanceService.suggestSplit(domain))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    /**
     * 获取域内重复条目建议。
     */
    @PostMapping("/domains/{domain}/suggest-merge")
    public Mono<KnowledgeMaintenanceService.MergeSuggestion> suggestMerge(@PathVariable String domain) {
        return Mono.fromCallable(() -> maintenanceService.suggestMerge(domain))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    /**
     * 执行域拆分（用户确认后调用）。
     */
    @PostMapping("/domains/execute-split")
    public Mono<KnowledgeMaintenanceService.SplitResult> executeSplit(@RequestBody SplitRequest body) {
        return Mono.fromCallable(() -> maintenanceService.executeSplit(body.domain(), body.toPlan()))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    /** 拆分请求体 */
    public record SplitRequest(String domain, List<KnowledgeMaintenanceService.SplitGroup> groups) {
        public KnowledgeMaintenanceService.SplitPlan toPlan() {
            return new KnowledgeMaintenanceService.SplitPlan(groups);
        }
    }
}
