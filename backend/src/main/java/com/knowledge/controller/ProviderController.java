package com.knowledge.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * LLM 供应商管理 API
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ProviderController {

    @Value("${llm.default-provider:deepseek}")
    private String defaultProvider;

    @Value("${llm.providers.deepseek.api-key:}")
    private String deepseekApiKey;

    @Value("${llm.providers.longcat.api-key:}")
    private String longcatApiKey;

    /**
     * 获取可用的 LLM 供应商列表
     */
    @GetMapping("/providers")
    public Map<String, Object> getProviders() {
        List<String> available = new ArrayList<>();
        if (deepseekApiKey != null && !deepseekApiKey.isBlank()) {
            available.add("deepseek");
        }
        if (longcatApiKey != null && !longcatApiKey.isBlank()) {
            available.add("longcat");
        }
        return Map.of(
                "default", defaultProvider,
                "available", available
        );
    }
}
