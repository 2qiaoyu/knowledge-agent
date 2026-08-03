import React, { useEffect, useCallback } from 'react';
import useStore from './store';
import ChatContainer from './components/ChatContainer';
import Sidebar from './components/Sidebar';

export default function App() {
  const fetchSessions = useStore((s) => s.fetchSessions);
  const fetchDomains = useStore((s) => s.fetchDomains);
  const fetchProviders = useStore((s) => s.fetchProviders);
  const newChat = useStore((s) => s.newChat);
  const stopGeneration = useStore((s) => s.stopGeneration);
  const streaming = useStore((s) => s.streaming);
  const setSidebarTab = useStore((s) => s.setSidebarTab);

  // Keyboard shortcuts
  const handleKeyDown = useCallback((e) => {
    // Ignore shortcuts when typing in input fields (except Esc)
    const isInputFocused = ['INPUT', 'TEXTAREA', 'SELECT'].includes(document.activeElement?.tagName);

    // Esc: stop generation (works everywhere)
    if (e.key === 'Escape' && streaming) {
      e.preventDefault();
      stopGeneration();
      return;
    }

    // Don't trigger other shortcuts while typing
    if (isInputFocused) return;

    // Ctrl/Cmd + N: new chat
    if ((e.ctrlKey || e.metaKey) && e.key === 'n') {
      e.preventDefault();
      newChat();
      return;
    }

    // Ctrl/Cmd + K: focus knowledge search
    if ((e.ctrlKey || e.metaKey) && e.key === 'k') {
      e.preventDefault();
      setSidebarTab('knowledge');
      // Focus the search input after tab switch
      setTimeout(() => {
        document.querySelector('.search-input')?.focus();
      }, 50);
      return;
    }
  }, [streaming, newChat, stopGeneration, setSidebarTab]);

  useEffect(() => {
    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [handleKeyDown]);

  useEffect(() => {
    fetchSessions();
    fetchDomains();
    fetchProviders();
  }, []);

  return (
    <div className="app">
      <Sidebar />
      <ChatContainer />
    </div>
  );
}
