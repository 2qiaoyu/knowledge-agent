package com.knowledge.controller;

import com.knowledge.service.KnowledgeService;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/knowledge")
@CrossOrigin(origins = "*")
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    public KnowledgeController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
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
}
