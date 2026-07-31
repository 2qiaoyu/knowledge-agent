import { create } from 'zustand';
import { parseSSEBuffer } from './sse';

const useStore = create((set, get) => ({
  // Sessions
  sessions: [],
  currentSessionId: null,

  // Chat
  messages: [],
  streaming: false,
  streamingContent: '',
  abortController: null,

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
      const res = await fetch('/api/sessions');
      const sessions = await res.json();
      set({ sessions });
    } catch (e) {
      console.error('Failed to fetch sessions', e);
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
    set({ streaming: true, streamingContent: '', abortController: controller });

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

      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      let fullContent = '';

      let streamEnded = false;
      let debounceTimer = null;
      const DEBOUNCE_MS = 40; // react-markdown 渲染更快，使用更短的 debounce

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
          flushStreaming(); // 刷新全部内容
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

      // Add assistant message
      const assistantMsg = {
        id: (Date.now() + 1).toString(),
        role: 'assistant',
        content: fullContent,
        timestamp: new Date().toISOString(),
      };
      set((s) => ({
        messages: [...s.messages, assistantMsg],
        streamingContent: '',
        streaming: false,
      }));

      // Refresh session list
      get().fetchSessions();
      // Refresh knowledge domains
      get().fetchDomains();
    } catch (e) {
      if (e.name === 'AbortError') {
        // 用户主动停止生成，将已有内容作为完整回复保存
        if (fullContent && fullContent.trim()) {
          const assistantMsg = {
            id: (Date.now() + 1).toString(),
            role: 'assistant',
            content: fullContent + '\n\n*(已停止生成)*',
            timestamp: new Date().toISOString(),
          };
          set((s) => ({
            messages: [...s.messages, assistantMsg],
            streamingContent: '',
            streaming: false,
            abortController: null,
          }));
          get().fetchSessions();
          return;
        }
      }
      console.error('Chat error', e);
      set({ streaming: false, abortController: null });
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
    set({ streaming: true, streamingContent: '', abortController: controller });

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

      const assistantMsg = {
        id: (Date.now() + 1).toString(),
        role: 'assistant',
        content: fullContent,
        timestamp: new Date().toISOString(),
      };
      set((s) => ({
        messages: [...s.messages, assistantMsg],
        streamingContent: '',
        streaming: false,
      }));

      get().fetchSessions();
      get().fetchDomains();
    } catch (e) {
      if (e.name === 'AbortError') {
        if (fullContent && fullContent.trim()) {
          const assistantMsg = {
            id: (Date.now() + 1).toString(),
            role: 'assistant',
            content: fullContent + '\n\n*(已停止生成)*',
            timestamp: new Date().toISOString(),
          };
          set((s) => ({
            messages: [...s.messages, assistantMsg],
            streamingContent: '',
            streaming: false,
            abortController: null,
          }));
          get().fetchSessions();
          return;
        }
      }
      console.error('Edit resend error', e);
      set({ streaming: false, abortController: null });
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

      const assistantMsg = {
        id: (Date.now() + 1).toString(),
        role: 'assistant',
        content: fullContent,
        timestamp: new Date().toISOString(),
      };
      set((s) => ({
        messages: [...s.messages, assistantMsg],
        streamingContent: '',
        streaming: false,
        abortController: null,
      }));

      get().fetchSessions();
      get().fetchDomains();
    } catch (e) {
      if (e.name === 'AbortError') {
        if (fullContent && fullContent.trim()) {
          const assistantMsg = {
            id: (Date.now() + 1).toString(),
            role: 'assistant',
            content: fullContent + '\n\n*(已停止生成)*',
            timestamp: new Date().toISOString(),
          };
          set((s) => ({
            messages: [...s.messages, assistantMsg],
            streamingContent: '',
            streaming: false,
            abortController: null,
          }));
          get().fetchSessions();
          return;
        }
      }
      console.error('Regenerate error', e);
      set({ streaming: false, abortController: null });
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

  reclassifyDomains: async () => {
    try {
      const res = await fetch('/api/knowledge/reclassify', { method: 'POST' });
      const data = await res.json();
      // Refresh domain list after reclassification
      get().fetchDomains();
      return data;
    } catch (e) {
      console.error('Failed to reclassify', e);
      throw e;
    }
  },

  clearDomainView: () => set({ selectedDomain: null, domainContent: '', entries: [] }),
  setSidebarTab: (tab) => set({ sidebarTab: tab, selectedDomain: null, domainContent: '' }),

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
