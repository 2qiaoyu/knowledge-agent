import React, { useState, useRef, useEffect } from 'react';
import useStore from '../store';
import MarkdownViewer from './MarkdownViewer';

export default function ChatMessages({ messages }) {
  const streaming = useStore((s) => s.streaming);
  const regenerate = useStore((s) => s.regenerate);
  const deleteMessage = useStore((s) => s.deleteMessage);
  const editMessage = useStore((s) => s.editMessage);
  const editAssistantMessage = useStore((s) => s.editAssistantMessage);

  const [editingId, setEditingId] = useState(null);
  const [editContent, setEditContent] = useState('');
  const textareaRef = useRef(null);

  // 只在非流式、且最后一条是 assistant 消息时显示重新生成按钮
  const showRegenerate = !streaming && messages.length > 0 && messages[messages.length - 1].role === 'assistant';

  const handleDelete = (msg) => {
    if (window.confirm('确定删除这条消息？')) {
      deleteMessage(msg.id);
    }
  };

  const startEdit = (msg) => {
    setEditingId(msg.id);
    setEditContent(msg.content);
  };

  const cancelEdit = () => {
    setEditingId(null);
    setEditContent('');
  };

  const saveEdit = (msg) => {
    if (!editContent.trim()) return;
    if (msg.role === 'user') {
      editMessage(msg.id, editContent.trim());
    } else {
      editAssistantMessage(msg.id, editContent.trim());
    }
    cancelEdit();
  };

  // 自动聚焦 textarea
  useEffect(() => {
    if (editingId && textareaRef.current) {
      textareaRef.current.focus();
      textareaRef.current.style.height = 'auto';
      textareaRef.current.style.height = textareaRef.current.scrollHeight + 'px';
    }
  }, [editingId]);

  const handleTextareaChange = (e) => {
    setEditContent(e.target.value);
    e.target.style.height = 'auto';
    e.target.style.height = e.target.scrollHeight + 'px';
  };

  const handleKeyDown = (e, msg) => {
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
      e.preventDefault();
      saveEdit(msg);
    }
    if (e.key === 'Escape') {
      cancelEdit();
    }
  };

  return (
    <>
      {messages.map((msg) => (
        <div key={msg.id} className={`message ${msg.role}`}>
          <div className="message-role">
            {msg.role === 'user' ? '你' : 'AI'}
            {editingId !== msg.id && (
              <div className="message-actions">
                <button
                  className="btn-edit-msg"
                  onClick={() => startEdit(msg)}
                  title="编辑此消息"
                >
                  ✎
                </button>
                <button
                  className="btn-delete-msg"
                  onClick={() => handleDelete(msg)}
                  title="删除此消息"
                >
                  ✕
                </button>
              </div>
            )}
          </div>
          <div className="message-content">
            {editingId === msg.id ? (
              <div className="message-edit">
                <textarea
                  ref={textareaRef}
                  className="edit-textarea"
                  value={editContent}
                  onChange={handleTextareaChange}
                  onKeyDown={(e) => handleKeyDown(e, msg)}
                  rows={2}
                />
                <div className="edit-actions">
                  <button className="btn-save-edit" onClick={() => saveEdit(msg)}>
                    保存
                  </button>
                  <button className="btn-cancel-edit" onClick={cancelEdit}>
                    取消
                  </button>
                  <span className="edit-hint">Ctrl+Enter 保存 · Esc 取消</span>
                </div>
              </div>
            ) : (
              <MarkdownViewer content={msg.content} />
            )}
          </div>
        </div>
      ))}
      {showRegenerate && (
        <div className="regenerate-bar">
          <button className="btn-regenerate" onClick={regenerate}>
            重新生成
          </button>
        </div>
      )}
    </>
  );
}
