import React, { useEffect } from 'react';
import useStore from './store';
import ChatContainer from './components/ChatContainer';
import Sidebar from './components/Sidebar';

export default function App() {
  const fetchSessions = useStore((s) => s.fetchSessions);
  const fetchDomains = useStore((s) => s.fetchDomains);

  useEffect(() => {
    fetchSessions();
    fetchDomains();
  }, []);

  return (
    <div className="app">
      <Sidebar />
      <ChatContainer />
    </div>
  );
}
