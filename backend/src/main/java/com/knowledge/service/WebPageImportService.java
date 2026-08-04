package com.knowledge.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 网页导入服务：抓取网页内容并使用 LLM 提炼 Q&A 条目。
 */
@Service
public class WebPageImportService {

    private static final Logger log = LoggerFactory.getLogger(WebPageImportService.class);

    /** 正文提取最大字符数（避免超出 LLM token 限制） */
    private static final int MAX_TEXT_LENGTH = 8000;

    /** 抓取超时时间（毫秒） */
    private static final int FETCH_TIMEOUT_MS = 15000;

    private final ChatClient defaultChatClient;
    private final ChatClient deepseekChatClient;
    private final ChatClient longcatChatClient;

    public WebPageImportService(
            ChatClient defaultChatClient,
            @org.springframework.beans.factory.annotation.Qualifier("deepseekChatClient") ChatClient deepseekChatClient,
            @org.springframework.beans.factory.annotation.Qualifier("longcatChatClient") ChatClient longcatChatClient) {
        this.defaultChatClient = defaultChatClient;
        this.deepseekChatClient = deepseekChatClient;
        this.longcatChatClient = longcatChatClient;
    }

    /**
     * 抓取网页并提取正文内容。
     *
     * @param url 网页 URL
     * @return WebPageContent 包含标题、正文文本和字符数
     * @throws IOException 抓取失败时抛出
     */
    public WebPageContent fetchAndExtract(String url) throws IOException {
        log.info("Fetching URL: {}", url);

        Document doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .timeout(FETCH_TIMEOUT_MS)
                .followRedirects(true)
                .get();

        // 移除无关元素
        doc.select("script, style, nav, footer, header, aside, .ad, .advertisement, #comment, .comment, .share, .related").remove();

        // 提取标题
        String title = doc.title();
        if (title == null || title.isBlank()) {
            Element h1 = doc.selectFirst("h1");
            if (h1 != null) title = h1.text();
        }
        if (title == null || title.isBlank()) {
            title = "未命名网页";
        }

        // 正文提取优先级
        Element content = doc.selectFirst("article");
        if (content == null) content = doc.selectFirst("#js_content");       // 微信公众号
        if (content == null) content = doc.selectFirst(".rich_media_content"); // 微信公众号旧版
        if (content == null) content = doc.selectFirst("main");
        if (content == null) content = doc.body();

        if (content == null) {
            throw new IOException("无法提取网页正文");
        }

        // 提取文本，保留段落结构
        String text = content.text();
        if (text == null || text.isBlank()) {
            throw new IOException("网页正文为空");
        }

        log.info("Fetched '{}': {} chars", title, text.length());
        return new WebPageContent(title, text, text.length());
    }

    /**
     * 使用 LLM 从网页文本中提炼 Q&A 条目。
     *
     * @param title       网页标题
     * @param text        网页正文文本
     * @param provider    LLM 供应商（deepseek / longcat）
     * @param targetDomain 目标知识域（可为 null，但网页导入通常由 Controller 预分类后传入）
     * @return Q&A 列表
     */
    public List<KnowledgeService.QAPair> extractQAPairs(String title, String text, String provider, String targetDomain) {
        ChatClient client = resolveClient(provider);

        // 截取前 8000 字符
        String truncatedText = text.length() > MAX_TEXT_LENGTH
                ? text.substring(0, MAX_TEXT_LENGTH)
                : text;

        String prompt = """
                你是一个知识库整理助手。请从以下网页内容中提炼出概念性的 Q&A 条目。

                %s

                要求：
                1. 仔细阅读内容，理解核心概念和知识点
                2. 提炼概念性、原理性的内容（不是新闻、广告、产品介绍等）
                3. 将相关内容合并为主题明确的 Q&A 对（不要把每个小标题单独拆成一条，而是把相关联的内容合并）
                4. 问题要完整、自包含，用"什么是..."/"如何..."/"为什么..."/"xxx的作用/原理/优势..."等形式
                5. 回答要详尽、通顺，包含必要的细节和示例，不要碎片化
                6. 跳过目录、链接列表、参考来源、作者简介等非知识性内容
                7. 如果内容与已有条目高度相似，请合并或更新，而不是重复创建
                8. 提炼出 3-10 个高质量的 Q&A 对（宁可少而精，不要多而碎）

                回答格式要求（Markdown，层次分明）：
                - 回答开头用 1-2 句话概括核心要点
                - 使用 ## 二级标题划分不同方面（如"核心概念"、"工作原理"、"使用场景"、"优势与局限"等）
                - 标题之间用空行分隔
                - 列表项之间用空行分隔
                - 关键术语使用**加粗**标注
                - 如有步骤或流程，使用有序列表
                - 如有代码或命令示例，使用代码块包裹

                以 JSON 数组格式返回：
                [{"question": "...", "answer": "回答内容（Markdown 格式）"}, ...]

                只返回 JSON 数组，不要任何解释或 Markdown 包裹。

                网页标题：%s

                网页内容：
                ```
                %s
                ```

                Q&A JSON：
                """.formatted(
                targetDomain == null ? "" : "目标知识域：" + targetDomain,
                title,
                truncatedText
        );

        try {
            String response = client.prompt().user(prompt).call().content();
            List<KnowledgeService.QAPair> pairs = KnowledgeService.parseQAPairsStatic(response);
            log.info("Extracted {} Q&A pairs from '{}'", pairs.size(), title);
            return pairs;
        } catch (Exception e) {
            log.error("Failed to extract Q&A from '{}': {}", title, e.getMessage(), e);
            throw new RuntimeException("提炼 Q&A 失败: " + e.getMessage(), e);
        }
    }

    private ChatClient resolveClient(String provider) {
        if ("longcat".equals(provider) && longcatChatClient != null) return longcatChatClient;
        if ("deepseek".equals(provider) && deepseekChatClient != null) return deepseekChatClient;
        return defaultChatClient;
    }

    /**
     * 网页抓取结果。
     */
    public record WebPageContent(String title, String text, int charCount) {}
}
