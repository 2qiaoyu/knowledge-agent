import React, { useState, useRef } from 'react';
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
  const fetchDomains = useStore((s) => s.fetchDomains);
  const deleteDomain = useStore((s) => s.deleteDomain);
  const searchKnowledge = useStore((s) => s.searchKnowledge);
  const clearSearch = useStore((s) => s.clearSearch);
  const searchQuery = useStore((s) => s.searchQuery);
  const searchResults = useStore((s) => s.searchResults);
  const reclassifyDomains = useStore((s) => s.reclassifyDomains);
  const exportKnowledgeBase = useStore((s) => s.exportKnowledgeBase);
  const smartImportKnowledge = useStore((s) => s.smartImportKnowledge);

  const [localSearch, setLocalSearch] = useState('');
  const [reclassifying, setReclassifying] = useState(false);
  const [smartImporting, setSmartImporting] = useState(false);
  const fileInputRef = useRef(null);

  const handleSearch = (e) => {
    e.preventDefault();
    const query = localSearch.trim();
    if (query) {
      searchKnowledge(query);
    }
  };

  const handleClearSearch = () => {
    setLocalSearch('');
    clearSearch();
  };

  const handleReclassify = async () => {
    if (!window.confirm('将「通用知识」中的条目按主题拆分为更细的知识域，继续？')) return;
    setReclassifying(true);
    try {
      const result = await reclassifyDomains();
      alert(result.message || '重新分类完成');
    } catch (e) {
      alert('重新分类失败: ' + e.message);
    } finally {
      setReclassifying(false);
    }
  };

  const handleExportAll = async () => {
    try {
      await exportKnowledgeBase();
    } catch (e) {
      alert('导出失败: ' + e.message);
    }
  };

  const handleSmartImport = async (e) => {
    const file = e.target.files[0];
    if (!file) return;
    setSmartImporting(true);
    try {
      const result = await smartImportKnowledge(file);
      const domain = result.domain || '未知域';
      alert(`智能导入完成！\n知识域：${domain}\nAI 提炼出 ${result.entries || 0} 条高质量条目`);
      // Refresh domain list (in case a new domain was created)
      fetchDomains();
      // Navigate to the classified domain
      fetchDomainContent(domain);
    } catch (e) {
      alert('智能导入失败: ' + e.message);
    } finally {
      setSmartImporting(false);
      e.target.value = '';
    }
  };

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
                    if (window.confirm(`确定删除对话「${s.title}」？`)) {
                      deleteSession(s.id);
                    }
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
          {/* Search bar */}
          <form className="knowledge-search" onSubmit={handleSearch}>
            <input
              type="text"
              className="search-input"
              placeholder="搜索知识库..."
              value={localSearch}
              onChange={(e) => setLocalSearch(e.target.value)}
            />
            {searchQuery && (
              <button type="button" className="btn-clear-search" onClick={handleClearSearch}>
                x
              </button>
            )}
          </form>

          {/* Search results */}
          {searchQuery && (
            <div className="search-results">
              <div className="search-results-header">
                搜索结果 ({searchResults.length})
              </div>
              {searchResults.length === 0 ? (
                <div className="empty-hint">未找到相关内容</div>
              ) : (
                <ul className="search-result-list">
                  {searchResults.map((r, i) => (
                    <li
                      key={i}
                      className="search-result-item"
                      onClick={() => {
                        fetchDomainContent(r.domain);
                        handleClearSearch();
                      }}
                    >
                      <div className="search-result-domain">{r.domain}</div>
                      <div className="search-result-question">{r.question}</div>
                      {r.answer && (
                        <div className="search-result-preview">{r.answer}</div>
                      )}
                    </li>
                  ))}
                </ul>
              )}
            </div>
          )}

          {/* Domain list (hidden during search) */}
          {!searchQuery && (
            <>
              <div className="knowledge-actions">
                <button
                  className="btn-smart-import"
                  onClick={() => fileInputRef.current?.click()}
                  disabled={smartImporting}
                  title="AI 智能提炼 Q&A 并自动分类"
                >
                  {smartImporting ? 'AI 处理中…' : '智能导入'}
                </button>
                <button
                  className="btn-export"
                  onClick={handleExportAll}
                  title="导出全部知识域为 zip"
                >
                  导出全部
                </button>
                <input
                  type="file"
                  accept=".md"
                  ref={fileInputRef}
                  style={{ display: 'none' }}
                  onChange={handleSmartImport}
                />
              </div>
              <button
                className="btn-reclassify"
                onClick={handleReclassify}
                disabled={reclassifying}
              >
                {reclassifying ? '重新分类中…' : '↻ 拆分通用知识'}
              </button>
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
            </>
          )}
        </div>
      )}
    </aside>
  );
}
