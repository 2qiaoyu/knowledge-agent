import React, { useEffect } from 'react';
import useStore from './store';
import ChatContainer from './components/ChatContainer';
import Sidebar from './components/Sidebar';

export default function App() {
  const fetchSessions = useStore((s) => s.fetchSessions);
  const fetchDomains = useStore((s) => s.fetchDomains);
  const fetchProviders = useStore((s) => s.fetchProviders);

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
