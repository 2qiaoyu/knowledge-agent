# 个人知识库 Agent — 需求规划

> 最后更新: 2026-07-31

## 项目概述

个人知识库 Agent — 智能问答系统，支持联网搜索，对话内容自动整理为结构化 Markdown 知识库。

技术栈：React 18 + Vite + Zustand | Java 21 + Spring Boot 4.1 + Spring AI 2.0 + WebFlux | Chroma 向量库 | Ollama 嵌入 | DeepSeek LLM

---

## 功能清单

### P0 — 核心功能（已完成）

| # | 功能 | 状态 | 说明 |
|---|------|------|------|
| 1 | 流式对话 (SSE) | ✅ 已完成 | SSE 流式输出，react-markdown 渐进式渲染 |
| 2 | 多轮记忆 | ✅ 已完成 | MessageChatMemoryAdvisor，按 sessionId 隔离 |
| 3 | 知识库 RAG 检索 | ✅ 已完成 | Chroma 向量相似度检索，topK=3 |
| 4 | 联网搜索 | ✅ 已完成 | Serper.dev Google Search API，可开关 |
| 5 | 多 LLM 供应商 | ✅ 已完成 | DeepSeek + LongCat，可手动切换 |
| 6 | 知识域自动分类 | ✅ 已完成 | LLM 智能分类到知识域或创建新域 |
| 7 | 编号引用格式 | ✅ 已完成 | 正文 [1][2] 引用 + 末尾参考来源列表 |
| 8 | 流式 Markdown 渲染 | ✅ 已完成 | 自动修复未闭合标记，渐进式渲染标题/代码块/粗体 |
| 9 | 测试覆盖 | ✅ 已完成 | 后端 31 cases + 前端 42 cases |

### P1 — 高优先级（已完成）

| # | 功能 | 状态 | 说明 |
|---|------|------|------|
| 10 | 停止生成 | ✅ 已完成 | AbortController 中断 fetch，WebFlux 自动取消 Flux |
| 11 | 重新生成 | ✅ 已完成 | 移除末条 AI 消息，用最后一条用户问题重发 |
| 12 | 知识库搜索 | ✅ 已完成 | Sidebar 搜索框，向量检索跨域搜索，结果带预览 |
| 13 | 知识条目管理 | ✅ 已完成 | 查看/编辑/删除单条 Q&A 条目，内联编辑 |

### P2 — 中优先级（待开发）

| # | 功能 | 状态 | 说明 |
|---|------|------|------|
| 14 | 会话导出 | ⏳ 待开发 | 将对话导出为 Markdown 文件 |
| 15 | 知识库导入/导出 | ⏳ 待开发 | 导出知识域为 Markdown 包 / 导入文件到知识库 |
| 16 | 消息编辑 | ⏳ 待开发 | 修改已发送的用户消息，后续 AI 回复联动更新 |
| 17 | 删除单条消息 | ⏳ 待开发 | 删除对话中的单条用户/AI 消息 |

### P3 — 体验优化（待开发）

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

## 已知问题与解决方案

| 问题 | 原因 | 解决方案 |
|------|------|----------|
| 流式输出中 `#` 符号泄漏 | streamdown 无法完美处理不完整的 Markdown heading | 使用 react-markdown + 流式标记自动修复 |
| 搜索接口 500 错误 | Ollama 阻塞调用在 WebFlux 事件线程执行 | `subscribeOn(Schedulers.boundedElastic())` |

---

## 近期提交记录

| 提交 | 说明 |
|------|------|
| `bccf398` | feat(streaming): 使用 react-markdown 重写流式渲染 |
| `ef7e0e0` | fix(streaming): 流式期间使用纯文本渲染 |
| `10ced95` | fix(streaming): 缓冲最后一行 |
| `78bae34` | fix(streaming): 转义所有 heading 标记 |
| `369058f` | fix(streaming): 转义不完整的 heading 标记 |
