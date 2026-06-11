import React from 'react';
import useStore from '../store';

export default function Sidebar() {
  const sidebarTab = useStore((s) => s.sidebarTab);
  const setSidebarTab = useStore((s) => s.setSidebarTab);
  const sessions = useStore((s) => s.sessions);
  const currentSessionId = useStore((s) => s.currentSessionId);
  const loadSession = useStore((s) => s.loadSession);
  const deleteSession = useStore((s) => s.deleteSession);
  const newChat = useStore((s) => s.newChat);
  const domains = useStore((s) => s.domains);
  const fetchDomainContent = useStore((s) => s.fetchDomainContent);
  const deleteDomain = useStore((s) => s.deleteDomain);

  return (
    <aside className="sidebar">
      <div className="sidebar-header">
        <h2>知识库</h2>
      </div>

      <div className="sidebar-tabs">
        <button
          className={`tab ${sidebarTab === 'sessions' ? 'active' : ''}`}
          onClick={() => setSidebarTab('sessions')}
        >
          对话
        </button>
        <button
          className={`tab ${sidebarTab === 'knowledge' ? 'active' : ''}`}
          onClick={() => setSidebarTab('knowledge')}
        >
          知识域
        </button>
      </div>

      {sidebarTab === 'sessions' && (
        <div className="sidebar-content">
          <button className="btn-new-chat" onClick={newChat}>
            + 新对话
          </button>
          <ul className="session-list">
            {sessions.map((s) => (
              <li
                key={s.id}
                className={`session-item ${s.id === currentSessionId ? 'active' : ''}`}
                onClick={() => loadSession(s.id)}
              >
                <span className="session-title">{s.title}</span>
                <button
                  className="btn-delete"
                  onClick={(e) => {
                    e.stopPropagation();
                    deleteSession(s.id);
                  }}
                >
                  x
                </button>
              </li>
            ))}
          </ul>
        </div>
      )}

      {sidebarTab === 'knowledge' && (
        <div className="sidebar-content">
          <ul className="domain-list">
            {domains.map((d) => (
              <li
                key={d}
                className="domain-item"
                onClick={() => fetchDomainContent(d)}
              >
                <span className="domain-name">{d}</span>
                <button
                  className="btn-delete"
                  onClick={(e) => {
                    e.stopPropagation();
                    deleteDomain(d);
                  }}
                >
                  x
                </button>
              </li>
            ))}
            {domains.length === 0 && (
              <li className="empty-hint">暂无知识域，开始对话后将自动创建</li>
            )}
          </ul>
        </div>
      )}
    </aside>
  );
}
