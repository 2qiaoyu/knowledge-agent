import { create } from 'zustand';
import { parseSSEBuffer } from './sse';

/**
 * Consume an SSE stream and update streaming state.
 * Handles [SESSION_ID:xxx] and [DONE] control messages internally.
 *
 * @param {Response} response - fetch Response with SSE body
 * @param {Function} set - Zustand set
 * @param {Function} get - Zustand get
 * @returns {Promise<{ content: string, aborted: boolean }>} full content + whether user aborted
 */
async function consumeStream(response, set, get) {
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let fullContent = '';
  let streamEnded = false;
  let debounceTimer = null;
  const DEBOUNCE_MS = 40;

  const flushStreaming = () => {
    if (debounceTimer) {
      clearTimeout(debounceTimer);
      debounceTimer = null;
    }
    set({ streamingContent: fullContent });
  };

  const scheduleStreamingFlush = () => {
    if (debounceTimer) return;
    debounceTimer = setTimeout(flushStreaming, DEBOUNCE_MS);
  };

  const handleEvent = (data) => {
    if (data === '[DONE]') {
      streamEnded = true;
      flushStreaming();
      return;
    }
    if (data.startsWith('[SESSION_ID:')) {
      const sid = data.slice(13, -1);
      set({ currentSessionId: sid });
      get().fetchSessions();
      return;
    }
    fullContent += data;
    scheduleStreamingFlush();
  };

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });
      const { events, remainder } = parseSSEBuffer(buffer);
      buffer = remainder;

      for (const data of events) {
        if (!data) continue;
        handleEvent(data);
        if (streamEnded) break;
      }
      if (streamEnded) break;
    }

    if (!streamEnded && buffer.trim()) {
      const { events } = parseSSEBuffer(`${buffer}\n\n`);
      for (const data of events) {
        if (!data) continue;
        handleEvent(data);
        if (streamEnded) break;
      }
    }

    flushStreaming();
    return { content: fullContent, aborted: false, error: null };
  } catch (e) {
    if (e.name === 'AbortError') {
      flushStreaming();
      return { content: fullContent, aborted: true, error: null };
    }
    flushStreaming();
    return { content: fullContent, aborted: false, error: e.message || '网络连接失败' };
  }
}

/**
 * Save partial or full streamed content as an assistant message.
 * Shared by sendMessage, editMessage, and regenerate.
 */
function saveAssistantMessage(set, get, content, isAborted = false) {
  if (!content || !content.trim()) return false;
  const finalContent = isAborted ? content + '\n\n*(已停止生成)*' : content;
  const assistantMsg = {
    id: (Date.now() + 1).toString(),
    role: 'assistant',
    content: finalContent,
    timestamp: new Date().toISOString(),
  };
  set((s) => ({
    messages: [...s.messages, assistantMsg],
    streamingContent: '',
    streaming: false,
    abortController: null,
  }));
  get().fetchSessions();
  return true;
}

const useStore = create((set, get) => ({
  // Sessions
  sessions: [],
  archivedSessions: [],
  currentSessionId: null,

  // Chat
  messages: [],
  streaming: false,
  streamingContent: '',
  abortController: null,
  chatError: null, // { message: string, retryable: boolean, retry: Function }

  // Settings
  enableWebSearch: false,
  llmProvider: 'deepseek',  // 'deepseek' | 'longcat'
  availableProviders: [],
  defaultProvider: 'deepseek',

  // Knowledge domains
  domains: [],
  selectedDomain: null,
  domainContent: '',
  searchQuery: '',
  searchResults: [],
  entries: [],

  // UI
  sidebarTab: 'sessions', // 'sessions' | 'knowledge'
  darkMode: false,

  // Knowledge recommendations
  recommendations: [], // [{ domain, question, answer }]

  // Knowledge graph
  graphData: null, // { nodes: [], edges: [] }
  showGraph: false,
  graphLoading: false,

  // Actions - Error handling
  clearChatError: () => set({ chatError: null }),

  // Actions - Recommendations
  setRecommendations: (recommendations) => set({ recommendations }),
  clearRecommendations: () => set({ recommendations: [] }),

  // Actions - Theme
  toggleDarkMode: () => set((s) => ({ darkMode: !s.darkMode })),
  setDarkMode: (dark) => set({ darkMode: dark }),

  // Actions - Knowledge recommendations
  fetchRecommendations: async (query) => {
    try {
      const res = await fetch(`/api/knowledge/recommend?q=${encodeURIComponent(query)}&limit=3`);
      if (!res.ok) return;
      const data = await res.json();
      set({ recommendations: data });
    } catch (e) {
      console.error('Failed to fetch recommendations', e);
    }
  },

  // Actions - Sessions
  setSessions: (sessions) => set({ sessions }),
  setCurrentSessionId: (id) => set({ currentSessionId: id }),
  addSession: (session) => set((s) => ({
    sessions: [session, ...s.sessions.filter((x) => x.id !== session.id)],
  })),

  loadSession: async (id) => {
    try {
      const res = await fetch(`/api/sessions/${id}`);
      const session = await res.json();
      set({ currentSessionId: id, messages: session.messages || [], selectedDomain: null, domainContent: '' });
    } catch (e) {
      console.error('Failed to load session', e);
    }
  },

  fetchSessions: async () => {
    try {
      // Use summary endpoint to avoid loading full message history
      const res = await fetch('/api/sessions?summary=true');
      const sessions = await res.json();
      set({ sessions });
    } catch (e) {
      console.error('Failed to fetch sessions', e);
    }
  },

  fetchArchivedSessions: async () => {
    try {
      const res = await fetch('/api/sessions/archived');
      const archivedSessions = await res.json();
      set({ archivedSessions });
    } catch (e) {
      console.error('Failed to fetch archived sessions', e);
    }
  },

  archiveSession: async (id) => {
    try {
      await fetch(`/api/sessions/${id}/archive`, { method: 'POST' });
      set((s) => {
        const session = s.sessions.find((x) => x.id === id);
        const archivedSession = session ? { ...session, archived: true } : { id, title: '未知对话', archived: true };
        return {
          sessions: s.sessions.filter((x) => x.id !== id),
          archivedSessions: [archivedSession, ...(s.archivedSessions || [])],
          currentSessionId: s.currentSessionId === id ? null : s.currentSessionId,
        };
      });
    } catch (e) {
      console.error('Failed to archive session', e);
    }
  },

  unarchiveSession: async (id) => {
    try {
      // Get full session data from backend
      const res = await fetch(`/api/sessions/${id}`);
      const session = await res.json();
      set((s) => ({
        archivedSessions: (s.archivedSessions || []).filter((x) => x.id !== id),
        sessions: [session, ...s.sessions],
      }));
    } catch (e) {
      console.error('Failed to unarchive session', e);
    }
  },

  newChat: () => {
    set({
      currentSessionId: null,
      messages: [],
      streamingContent: '',
      selectedDomain: null,
      domainContent: '',
    });
  },

  deleteSession: async (id) => {
    try {
      await fetch(`/api/sessions/${id}`, { method: 'DELETE' });
      set((s) => ({
        sessions: s.sessions.filter((x) => x.id !== id),
        currentSessionId: s.currentSessionId === id ? null : s.currentSessionId,
        messages: s.currentSessionId === id ? [] : s.messages,
      }));
    } catch (e) {
      console.error('Failed to delete session', e);
    }
  },

  // Actions - Chat
  sendMessage: async (content) => {
    const { currentSessionId, enableWebSearch, llmProvider } = get();
    const controller = new AbortController();
    set({ streaming: true, streamingContent: '', abortController: controller, chatError: null });

    const userMsg = { id: Date.now().toString(), role: 'user', content, timestamp: new Date().toISOString() };
    set((s) => ({ messages: [...s.messages, userMsg] }));

    try {
      const response = await fetch('/api/chat/stream', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          sessionId: currentSessionId,
          message: content,
          enableWebSearch,
          provider: llmProvider,
        }),
        signal: controller.signal,
      });

      // Check for HTTP errors before consuming stream
      if (!response.ok) {
        const errorMsg = response.status === 429
          ? '请求过于频繁，请稍后再试'
          : response.status >= 500
            ? '服务器繁忙，请稍后再试'
            : `请求失败 (${response.status})`;
        set({
          streaming: false,
          abortController: null,
          chatError: { message: errorMsg, retryable: true, retry: () => get().sendMessage(content) },
        });
        return;
      }

      const { content: fullContent, aborted, error } = await consumeStream(response, set, get);

      // Stream-level error (network drop mid-stream, etc.)
      if (error) {
        // Save partial content as a failed message, preserving what we got
        if (fullContent && fullContent.trim()) {
          saveAssistantMessage(set, get, fullContent + '\n\n*(生成中断)*', false);
        }
        set({
          streaming: false,
          abortController: null,
          chatError: { message: error, retryable: true, retry: () => get().sendMessage(content) },
        });
        return;
      }

      if (aborted) {
        saveAssistantMessage(set, get, fullContent, true);
        return;
      }

      saveAssistantMessage(set, get, fullContent);
      get().fetchDomains();
      // Fetch proactive knowledge recommendations based on the question
      get().fetchRecommendations(content);
    } catch (e) {
      // Network failure (fetch itself failed)
      console.error('Chat error', e);
      set({
        streaming: false,
        abortController: null,
        chatError: {
          message: '网络连接失败，请检查网络后重试',
          retryable: true,
          retry: () => get().sendMessage(content),
        },
      });
    }
  },

  deleteMessage: async (messageId) => {
    const { currentSessionId, messages } = get();
    if (!currentSessionId) {
      // 未同步到后端的会话（新对话尚未发送过消息），仅前端删除
      set({ messages: messages.filter((m) => m.id !== messageId) });
      return;
    }
    try {
      await fetch(`/api/sessions/${currentSessionId}/messages/${messageId}`, {
        method: 'DELETE',
      });
      set({ messages: messages.filter((m) => m.id !== messageId) });
    } catch (e) {
      console.error('Failed to delete message', e);
    }
  },

  // 编辑用户消息：更新内容 + 删除后续所有消息 + 自动重新发送
  editMessage: async (messageId, newContent) => {
    const { currentSessionId, messages, enableWebSearch, llmProvider } = get();
    if (!newContent.trim()) return;

    // 更新本地消息内容
    const updatedMessages = messages.map((m) =>
      m.id === messageId ? { ...m, content: newContent } : m
    );
    // 删除该消息之后的所有消息
    const msgIdx = updatedMessages.findIndex((m) => m.id === messageId);
    const truncatedMessages = updatedMessages.slice(0, msgIdx + 1);
    set({ messages: truncatedMessages });

    if (currentSessionId) {
      try {
        await fetch(`/api/sessions/${currentSessionId}/messages/${messageId}`, {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ content: newContent }),
        });
        await fetch(`/api/sessions/${currentSessionId}/messages/${messageId}/after`, {
          method: 'DELETE',
        });
      } catch (e) {
        console.error('Failed to update message on server', e);
      }
    }

    // 自动重新发送
    const controller = new AbortController();
    set({ streaming: true, streamingContent: '', abortController: controller, chatError: null });

    try {
      const response = await fetch('/api/chat/stream', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          sessionId: currentSessionId,
          message: newContent,
          enableWebSearch,
          provider: llmProvider,
        }),
        signal: controller.signal,
      });

      if (!response.ok) {
        const errorMsg = response.status >= 500 ? '服务器繁忙，请稍后再试' : `请求失败 (${response.status})`;
        set({ streaming: false, abortController: null, chatError: { message: errorMsg, retryable: true } });
        return;
      }

      const { content: fullContent, aborted, error } = await consumeStream(response, set, get);

      if (error) {
        if (fullContent && fullContent.trim()) {
          saveAssistantMessage(set, get, fullContent + '\n\n*(生成中断)*', false);
        }
        set({ streaming: false, abortController: null, chatError: { message: error, retryable: true } });
        return;
      }

      if (aborted) {
        saveAssistantMessage(set, get, fullContent, true);
        return;
      }

      saveAssistantMessage(set, get, fullContent);
      get().fetchDomains();
    } catch (e) {
      console.error('Edit resend error', e);
      set({ streaming: false, abortController: null, chatError: { message: '网络连接失败，请检查网络后重试', retryable: true } });
    }
  },

  // 编辑 AI 消息：仅更新内容
  editAssistantMessage: async (messageId, newContent) => {
    const { currentSessionId, messages } = get();
    set({
      messages: messages.map((m) =>
        m.id === messageId ? { ...m, content: newContent } : m
      ),
    });
    if (currentSessionId) {
      try {
        await fetch(`/api/sessions/${currentSessionId}/messages/${messageId}`, {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ content: newContent }),
        });
      } catch (e) {
        console.error('Failed to update assistant message', e);
      }
    }
  },

  stopGeneration: () => {
    const { abortController } = get();
    if (abortController) {
      abortController.abort();
      set({ streaming: false, abortController: null });
    }
  },

  regenerate: async () => {
    const { messages, currentSessionId, enableWebSearch, llmProvider, streaming } = get();
    if (streaming) return;

    // 找到最后一条 assistant 和 user 消息
    let lastAssistantIdx = -1;
    let lastUserIdx = -1;
    for (let i = messages.length - 1; i >= 0; i--) {
      if (lastAssistantIdx === -1 && messages[i].role === 'assistant') lastAssistantIdx = i;
      if (lastUserIdx === -1 && messages[i].role === 'user') lastUserIdx = i;
      if (lastAssistantIdx !== -1 && lastUserIdx !== -1) break;
    }

    if (lastAssistantIdx === -1 || lastUserIdx === -1 || lastUserIdx >= lastAssistantIdx) return;

    const userMessage = messages[lastUserIdx].content;

    // 移除最后一条 assistant 消息
    set((s) => ({
      messages: s.messages.slice(0, lastAssistantIdx),
      streaming: true,
      streamingContent: '',
      chatError: null,
    }));

    // 重新发送
    const controller = new AbortController();
    set({ abortController: controller });

    try {
      const response = await fetch('/api/chat/stream', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          sessionId: currentSessionId,
          message: userMessage,
          enableWebSearch,
          provider: llmProvider,
        }),
        signal: controller.signal,
      });

      if (!response.ok) {
        const errorMsg = response.status >= 500 ? '服务器繁忙，请稍后再试' : `请求失败 (${response.status})`;
        set({ streaming: false, abortController: null, chatError: { message: errorMsg, retryable: true } });
        return;
      }

      const { content: fullContent, aborted, error } = await consumeStream(response, set, get);

      if (error) {
        if (fullContent && fullContent.trim()) {
          saveAssistantMessage(set, get, fullContent + '\n\n*(生成中断)*', false);
        }
        set({ streaming: false, abortController: null, chatError: { message: error, retryable: true } });
        return;
      }

      if (aborted) {
        saveAssistantMessage(set, get, fullContent, true);
        return;
      }

      saveAssistantMessage(set, get, fullContent);
      get().fetchDomains();
    } catch (e) {
      console.error('Regenerate error', e);
      set({ streaming: false, abortController: null, chatError: { message: '网络连接失败，请检查网络后重试', retryable: true } });
    }
  },

  // Actions - Settings
  toggleWebSearch: () => set((s) => ({ enableWebSearch: !s.enableWebSearch })),
  setLlmProvider: (provider) => set({ llmProvider: provider }),

  fetchProviders: async () => {
    try {
      const res = await fetch('/api/providers');
      const data = await res.json();
      set({
        availableProviders: data.available || [],
        defaultProvider: data.default || 'deepseek',
        llmProvider: data.default || 'deepseek',
      });
    } catch (e) {
      console.error('Failed to fetch providers', e);
    }
  },

  // Actions - Knowledge
  fetchDomains: async () => {
    try {
      const res = await fetch('/api/knowledge/domains');
      const domains = await res.json();
      set({ domains });
    } catch (e) {
      console.error('Failed to fetch domains', e);
    }
  },

  fetchDomainContent: async (domain) => {
    try {
      const res = await fetch(`/api/knowledge/domains/${encodeURIComponent(domain)}`);
      const data = await res.json();
      set({ selectedDomain: domain, domainContent: data.content, sidebarTab: 'knowledge' });
    } catch (e) {
      console.error('Failed to fetch domain content', e);
    }
  },

  deleteDomain: async (domain) => {
    try {
      await fetch(`/api/knowledge/domains/${encodeURIComponent(domain)}`, { method: 'DELETE' });
      set((s) => ({
        domains: s.domains.filter((d) => d !== domain),
        selectedDomain: s.selectedDomain === domain ? null : s.selectedDomain,
        domainContent: s.selectedDomain === domain ? '' : s.domainContent,
      }));
    } catch (e) {
      console.error('Failed to delete domain', e);
    }
  },

  searchKnowledge: async (query) => {
    if (!query || !query.trim()) {
      set({ searchQuery: '', searchResults: [] });
      return;
    }
    try {
      const res = await fetch(`/api/knowledge/search?q=${encodeURIComponent(query)}&topK=8`);
      const results = await res.json();
      set({ searchQuery: query, searchResults: results });
    } catch (e) {
      console.error('Search failed', e);
      set({ searchResults: [] });
    }
  },

  clearSearch: () => set({ searchQuery: '', searchResults: [] }),

  fetchEntries: async (domain) => {
    try {
      const res = await fetch(`/api/knowledge/domains/${encodeURIComponent(domain)}/entries`);
      const entries = await res.json();
      set({ entries });
    } catch (e) {
      console.error('Failed to fetch entries', e);
      set({ entries: [] });
    }
  },

  updateEntry: async (domain, entryId, question, answer) => {
    try {
      await fetch(`/api/knowledge/domains/${encodeURIComponent(domain)}/entries/${entryId}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ question, answer }),
      });
    } catch (e) {
      console.error('Failed to update entry', e);
    }
  },

  deleteEntry: async (domain, entryId) => {
    try {
      await fetch(`/api/knowledge/domains/${encodeURIComponent(domain)}/entries/${entryId}`, {
        method: 'DELETE',
      });
    } catch (e) {
      console.error('Failed to delete entry', e);
    }
  },

  clearDomainView: () => set({ selectedDomain: null, domainContent: '', entries: [] }),
  setSidebarTab: (tab) => set({ sidebarTab: tab, selectedDomain: null, domainContent: '' }),

  // Actions - Knowledge Graph
  fetchGraphData: async () => {
    set({ graphLoading: true });
    try {
      const res = await fetch('/api/knowledge/graph');
      if (!res.ok) throw new Error('获取图谱失败');
      const data = await res.json();
      set({ graphData: data });
    } catch (e) {
      console.error('Failed to fetch knowledge graph', e);
    } finally {
      set({ graphLoading: false });
    }
  },

  rebuildGraph: async () => {
    set({ graphLoading: true });
    try {
      const res = await fetch('/api/knowledge/graph/rebuild', { method: 'POST' });
      if (!res.ok) throw new Error('重建图谱失败');
      const data = await res.json();
      set({ graphData: data });
    } catch (e) {
      console.error('Failed to rebuild knowledge graph', e);
    } finally {
      set({ graphLoading: false });
    }
  },

  setShowGraph: (v) => set({ showGraph: v }),

  // Actions - Domain Maintenance (拆分/去重)
  suggestSplit: async (domain) => {
    const res = await fetch(`/api/knowledge/domains/${encodeURIComponent(domain)}/suggest-split`, {
      method: 'POST',
    });
    if (!res.ok) throw new Error('获取拆分建议失败');
    return res.json();
  },

  suggestMerge: async (domain) => {
    const res = await fetch(`/api/knowledge/domains/${encodeURIComponent(domain)}/suggest-merge`, {
      method: 'POST',
    });
    if (!res.ok) throw new Error('获取去重建议失败');
    return res.json();
  },

  executeSplit: async (domain, groups) => {
    const res = await fetch('/api/knowledge/domains/execute-split', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ domain, groups }),
    });
    if (!res.ok) throw new Error('执行拆分失败');
    const result = await res.json();
    // 刷新域列表
    get().fetchDomains();
    return result;
  },

  // Actions - Domain Rename
  renameDomain: async (oldName, newName) => {
    const res = await fetch(`/api/knowledge/domains/${encodeURIComponent(oldName)}/rename`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ newName }),
    });
    if (!res.ok) {
      const err = await res.json().catch(() => ({}));
      throw new Error(err.message || '重命名失败');
    }
    // 刷新域列表
    get().fetchDomains();
    // 如果当前正在查看该域，切换到新名称
    const state = get();
    if (state.selectedDomain === oldName) {
      set({ selectedDomain: newName, entries: [] });
      get().fetchEntries(newName);
    }
    return res.json();
  },

  // Actions - Knowledge Import/Export
  exportKnowledgeBase: async () => {
    try {
      const res = await fetch('/api/knowledge/export');
      if (!res.ok) throw new Error('导出失败');
      const blob = await res.blob();
      const date = new Date().toISOString().slice(0, 10);
      // Trigger download
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `knowledge-export-${date}.zip`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
    } catch (e) {
      console.error('Failed to export knowledge base', e);
      throw e;
    }
  },

  importKnowledge: async (domain, file) => {
    const formData = new FormData();
    formData.append('file', file);
    const res = await fetch(`/api/knowledge/import?domain=${encodeURIComponent(domain)}`, {
      method: 'POST',
      body: formData,
    });
    if (!res.ok) {
      const err = await res.text();
      throw new Error(err || '导入失败');
    }
    return res.json();
  },

  smartImportKnowledge: async (file) => {
    const formData = new FormData();
    formData.append('file', file);
    // No domain param — backend auto-classifies based on content
    const res = await fetch('/api/knowledge/smart-import', {
      method: 'POST',
      body: formData,
    });
    if (!res.ok) {
      const err = await res.text();
      throw new Error(err || '智能导入失败');
    }
    return res.json();
  },

  // Actions - URL Import
  fetchUrlContent: async (url) => {
    const res = await fetch('/api/knowledge/import-url/fetch', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ url }),
    });
    if (!res.ok) {
      const err = await res.text();
      throw new Error(err || '抓取失败');
    }
    return res.json();
  },

  importFromUrl: async (url, title, text, provider) => {
    const res = await fetch('/api/knowledge/import-url/import', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ url, title, text, provider }),
    });
    if (!res.ok) {
      const err = await res.text();
      throw new Error(err || '导入失败');
    }
    return res.json();
  },

  // Actions - Export
  exportSession: () => {
    const { messages, currentSessionId, sessions } = get();
    if (messages.length === 0) return;

    const session = sessions.find((s) => s.id === currentSessionId);
    const title = session?.title || '未命名对话';
    const date = new Date().toISOString().slice(0, 10);

    let md = `# ${title}\n\n`;
    md += `> 导出时间: ${new Date().toLocaleString('zh-CN')}  \n`;
    md += `> 消息数: ${messages.length}\n\n---\n\n`;

    messages.forEach((msg) => {
      const role = msg.role === 'user' ? '用户' : 'AI';
      md += `## ${role}\n\n`;
      md += `${msg.content}\n\n`;
      if (msg.timestamp) {
        md += `*${new Date(msg.timestamp).toLocaleString('zh-CN')}*\n\n`;
      }
      md += '---\n\n';
    });

    // Trigger download
    const blob = new Blob([md], { type: 'text/markdown;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `${title}_${date}.md`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  },
}));

export default useStore;
