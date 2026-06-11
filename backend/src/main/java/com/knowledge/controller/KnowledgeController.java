package com.knowledge.controller;

import com.knowledge.service.KnowledgeService;
import org.springframework.web.bind.annotation.*;

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

    @DeleteMapping("/domains/{domain}")
    public Map<String, String> deleteDomain(@PathVariable String domain) {
        knowledgeService.deleteDomain(domain);
        return Map.of("status", "deleted");
    }
}
