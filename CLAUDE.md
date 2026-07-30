# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

个人知识库 Agent — 智能问答系统，支持联网搜索，对话内容自动整理为结构化 Markdown 知识库。

## 常用命令

```bash
# 启动基础设施 (Chroma)
docker-compose up -d

# 后端 (Java 21, Spring Boot 4.1)
cd backend
mvn spring-boot:run          # 启动后端 (localhost:8080)
mvn compile                  # 仅编译
mvn test                     # 运行测试

# 前端 (React 18 + Vite)
cd frontend
npm install                  # 安装依赖
npm run dev                  # 启动前端 (localhost:3000, API 代理到 :8080)
npm run build                # 生产构建
```

## 技术栈

| 层 | 技术 |
|---|---|
| 前端 | React 18 + Vite + Zustand + Tailwind CSS 4 |
| 后端 | Java 21 + Spring Boot 4.1 + Spring AI 2.0.0 + WebFlux |
| LLM | DeepSeek Chat (OpenAI 兼容协议, 通过 Spring AI OpenAI starter) |
| 向量嵌入 | Ollama (nomic-embed-text, 本地) |
| 向量数据库 | Chroma DB (Docker) |
| 搜索 | Serper.dev (Google Search API, 免费 2500 次/月) |
| Markdown 渲染 | streamdown + @streamdown/code (Shiki 语法高亮) |

## 架构

### 请求流程

```
用户提问 → ChatController (SSE 流式)
  → ChatService.buildPrompt()
    → [可选] WebSearchService.search() → Serper.dev (Google Search API)
    → [可选] KnowledgeService.retrieveContext() → Chroma 向量检索
  → ChatClient (DeepSeek via Spring AI) → 流式生成
  → ChatService.saveAnswer() → KnowledgeService.classifyDomainWithLlm() → LLM 分类到知识域
  → KnowledgeService.appendEntry() → Markdown 文件 + Chroma 索引
  → SessionService → data/sessions.json
```

### 后端分层

- **controller/** — REST + SSE 端点
  - `ChatController` — `/api/chat/stream` (SSE), `/api/sessions`
  - `KnowledgeController` — `/api/knowledge/domains`
  - `ProviderController` — `/api/providers` (获取可用 LLM 供应商)
- **service/** — 业务逻辑
  - `ChatService` — 核心编排：提示词构建、流式调用 LLM、异步保存知识
  - `WebSearchService` — Serper.dev Google Search API，返回有机搜索结果 + AnswerBox
  - `KnowledgeService` — Markdown 文件读写 + Chroma 向量索引/检索 + LLM 知识域分类
  - `SessionService` — 会话 CRUD，持久化到 `data/sessions.json`
- **model/** — DTO: `ChatRequest`, `ChatMessage`, `Session`
- **config/** — Spring AI 自动配置 + Jackson ObjectMapper 自定义 + 多 LLM 供应商 ChatClient 配置
  - `SpringAiConfig` — ObjectMapper 自定义
  - `ChatClientConfig` — 多供应商 ChatClient 配置（DeepSeek + LongCat）

### 前端状态管理 (Zustand store)

全局状态集中在 `store.js`，包含：
- **sessions** — 会话列表、当前会话 ID
- **messages** — 当前对话消息
- **streaming** — SSE 流式状态 (`streaming`, `streamingContent`)
- **enableWebSearch** — 联网搜索开关
- **domains** — 知识域列表和内容

`sendMessage()` 通过 `fetch` + `ReadableStream` 消费 SSE，用 `[SESSION_ID:xxx]` 和 `[DONE]` 控制流边界。

### 联网搜索

使用 Serper.dev 作为搜索提供商（Google Search 结果）：
- **API**: `POST https://google.serper.dev/search`，Header `X-API-KEY`
- **参数**: `gl=cn, hl=zh-cn`（中文语境）
- **免费额度**: 2,500 次/月，注册获取 API Key：https://serper.dev
- **结果解析**: `organic[]` 数组（title + link + snippet），`organic` 为空时提取 `answerBox`
- **环境变量**: `SERPER_API_KEY`（必填，搜索功能启用前提）

提示词策略：搜索结果放在问题之前，system 消息明确指令"搜索结果优先于自身知识"，信息不足时要求说明。

### 数据存储

- `data/knowledge/{domain}.md` — 按知识域组织的 Markdown 文件，Q&A 增量追加
- `data/sessions.json` — 会话和消息历史
- `data/chroma/` — Chroma 向量数据库持久化目录
- 向量嵌入由 Ollama 本地生成 (`nomic-embed-text`)，无需外部 API

### 知识域分类

每次问答结束后，系统自动将内容分类到对应知识域（`KnowledgeService.classifyDomainWithLlm()`）：

1. **LLM 智能分类**：将问答内容（问题 + 回答前 500 字）+ 现有知识域列表发送给 LLM，由其判断归入现有域或创建新域
2. **新域命名**：LLM 返回 2-5 个中文字的简洁名称（如"前端开发"、"Python"、"机器学习"）
3. **Fallback**：LLM 调用失败时回退到旧的向量相似度匹配（`classifyDomain()`，已废弃）
4. **手动覆盖**：`ChatRequest.domain` 字段可指定知识域，跳过自动分类

分类结果决定 Q&A 写入 `data/knowledge/{domain}.md` 的哪个文件。

### SSE 协议

后端 SSE 流格式（ChatController.streamChat）：
```
[SESSION_ID:<id>]    ← 首条，携带会话 ID
<chunk>              ← LLM 文本片段（Spring WebFlux 自动包装为 SSE data: 行）
[DONE]               ← 末条，标记流结束
```

前端 `store.js` 通过 `parseSSEBuffer()` 解析 SSE 事件，处理 `[SESSION_ID]` / `[DONE]` 控制消息。

### 前端组件树

```
App
├── Sidebar
│   ├── 会话列表 (新建/切换/删除)
│   └── 知识域列表 (查看/删除)
└── ChatContainer
    ├── ChatMessages → MarkdownViewer (streamdown + Shiki)
    └── ChatInput (textarea + 联网搜索复选框)
```

### 多 LLM 供应商

支持 DeepSeek 和 LongCat（美团）作为 LLM 供应商，可手动切换：

- **配置方式**：`application.yml` 中的 `llm.providers` 部分
- **环境变量**：`DEEPSEEK_API_KEY`（默认）、`LONGCAT_API_KEY`（可选）
- **API 端点**：`GET /api/providers` 返回可用供应商列表
- **前端切换**：输入框下方下拉选择器（仅当多个供应商可用时显示）
- **请求参数**：`ChatRequest.provider` 字段（"deepseek" 或 "longcat"）

```yaml
llm:
  default-provider: deepseek
  providers:
    deepseek:
      api-key: ${DEEPSEEK_API_KEY}
      base-url: https://api.deepseek.com
      model: deepseek-chat
    longcat:
      api-key: ${LONGCAT_API_KEY:}
      base-url: https://api.longcat.chat/openai
      model: LongCat-Flash-Chat
```

### 关键配置

- `backend/src/main/resources/application.yml` — Spring AI、Chroma、Ollama、WebSearch、LLM 供应商配置
- `.env` — `DEEPSEEK_API_KEY`（必填）、`LONGCAT_API_KEY`（可选）、`SERPER_API_KEY`（搜索必填）
- `frontend/vite.config.js` — 开发端口 3000，`/api` 代理到 `localhost:8080`
- `docker-compose.yml` — Chroma (8000)
