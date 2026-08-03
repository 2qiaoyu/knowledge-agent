# 个人知识库 Agent — 需求规划

> 最后更新: 2026-08-03 (v2 — 本轮重构后)

## 项目概述

个人知识库 Agent — 智能问答系统，支持联网搜索，对话内容自动整理为结构化 Markdown 知识库。

技术栈：React 18 + Vite + Zustand | Java 21 + Spring Boot 4.1 + Spring AI 2.0 + WebFlux | Chroma 向量库 | Ollama 嵌入 | DeepSeek LLM

---

## 功能清单

### P0 — 核心功能（已完成 9/9）

| # | 功能 | 状态 | 说明 |
|---|------|------|------|
| 1 | 流式对话 (SSE) | ✅ 已完成 | SSE 流式输出，react-markdown 渐进式渲染 |
| 2 | 多轮记忆 | ✅ 已完成 | MessageChatMemoryAdvisor，按 sessionId 隔离 |
| 3 | 知识库 RAG 检索 | ✅ 已完成 | Chroma 向量相似度检索，topK=3，阈值 0.6 |
| 4 | 联网搜索 | ✅ 已完成 | Serper.dev Google Search API，可开关 |
| 5 | 多 LLM 供应商 | ✅ 已完成 | DeepSeek + LongCat，可手动切换 |
| 6 | 知识域自动分类 | ✅ 已完成 | LLM 智能分类到知识域或创建新域 |
| 7 | 编号引用格式 | ✅ 已完成 | 正文 [1][2] 引用 + 末尾参考来源列表 |
| 8 | 流式 Markdown 渲染 | ✅ 已完成 | 自动修复未闭合标记，渐进式渲染标题/代码块/粗体 |
| 9 | 测试覆盖 | ✅ 已完成 | 后端 36 cases + 前端 42 cases |

### P1 — 高优先级（已完成 4/4）

| # | 功能 | 状态 | 说明 |
|---|------|------|------|
| 10 | 停止生成 | ✅ 已完成 | AbortController 中断 fetch，WebFlux 自动取消 Flux |
| 11 | 重新生成 | ✅ 已完成 | 移除末条 AI 消息，用最后一条用户问题重发 |
| 12 | 知识库搜索 | ✅ 已完成 | Sidebar 搜索框，向量检索跨域搜索，结果带预览 |
| 13 | 知识条目管理 | ✅ 已完成 | 查看/编辑/删除单条 Q&A 条目，内联编辑 |

### P2 — 中优先级（已完成 4/4 🎉）

| # | 功能 | 状态 | 说明 |
|---|------|------|------|
| 14 | 会话导出 | ✅ 已完成 | 前端导出会话为 Markdown 文件 (`9442bea`) |
| 15 | 知识库导入/导出 | ✅ 已完成 | 导出全部域为 zip / 快速导入（按标题拆分）/ 智能导入（LLM 提炼 Q&A） |
| 16 | 消息编辑 | ✅ 已完成 | 修改已发送的用户消息，后续 AI 回复联动更新 (`d55e43c`) |
| 17 | 删除单条消息 | ✅ 已完成 | 删除对话中的单条用户/AI 消息 (`a97cc24`) |

### P3 — 体验优化（待开发 0/5）

| # | 功能 | 状态 | 说明 |
|---|------|------|------|
| 18 | 键盘快捷键 | ⏳ 待开发 | Ctrl+N 新对话、Ctrl+K 聚焦搜索、Esc 停止生成 |
| 19 | 暗色模式 | ⏳ 待开发 | CSS Variables 已预留，添加 dark theme 切换 |
| 20 | 代码块复制按钮 | ⏳ 待开发 | 代码块右上角添加"复制"按钮 |
| 21 | 消息操作菜单 | ⏳ 待开发 | 复制消息内容、编辑、删除等操作 |
| 22 | 知识图谱 | ⏳ 待开发 | 可视化展示知识域之间的关系 |

---

## API 端点

### 聊天

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/chat/stream` | SSE 流式聊天 |

### 会话

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/sessions` | 列出所有会话 |
| GET | `/api/sessions/{id}` | 获取会话详情（含消息历史） |
| DELETE | `/api/sessions/{id}` | 删除会话 |

### 知识库

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/knowledge/domains` | 列出所有知识域 |
| GET | `/api/knowledge/domains/{domain}` | 获取知识域内容 |
| GET | `/api/knowledge/search?q=&topK=` | 向量搜索知识条目 |
| GET | `/api/knowledge/domains/{domain}/entries` | 列出知识域下的 Q&A 条目 |
| PUT | `/api/knowledge/domains/{domain}/entries/{entryId}` | 编辑知识条目 |
| DELETE | `/api/knowledge/domains/{domain}/entries/{entryId}` | 删除知识条目 |
| DELETE | `/api/knowledge/domains/{domain}` | 删除整个知识域 |
| POST | `/api/knowledge/domains/{domain}/reindex` | 重建域的向量索引（维护工具） |
| GET | `/api/knowledge/export` | 导出全部知识域为 zip |
| POST | `/api/knowledge/import?domain=` | 快速导入：按标题拆分 .md 文件到指定知识域 |
| POST | `/api/knowledge/smart-import?domain=` | 智能导入：LLM 提炼 Q&A 后写入知识域 |

### 其他

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/providers` | 获取可用 LLM 供应商列表 |

---

## 前端组件

```
App
├── Sidebar
│   ├── 会话列表 (新建/切换/删除)
│   ├── 知识域搜索框
│   └── 知识域列表 (查看/删除)
└── ChatContainer
    ├── ChatMessages → MarkdownViewer (react-markdown + remark-gfm)
    │   └── 重新生成按钮 (最后一条 AI 消息下方)
    ├── KnowledgeViewer (知识域查看 + 条目编辑/删除)
    └── ChatInput (textarea + 停止生成按钮 + 联网搜索 + 模型选择)
```

---

## 数据存储

| 路径 | 说明 |
|------|------|
| `data/knowledge/{domain}.md` | 按知识域组织的 Markdown 文件，Q&A 增量追加 |
| `data/sessions.json` | 会话和消息历史 |
| `data/chroma/` | Chroma 向量数据库持久化目录 |

### 知识域 Markdown 格式

```markdown
# 知识域: 前端开发

## Q: React 的 useEffect 如何使用？

**日期**: 2026-07-31 10:30 | **来源**: [React 文档](https://react.dev)

useEffect 是 React 的 Hook，用于在组件渲染后执行副作用...

---

## Q: ...

...
```

---

## 技术债与待修复问题（ROADMAP 外）

### ✅ 本轮已修复（2026-08-03）

| # | 问题 | 修复方案 | 改动文件 |
|---|------|----------|----------|
| T1 | Chroma 向量索引脏数据 | 使用 `FilterExpressionBuilder` 按 `domain + question` 元数据精确删除向量；新增 `reindexDomain()` 方法重建整个域索引 | `KnowledgeService.java`, `KnowledgeController.java` |
| T5 | store.js SSE 逻辑重复 | 提取 `consumeStream()` + `saveAssistantMessage()` 共享函数；store.js 从 701 行减至 543 行（-22%） | `store.js` |

### ⏳ 待修复

| # | 问题 | 严重度 | 建议方案 |
|---|------|--------|----------|
| T2 | ChatMemory 纯内存且无界 | 🔴 高 | 配置 `MessageWindowChatMemory` 窗口大小（建议 20 轮）；考虑持久化到 sessions.json |
| T3 | sessions.json 无限增长 | 🟡 中 | 添加归档机制或定期清理；`GET /api/sessions` 增加分页 |
| T4 | 前端流式错误静默 | 🟡 中 | store.js 中增加 `error` 状态 + UI 展示错误提示 + 重试按钮 |
| T6 | application.yml 硬编码真实密钥 | 🟡 中 | 移除默认值占位符，改为仅依赖 `.env` 环境变量 |
| T7 | 无输入校验/认证/限流 | 🟢 低 | 本地应用暂可接受；若暴露到网络需加认证 |

---

## 已知问题与解决方案（已解决）

| 问题 | 原因 | 解决方案 |
|------|------|----------|
| 流式输出中 `#` 符号泄漏 | streamdown 无法完美处理不完整的 Markdown heading | 使用 react-markdown + 流式标记自动修复 |
| 搜索接口 500 错误 | Ollama 阻塞调用在 WebFlux 事件线程执行 | `subscribeOn(Schedulers.boundedElastic())` |

---

## 本轮重构记录（2026-08-03）

### 修复的问题

1. **ROADMAP.md 与实际状态同步** — P2 中会话导出、消息编辑、删除单条消息实际已完成，修正状态标注
2. **Chroma 向量索引脏数据（T1）** — 编辑/删除知识条目后旧向量不再残留
   - `KnowledgeService.removeFromIndex()` 使用 `FilterExpressionBuilder` 按元数据精确删除
   - 新增 `KnowledgeService.reindexDomain()` 重建整个域索引
   - 新增 `POST /api/knowledge/domains/{domain}/reindex` 维护端点
3. **store.js SSE 逻辑重复（T5）** — 提取共享函数，代码量 -22%
   - 新增 `consumeStream(response, set, get)` — 统一 SSE 读取循环
   - 新增 `saveAssistantMessage(set, get, content, isAborted)` — 统一保存 AI 回复
   - `sendMessage` / `editMessage` / `regenerate` 各自精简约 60-90 行

### 变更文件

| 文件 | 变更 |
|------|------|
| `docs/ROADMAP.md` | 修正 P2 状态，新增技术债章节 |
| `backend/.../KnowledgeService.java` | `removeFromIndex` 实现真正的向量删除；新增 `reindexDomain` |
| `backend/.../KnowledgeController.java` | 新增 `POST /reindex` 端点 |
| `frontend/src/store.js` | 提取 `consumeStream` + `saveAssistantMessage`；三处 SSE 逻辑复用 |

### 测试

- 后端：31 tests ✅ 通过
- 前端：42 tests ✅ 通过
- 前端构建：✅ 成功

---

## 本轮重构记录（知识库导入/导出）

### 新增功能

1. **知识库导出** — 全部知识域打包为 zip 下载
   - `KnowledgeService.exportAllDomains()` 读取所有域文件
   - `GET /api/knowledge/export` 返回 zip 流（webflux `ByteArrayResource`）
   - 前端 KnowledgeViewer 顶部"导出知识库"按钮，Blob 下载

2. **知识库导入** — 上传 .md 文件追加到指定知识域
   - `KnowledgeService.importEntries()` 解析 `## Q: ...` 格式并逐条 `appendEntry`
   - `POST /api/knowledge/import?domain=xxx` 接收 webflux `FilePart`
   - 前端 KnowledgeViewer 顶部"导入 .md"按钮，FormData 上传

3. **配置修正** — `spring.servlet.multipart` → `spring.codec.max-in-memory-size`（webflux 兼容）

### 变更文件

| 文件 | 变更 |
|------|------|
| `backend/.../KnowledgeService.java` | 新增 `exportAllDomains()` + `importEntries()` + `parseCitations()` |
| `backend/.../KnowledgeController.java` | 新增 `GET /export` + `POST /import` 端点 |
| `backend/src/main/resources/application.yml` | 修正 multipart 配置为 webflux 版本 |
| `frontend/src/store.js` | 新增 `exportKnowledgeBase` + `importKnowledge` actions |
| `frontend/src/components/KnowledgeViewer.jsx` | 新增导出/导入按钮 + 文件上传 |
| `frontend/src/styles/app.css` | 新增 `.btn-import` + `.knowledge-actions` 样式 |
| `backend/.../KnowledgeServiceTest.java` | 新增 5 个测试用例 |

### 测试

- 后端：36 tests ✅ 通过（+5）
- 前端：42 tests ✅ 通过
- 前端构建：✅ 成功

---

## 近期提交记录

| 提交 | 说明 |
|------|------|
| `d55e43c` | feat(chat): 支持编辑消息 |
| `a97cc24` | feat(chat): 支持删除单条消息 |
| `9442bea` | feat(export): 会话导出为 Markdown 文件 |
| `6efd2a3` | feat(knowledge): 实现「通用知识」重新分类为细粒度知识域 |
| `ea171cb` | fix(rag): 重构联网搜索与知识库检索的路由逻辑 |
| `03a3124` | feat(P1): 实现停止生成、重新生成、知识库搜索、知识条目管理 |
| `bccf398` | feat(streaming): 使用 react-markdown 重写流式渲染 |
