import React, { useState, useEffect, useRef } from 'react';
import useStore from '../store';
import MarkdownViewer from './MarkdownViewer';
import DomainOrganizer from './DomainOrganizer';

export default function KnowledgeViewer() {
  const selectedDomain = useStore((s) => s.selectedDomain);
  const clearDomainView = useStore((s) => s.clearDomainView);
  const fetchEntries = useStore((s) => s.fetchEntries);
  const updateEntry = useStore((s) => s.updateEntry);
  const deleteEntry = useStore((s) => s.deleteEntry);
  const entries = useStore((s) => s.entries);
  const exportKnowledgeBase = useStore((s) => s.exportKnowledgeBase);
  const importKnowledge = useStore((s) => s.importKnowledge);
  const smartImportKnowledge = useStore((s) => s.smartImportKnowledge);
  const renameDomain = useStore((s) => s.renameDomain);

  const [editingId, setEditingId] = useState(null);
  const [editQuestion, setEditQuestion] = useState('');
  const [editAnswer, setEditAnswer] = useState('');
  const [importing, setImporting] = useState(false);
  const [smartImporting, setSmartImporting] = useState(false);
  const [showOrganizer, setShowOrganizer] = useState(false);
  const [renaming, setRenaming] = useState(false);
  const [renameValue, setRenameValue] = useState('');
  const renameInputRef = useRef(null);
  const fileInputRef = useRef(null);
  const smartFileInputRef = useRef(null);

  useEffect(() => {
    if (selectedDomain) {
      fetchEntries(selectedDomain);
      // 切换知识域时滚动到顶部
      const el = document.querySelector('.knowledge-viewer');
      if (el) el.scrollTop = 0;
    }
  }, [selectedDomain, fetchEntries]);

  const handleEdit = (entry) => {
    setEditingId(entry.id);
    setEditQuestion(entry.question);
    setEditAnswer(entry.answer);
  };

  const handleSave = async (entryId) => {
    await updateEntry(selectedDomain, entryId, editQuestion, editAnswer);
    setEditingId(null);
    fetchEntries(selectedDomain);
  };

  const handleCancel = () => {
    setEditingId(null);
  };

  const handleDelete = async (entryId) => {
    if (window.confirm('确定删除这条知识条目？')) {
      await deleteEntry(selectedDomain, entryId);
      fetchEntries(selectedDomain);
    }
  };

  const handleExport = async () => {
    try {
      await exportKnowledgeBase();
    } catch (e) {
      alert('导出失败: ' + e.message);
    }
  };

  const handleImport = async (e) => {
    const file = e.target.files[0];
    if (!file) return;
    setImporting(true);
    try {
      const result = await importKnowledge(selectedDomain, file);
      alert(`导入完成，共 ${result.entries || 0} 条条目`);
      fetchEntries(selectedDomain);
    } catch (e) {
      alert('导入失败: ' + e.message);
    } finally {
      setImporting(false);
      e.target.value = '';
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
      useStore.getState().fetchDomains();
      // Navigate to the classified domain to show the imported entries
      useStore.getState().fetchDomainContent(domain);
    } catch (e) {
      alert('智能导入失败: ' + e.message);
    } finally {
      setSmartImporting(false);
      e.target.value = '';
    }
  };

  const handleStartRename = () => {
    setRenaming(true);
    setRenameValue(selectedDomain);
    setTimeout(() => renameInputRef.current?.focus(), 50);
  };

  const handleRename = async () => {
    const newName = renameValue.trim();
    if (!newName || newName === selectedDomain) {
      setRenaming(false);
      return;
    }
    try {
      await renameDomain(selectedDomain, newName);
      setRenaming(false);
    } catch (e) {
      alert(e.message);
    }
  };

  const handleRenameKeyDown = (e) => {
    if (e.key === 'Enter') handleRename();
    if (e.key === 'Escape') setRenaming(false);
  };

  if (!selectedDomain) return null;

  return (
    <div className="knowledge-viewer">
      <div className="knowledge-header">
        {renaming ? (
          <input
            ref={renameInputRef}
            className="domain-rename-input"
            value={renameValue}
            onChange={(e) => setRenameValue(e.target.value)}
            onBlur={handleRename}
            onKeyDown={handleRenameKeyDown}
          />
        ) : (
          <h2 className="domain-title" onClick={handleStartRename} title="点击重命名">
            {selectedDomain}
          </h2>
        )}
        <div className="knowledge-actions">
          <button className="btn-export" onClick={handleExport}>
            导出知识库
          </button>
          <button
            className="btn-import"
            onClick={() => fileInputRef.current?.click()}
            disabled={importing || smartImporting}
            title="按标题直接拆分导入"
          >
            {importing ? '导入中…' : '快速导入'}
          </button>
          <input
            type="file"
            accept=".md"
            ref={fileInputRef}
            style={{ display: 'none' }}
            onChange={handleImport}
          />
          <button
            className="btn-smart-import"
            onClick={() => smartFileInputRef.current?.click()}
            disabled={importing || smartImporting}
            title="AI 智能提炼 Q&A，合并相关内容"
          >
            {smartImporting ? 'AI 处理中…' : '智能导入'}
          </button>
          <input
            type="file"
            accept=".md"
            ref={smartFileInputRef}
            style={{ display: 'none' }}
            onChange={handleSmartImport}
          />
          <button
            className="btn-organize"
            onClick={() => setShowOrganizer(true)}
            title="AI 分析域拆分 / 去重建议"
          >
            ⚙ 整理
          </button>
        </div>
        <button className="btn-back" onClick={clearDomainView}>
          返回
        </button>
      </div>

      {entries.length === 0 ? (
        <div className="empty-hint">暂无条目</div>
      ) : (
        <div className="entry-list">
          {entries.map((entry) => (
            <div key={entry.id} className="entry-card">
              {editingId === entry.id ? (
                <div className="entry-edit">
                  <input
                    className="entry-edit-question"
                    value={editQuestion}
                    onChange={(e) => setEditQuestion(e.target.value)}
                    placeholder="问题"
                  />
                  <textarea
                    className="entry-edit-answer"
                    value={editAnswer}
                    onChange={(e) => setEditAnswer(e.target.value)}
                    placeholder="回答"
                    rows={6}
                  />
                  <div className="entry-edit-actions">
                    <button className="btn-save" onClick={() => handleSave(entry.id)}>
                      保存
                    </button>
                    <button className="btn-cancel" onClick={handleCancel}>
                      取消
                    </button>
                  </div>
                </div>
              ) : (
                <>
                  <div className="entry-question">
                    <span className="entry-label">Q:</span> {entry.question}
                  </div>
                  {entry.sources && (
                    <div className="entry-sources">
                      <span className="entry-sources-label">参考来源:</span>
                      <MarkdownViewer content={entry.sources} />
                    </div>
                  )}
                  <div className="entry-answer">
                    <MarkdownViewer content={entry.answer} />
                  </div>
                  <div className="entry-actions">
                    <button className="btn-edit" onClick={() => handleEdit(entry)}>
                      编辑
                    </button>
                    <button className="btn-delete-entry" onClick={() => handleDelete(entry.id)}>
                      删除
                    </button>
                  </div>
                </>
              )}
            </div>
          ))}
        </div>
      )}

      {showOrganizer && (
        <DomainOrganizer
          domain={selectedDomain}
          onClose={(success) => {
            setShowOrganizer(false);
            if (success) {
              // 拆分成功，刷新条目列表
              clearDomainView();
            }
          }}
        />
      )}
    </div>
  );
}
