import React, { useState, useEffect } from 'react';
import useStore from '../store';
import MarkdownViewer from './MarkdownViewer';

export default function KnowledgeViewer() {
  const selectedDomain = useStore((s) => s.selectedDomain);
  const clearDomainView = useStore((s) => s.clearDomainView);
  const fetchEntries = useStore((s) => s.fetchEntries);
  const updateEntry = useStore((s) => s.updateEntry);
  const deleteEntry = useStore((s) => s.deleteEntry);
  const entries = useStore((s) => s.entries);

  const [editingId, setEditingId] = useState(null);
  const [editQuestion, setEditQuestion] = useState('');
  const [editAnswer, setEditAnswer] = useState('');

  useEffect(() => {
    if (selectedDomain) {
      fetchEntries(selectedDomain);
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

  if (!selectedDomain) return null;

  return (
    <div className="knowledge-viewer">
      <div className="knowledge-header">
        <h2>{selectedDomain}</h2>
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
    </div>
  );
}
