# 个人知识库 Agent

一个智能问答系统，支持联网搜索，将对话内容自动整理为结构化 Markdown 知识库，支持增量更新和多轮对话。

## 技术栈

| 层 | 技术 |
|---|------|
| 前端 | React 18 + Vite + Zustand |
| 后端 | Java 21 + Spring Boot 4.1 + Spring AI 2.0.0 |
| 对话模型 | DeepSeek Chat (默认) / LongCat (美团, 可切换) |
| 向量嵌入 | Ollama (nomic-embed-text, 本地运行) |
| 向量数据库 | Chroma DB |
| 联网搜索 | Serper.dev (Google Search API, 免费 2500 次/月) |

## 架构

```
┌─────────────────────────────────────────────────────────┐
│                    Frontend (React 18)                    │
│  Chat UI │ Knowledge Browser │ Session Manager           │
└──────────────────────┬──────────────────────────────────┘
                       │ HTTP/SSE (streaming)
┌──────────────────────┴──────────────────────────────────┐
│                Backend (Java 21 + Spring Boot 4.1)        │
│                                                          │
│  ChatController → ChatService                           │
│     ├── WebSearchService   (Serper.dev Google Search)    │
│     ├── ChatClient         (DeepSeek via Spring AI)     │
│     ├── KnowledgeService   (Markdown + Chroma)          │
│     └── SessionService     (会话上下文)                  │
│                                                          │
│  Chroma DB ←→ Ollama Embedding (向量检索)                │
│  Local Filesystem ←→ Markdown 知识文件                   │
└──────────────────────────────────────────────────────────┘
```

## 数据流

```
用户提问 → Chroma 检索历史知识 → [可选] 联网搜索
→ DeepSeek 流式生成回答 → 向量分类知识域
→ 追加到 Markdown 文件 → 索引到 Chroma
```

## 项目结构

```
knowledge-agent/
├── docker-compose.yml              # Chroma 容器
├── .env.example                    # 环境变量模板
├── README.md
├── backend/
│   ├── pom.xml
│   └── src/main/
│       ├── resources/application.yml
│       └── java/com/knowledge/
│           ├── KnowledgeAgentApplication.java
│           ├── config/SpringAiConfig.java
│           ├── model/
│           │   ├── ChatMessage.java
│           │   ├── ChatRequest.java
│           │   └── Session.java
│           ├── controller/
│           │   ├── ChatController.java
│           │   └── KnowledgeController.java
│           └── service/
│               ├── ChatService.java          # 核心编排
│               ├── SessionService.java       # 会话管理
│               ├── KnowledgeService.java     # Markdown + Chroma
│               └── WebSearchService.java     # 搜索集成
└── frontend/
    ├── index.html
    ├── vite.config.js
    └── src/
        ├── main.jsx
        ├── App.jsx
        ├── store.js                         # Zustand 状态
        ├── styles/app.css
        └── components/
            ├── Sidebar.jsx                  # 对话/知识域面板
            ├── ChatContainer.jsx            # 主聊天区
            ├── ChatMessages.jsx
            ├── ChatInput.jsx                # 输入 + 联网开关
            └── MarkdownViewer.jsx           # Markdown 渲染
```

## API

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/chat/stream` | POST | 发送消息，SSE 流式返回（支持 `provider` 参数切换模型） |
| `/api/providers` | GET | 获取可用 LLM 供应商列表 |
| `/api/sessions` | GET | 会话列表 |
| `/api/sessions/{id}` | GET | 会话详情 + 历史消息 |
| `/api/sessions/{id}` | DELETE | 删除会话 |
| `/api/knowledge/domains` | GET | 知识域列表 |
| `/api/knowledge/domains/{name}` | GET | 知识文件内容 |
| `/api/knowledge/domains/{name}` | DELETE | 删除知识域 |

## 快速开始

### 1. 环境准备

- JDK 21+
- Node.js 18+
- Docker (运行 Chroma)
- Ollama (本地向量嵌入)

### 2. 安装 Ollama 并拉取模型

```bash
brew install ollama
ollama serve &
ollama pull nomic-embed-text
```

### 3. 启动 Chroma

```bash
docker-compose up -d
```

### 4. 配置环境变量

```bash
cp .env.example .env
```

编辑 `.env`，必填项：

```bash
DEEPSEEK_API_KEY=sk-your-deepseek-api-key   # DeepSeek API Key
```

联网搜索使用 Serper.dev（Google Search API），需在 `.env` 中配置 `SERPER_API_KEY`。注册获取 Key：https://serper.dev（免费额度 2500 次/月）。

### 5. 启动后端

```bash
cd backend
./mvnw spring-boot:run
```

后端运行在 `http://localhost:8080`

### 6. 启动前端

```bash
cd frontend
npm install
npm run dev
```

前端运行在 `http://localhost:3000`，API 请求自动代理到后端。

## Markdown 知识文件格式

每个知识域对应一个 `.md` 文件，存储在 `data/knowledge/`：

```markdown
# 知识域: 机器学习

## Q: 什么是梯度下降？
**日期**: 2026-06-02 | **来源**: [文章A](url), [文章B](url)

详细回答内容...

---

## Q: 反向传播的原理？
**日期**: 2026-06-02

回答内容...
```

## 核心特性

- **多轮对话**：会话级别上下文记忆，支持历史会话切换
- **多模型切换**：支持 DeepSeek 和 LongCat，可手动切换
- **联网搜索**：一键切换，搜索结果带引用来源
- **自动分类**：通过向量相似度将回答归入对应知识域
- **增量更新**：同一知识域的新内容追加到已有文件
- **流式响应**：SSE 实时显示 LLM 生成内容

## 多模型配置

在 `.env` 中添加 `LONGCAT_API_KEY` 即可启用 LongCat 作为备用模型：

```bash
# .env
DEEPSEEK_API_KEY=sk-your-deepseek-key
LONGCAT_API_KEY=your-longcat-key  # 可选
```

前端输入框下方会自动显示模型选择器（当多个模型可用时）。
