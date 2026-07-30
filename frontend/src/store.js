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

  // Settings
  enableWebSearch: false,
  llmProvider: 'deepseek',  // 'deepseek' | 'longcat'
  availableProviders: [],
  defaultProvider: 'deepseek',

  // Knowledge domains
  domains: [],
  selectedDomain: null,
  domainContent: '',

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
    set({ streaming: true, streamingContent: '' });

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
      });

      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      let fullContent = '';

      let streamEnded = false;
      let flushScheduled = false;

      // 缓冲最后一行不完整的内容，避免流式渲染时出现原始 Markdown 符号（如 ##）
      let lineBuffer = '';

      const getDisplayContent = () => {
        // 找到最后一个换行符，只显示完整行，缓冲不完整的最后一行
        const lastNewline = fullContent.lastIndexOf('\n');
        if (lastNewline === -1) {
          lineBuffer = fullContent;
          return '';
        }
        lineBuffer = fullContent.slice(lastNewline + 1);
        return fullContent.slice(0, lastNewline + 1);
      };

      const flushStreaming = () => {
        flushScheduled = false;
        // 流式结束时显示全部内容（包含不完整的最后一行），否则只显示完整行
        const displayContent = streamEnded ? fullContent : getDisplayContent();
        set({ streamingContent: displayContent });
      };

      const scheduleStreamingFlush = () => {
        if (flushScheduled) return;
        flushScheduled = true;
        requestAnimationFrame(flushStreaming);
      };

      const handleEvent = (data) => {
        if (data === '[DONE]') {
          streamEnded = true;
          // 立即刷新，确保不完整的最后一行也被渲染
          set({ streamingContent: fullContent });
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
      console.error('Chat error', e);
      set({ streaming: false });
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

  clearDomainView: () => set({ selectedDomain: null, domainContent: '' }),
  setSidebarTab: (tab) => set({ sidebarTab: tab, selectedDomain: null, domainContent: '' }),
}));

export default useStore;
