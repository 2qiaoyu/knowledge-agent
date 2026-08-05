# 个人知识库 Agent — 需求规划

> 最后更新: 2026-08-05 (v5 — 知识维护工具完成，Phase 2 部分完成)

## 项目概述

个人知识库 Agent — 智能问答系统，支持联网搜索，对话内容自动整理为结构化 Markdown 知识库。

技术栈：React 18 + Vite + Zustand + Tailwind CSS 4 | Java 21 + Spring Boot 4.1 + Spring AI 2.0 + WebFlux | Chroma 向量库 | Ollama 嵌入 | LongCat / DeepSeek LLM

---

## 功能清单

### P0 — 核心功能（已完成 9/9）

| # | 功能 | 状态 | 说明 |
|---|------|------|------|
| 1 | 流式对话 (SSE) | ✅ 已完成 | SSE 流式输出，react-markdown 渐进式渲染 |
| 2 | 多轮记忆 | ✅ 已完成 | MessageChatMemoryAdvisor，按 sessionId 隔离，窗口 20 轮 |
| 3 | 知识库 RAG 检索 | ✅ 已完成 | Chroma 向量相似度检索，topK=3，阈值 0.6 |
| 4 | 联网搜索 | ✅ 已完成 | Serper.dev Google Search API，可开关 |
| 5 | 多 LLM 供应商 | ✅ 已完成 | LongCat（默认）+ DeepSeek，可手动切换 |
| 6 | 知识域自动分类 | ✅ 已完成 | LLM 智能分类到知识域或创建新域 |
| 7 | 编号引用格式 | ✅ 已完成 | 正文 [1][2] 引用 + 末尾参考来源列表 |
| 8 | 流式 Markdown 渲染 | ✅ 已完成 | 自动修复未闭合标记，渐进式渲染标题/代码块/粗体 |
| 9 | 测试覆盖 | ✅ 已完成 | 后端 39 cases + 前端 42 cases |

### P1 — 高优先级（已完成 4/4）

| # | 功能 | 状态 | 说明 |
|---|------|------|------|
| 10 | 停止生成 | ✅ 已完成 | AbortController 中断 fetch，WebFlux 自动取消 Flux |
| 11 | 重新生成 | ✅ 已完成 | 移除末条 AI 消息，用最后一条用户问题重发 |
| 12 | 知识库搜索 | ✅ 已完成 | Sidebar 搜索框，向量检索跨域搜索，结果带预览 |
| 13 | 知识条目管理 | ✅ 已完成 | 查看/编辑/删除单条 Q&A 条目，内联编辑 |

### P2 — 中优先级（已完成 4/4）

| # | 功能 | 状态 | 说明 |
|---|------|------|------|
| 14 | 会话导出 | ✅ 已完成 | 前端导出会话为 Markdown 文件 |
| 15 | 知识库导入/导出 | ✅ 已完成 | 导出 zip / 快速导入（按标题拆分）/ 智能导入（LLM 提炼 Q&A + 自动分类） |
| 16 | 消息编辑 | ✅ 已完成 | 修改已发送的用户消息，后续 AI 回复联动更新 |
| 17 | 删除单条消息 | ✅ 已完成 | 删除对话中的单条用户/AI 消息 |

### P3 — 体验优化（已完成 5/5）

| # | 功能 | 状态 | 说明 |
|---|------|------|------|
| 18 | 键盘快捷键 | ✅ 已完成 | Ctrl+N 新对话、Ctrl+K 聚焦搜索、Esc 停止生成 |
| 19 | 暗色模式 | ✅ 已完成 | [data-theme="dark"] + CSS 变量，系统偏好检测，localStorage 持久化 |
| 20 | 代码块复制按钮 | ✅ 已完成 | 代码块头部"复制"按钮，点击后变"✓ 已复制" |
| 21 | 消息操作菜单 | ✅ 已完成 | 悬浮 ⋯ 按钮展开下拉菜单：复制/编辑/删除 |
| 22 | 知识图谱 | ✅ 已完成 | LLM 提取概念 + @xyflow/react 交互式可视化 |

### P4 — 知识维护工具（已完成 4/4）

| # | 功能 | 状态 | 说明 |
|---|------|------|------|
| 26 | 知识域重命名 | ✅ 已完成 | 重命名知识域，同步更新 Markdown 文件和向量索引 |
| 27 | 知识域整理与拆分 | ✅ 已完成 | LLM 分析域内容，将混杂域拆分为多个精确域 |
| 28 | 知识条目优化 | ✅ 已完成 | LLM 优化单条 Q&A，保留 Markdown 格式和参考来源 |
| 29 | 网页 URL 导入 | ✅ 已完成 | 输入 URL 自动抓取网页内容，LLM 提炼 Q&A 并分类入库 |

---

## 智能化功能（Phase 1）

| # | 功能 | 状态 | 说明 |
|---|------|------|------|
| 23 | 主动知识推荐 | ✅ 已完成 | 问答结束后自动推荐相关知识条目，点击跳转 |
| 24 | 智能问答增强 | ✅ 已完成 | LLM 融合多条知识形成结构化回答，标注知识缺口 |
| 25 | 会话归档 | ✅ 已完成 | 活跃/归档分离，防止 sessions.json 无限增长 |

## Phase 2 — 知识自动化（已完成 1/4）

| # | 功能 | 状态 | 说明 |
|---|------|------|------|
| 30 | 多源导入 — URL 网页抓取 | ✅ 已完成 | 输入 URL 抓取内容，LLM 提炼 Q&A 并自动分类 |
| 31 | 知识自动维护（合并/矛盾检测/过时清理） | ✅ 已完成 | LLM 检测重复+矛盾，日期阈值检测过时，人工确认后执行 |
| 32 | 知识健康报告 | 🟡 待开发 | 知识域统计、重复率、活跃度、知识缺口分析 |
| 33 | 多源导入扩展（书签/剪贴板） | 🟢 待开发 | 浏览器书签导入、剪贴板监控 |

---

## API 端点

### 聊天

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/chat/stream` | SSE 流式聊天 |

### 会话

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/sessions` | 列出活跃会话（?summary=true 返回不含消息的轻量列表） |
| GET | `/api/sessions/{id}` | 获取会话详情（含消息历史） |
| DELETE | `/api/sessions/{id}` | 删除会话 |
| GET | `/api/sessions/archived` | 列出已归档会话 |
| POST | `/api/sessions/{id}/archive` | 归档会话 |
| POST | `/api/sessions/{id}/unarchive` | 取消归档会话 |

### 知识库

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/knowledge/domains` | 列出所有知识域 |
| GET | `/api/knowledge/domains/{domain}` | 获取知识域内容 |
| GET | `/api/knowledge/search?q=&topK=` | 向量搜索知识条目 |
| GET | `/api/knowledge/recommend?q=&limit=` | 推荐相关知识条目 |
| GET | `/api/knowledge/graph` | 获取知识图谱数据（节点 + 边） |
| POST | `/api/knowledge/graph/rebuild` | 重建知识图谱 |
| GET | `/api/knowledge/domains/{domain}/entries` | 列出知识域下的 Q&A 条目 |
| PUT | `/api/knowledge/domains/{domain}/entries/{entryId}` | 编辑知识条目 |
| DELETE | `/api/knowledge/domains/{domain}/entries/{entryId}` | 删除知识条目 |
| DELETE | `/api/knowledge/domains/{domain}` | 删除整个知识域 |
| PUT | `/api/knowledge/domains/{domain}/rename` | 重命名知识域 |
| POST | `/api/knowledge/domains/{domain}/split` | 拆分知识域（LLM 分析并迁移条目） |
| POST | `/api/knowledge/domains/{domain}/entries/{entryId}/optimize` | LLM 优化单条知识条目 |
| POST | `/api/knowledge/domains/{domain}/maintenance/report` | 生成维护报告（重复/矛盾/过时检测） |
| POST | `/api/knowledge/domains/{domain}/maintenance/merge` | 执行合并重复条目 |
| POST | `/api/knowledge/domains/{domain}/maintenance/delete-outdated` | 删除过时条目 |
| POST | `/api/knowledge/domains/{domain}/reindex` | 重建域的向量索引（维护工具） |
| POST | `/api/knowledge/import-url` | 网页 URL 导入：抓取内容 + LLM 提炼 Q&A |
| GET | `/api/knowledge/export` | 导出全部知识域为 zip |
| POST | `/api/knowledge/import?domain=` | 快速导入：按标题拆分 .md 文件 |
| POST | `/api/knowledge/smart-import` | 智能导入：LLM 提炼 Q&A + 自动分类 |

### 其他

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/providers` | 获取可用 LLM 供应商列表 |

---

## 前端组件

```
App
├── Sidebar
│   ├── 会话列表 (新建/切换/删除/归档)
│   ├── 已归档会话（折叠区）
│   ├── 知识域搜索框
│   ├── 知识域列表 (查看/删除)
│   ├── 智能导入 / 导出按钮
│   └── 主题切换按钮
└── ChatContainer
    ├── ChatMessages → MarkdownViewer (react-markdown + remark-gfm)
    │   ├── 消息操作菜单 (复制/编辑/删除)
    │   ├── 重新生成按钮
    │   ├── 知识推荐卡片
    │   └── 错误提示 banner + 重试
    ├── KnowledgeViewer (知识域查看 + 条目编辑/删除)
    ├── KnowledgeGraph (知识图谱可视化, @xyflow/react)
    ├── DomainManager (知识域重命名/拆分)
    ├── MaintenancePanel (知识维护：重复/矛盾/过时检测)
    └── ChatInput (textarea + 停止生成 + 联网搜索 + 模型选择)
```

---

## 数据存储

| 路径 | 说明 |
|------|------|
| `data/knowledge/{domain}.md` | 按知识域组织的 Markdown 文件，Q&A 增量追加 |
| `data/sessions.json` | 会话和消息历史（活跃 + 归档） |
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

## 技术债与待修复问题

### ✅ 已修复

| # | 问题 | 修复方案 | 改动文件 |
|---|------|----------|----------|
| T1 | Chroma 向量索引脏数据 | FilterExpressionBuilder 精确删除 + reindexDomain() | `KnowledgeService.java`, `KnowledgeController.java` |
| T2 | ChatMemory 无界增长 | MessageWindowChatMemory.maxMessages(20) | `ChatService.java` |
| T3 | sessions.json 无限增长 | 会话归档机制（活跃/归档分离） | `SessionService.java`, `ChatController.java`, `Session.java` |
| T4 | 前端流式错误静默 | chatError 状态 + 错误 banner + 重试 + 保留部分内容 | `store.js`, `ChatMessages.jsx`, `app.css` |
| T5 | store.js SSE 逻辑重复 | 提取 consumeStream() + saveAssistantMessage() | `store.js` |
| T8 | SSE 解析器嵌套 data: 前缀 | 修复 parseSSEBuffer 处理 `data: data:` 格式 | `store.js` |
| T9 | SSE 流结束残留 buffer | 流结束时正确处理 buffer 中剩余数据 | `store.js` |
| T10 | 知识条目编辑丢失来源 | updateEntry 时保留 Markdown 格式和参考来源 | `KnowledgeService.java` |
| T11 | 知识域拆分后残留 | 拆分后正确删除原域中已迁移的条目 | `KnowledgeService.java` |

### ⏳ 待修复

| # | 问题 | 严重度 | 建议方案 |
|---|------|--------|----------|
| ~~T6~~ | ~~application.yml 硬编码密钥~~ | ~~🟡 中~~ | 已跳过（yml 已 gitignored，不提交仓库） |
| T7 | 无输入校验/认证/限流 | 🟢 低 | 本地应用暂可接受 |

---

## 近期提交记录

| 提交 | 说明 |
|------|------|
| `4d14df8` | feat: 网页 URL 导入知识 + 修复标题格式和编辑丢失来源 |
| `e7d9ed8` | fix: 优化条目替换后立即刷新 + 复用 SSE 解析器 |
| `19a5507` | fix: 优化条目 prompt 参考 ChatService 结构化格式 |
| `c4296bc` | fix: 优化条目时保留 Markdown 格式和参考来源 |
| `8606d72` | fix: updateEntry/deleteEntry 端点添加 boundedElastic 调度 |
| `8d190af` | fix(ui): 修复 SSE 解析器处理嵌套 data: 前缀 |
| `dade8c0` | fix(ui): SSE 流结束时正确处理 buffer 中剩余数据 |
| `b5c35be` | feat(knowledge): LLM 优化单个知识条目 |
| `25c4116` | fix(ui): 切换知识域时滚动条重置到顶部 |
| `50f04e3` | feat(knowledge): 支持重命名知识域 |
| `50fc54b` | fix(knowledge): 拆分后正确删除原域中已迁移的条目 |
| `aa6afb4` | feat(knowledge): 知识域整理与拆分 |
| `b88abf7` | feat(knowledge): 知识图谱 — LLM 提取概念 + 交互式可视化 |
| `b793ac0` | docs: 全面更新 ROADMAP v3 |
| `8c525b9` | docs: 更新 ROADMAP 标记所有已完成项 |
| `1ef9903` | feat(knowledge): Phase 1 智能化 — 主动推荐 + 问答增强 |
| `bbe6d4c` | perf: 会话列表瘦身 + 保存防抖 |
| `f98d91e` | fix(session): 归档显示标题 + 取消归档刷新列表 |
| `5a2504a` | feat(session): 会话归档机制 |
| `80b3154` | fix(ui): 主题切换按钮移至 Sidebar 标题栏右侧 |
| `04a3432` | feat(ui): 暗色模式支持 |
| `b3e2e87` | feat(ui): 消息操作菜单 |
| `26193cc` | feat(ui): 键盘快捷键支持 |
| `17f79aa` | fix(ui): 流式错误提示 + 重试机制 |
| `f59f139` | feat(ui): 代码块添加复制按钮 |
| `703ed3f` | fix(memory): 限制 ChatMemory 窗口为 20 轮 |
| `9dfd503` | feat(knowledge): 知识库导入/导出 + LLM 智能导入 |

