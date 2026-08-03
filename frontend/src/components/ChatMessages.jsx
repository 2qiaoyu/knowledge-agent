import React, { useState, useRef, useEffect } from 'react';
import useStore from '../store';
import MarkdownViewer from './MarkdownViewer';

export default function ChatMessages({ messages }) {
  const streaming = useStore((s) => s.streaming);
  const chatError = useStore((s) => s.chatError);
  const clearChatError = useStore((s) => s.clearChatError);
  const regenerate = useStore((s) => s.regenerate);
  const deleteMessage = useStore((s) => s.deleteMessage);
  const editMessage = useStore((s) => s.editMessage);
  const editAssistantMessage = useStore((s) => s.editAssistantMessage);
  const recommendations = useStore((s) => s.recommendations);
  const clearRecommendations = useStore((s) => s.clearRecommendations);
  const fetchDomainContent = useStore((s) => s.fetchDomainContent);

  const [editingId, setEditingId] = useState(null);
  const [editContent, setEditContent] = useState('');
  const [activeMenuId, setActiveMenuId] = useState(null);
  const [copiedId, setCopiedId] = useState(null);
  const textareaRef = useRef(null);

  // 只在非流式、且最后一条是 assistant 消息时显示重新生成按钮
  const showRegenerate = !streaming && messages.length > 0 && messages[messages.length - 1].role === 'assistant';

  const handleDelete = (msg) => {
    setActiveMenuId(null);
    if (window.confirm('确定删除这条消息？')) {
      deleteMessage(msg.id);
    }
  };

  const handleCopy = async (msg) => {
    try {
      await navigator.clipboard.writeText(msg.content);
    } catch {
      const textarea = document.createElement('textarea');
      textarea.value = msg.content;
      document.body.appendChild(textarea);
      textarea.select();
      document.execCommand('copy');
      document.body.removeChild(textarea);
    }
    setCopiedId(msg.id);
    setTimeout(() => setCopiedId(null), 2000);
    setActiveMenuId(null);
  };

  const startEdit = (msg) => {
    setActiveMenuId(null);
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

  // 点击外部关闭菜单
  useEffect(() => {
    if (!activeMenuId) return;
    const handleClick = () => setActiveMenuId(null);
    document.addEventListener('click', handleClick);
    return () => document.removeEventListener('click', handleClick);
  }, [activeMenuId]);

  return (
    <>
      {messages.map((msg) => (
        <div key={msg.id} className={`message ${msg.role}`}>
          <div className="message-role">
            {msg.role === 'user' ? '你' : 'AI'}
            {editingId !== msg.id && (
              <div className="message-actions">
                <button
                  className="btn-msg-menu-trigger"
                  onClick={(e) => {
                    e.stopPropagation();
                    setActiveMenuId(activeMenuId === msg.id ? null : msg.id);
                  }}
                  title="更多操作"
                >
                  ⋯
                </button>
                {activeMenuId === msg.id && (
                  <div className="message-action-dropdown" onClick={(e) => e.stopPropagation()}>
                    <button className="btn-action-item" onClick={() => handleCopy(msg)}>
                      {copiedId === msg.id ? '✓ 已复制' : '📋 复制'}
                    </button>
                    <button className="btn-action-item" onClick={() => startEdit(msg)}>
                      ✎ 编辑
                    </button>
                    <button className="btn-action-item btn-action-danger" onClick={() => handleDelete(msg)}>
                      ✕ 删除
                    </button>
                  </div>
                )}
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
      {recommendations.length > 0 && !streaming && (
        <div className="recommendations">
          <div className="recommendations-header">
            <span>📚 相关知识推荐</span>
            <button className="btn-dismiss-error" onClick={clearRecommendations} title="关闭">✕</button>
          </div>
          {recommendations.map((r, i) => (
            <div
              key={i}
              className="recommendation-card"
              onClick={() => {
                fetchDomainContent(r.domain);
                clearRecommendations();
              }}
            >
              <div className="recommendation-domain">{r.domain}</div>
              <div className="recommendation-question">{r.question}</div>
            </div>
          ))}
        </div>
      )}
      {chatError && (
        <div className="chat-error-banner">
          <span className="chat-error-icon">⚠</span>
          <span className="chat-error-message">{chatError.message}</span>
          {chatError.retryable && (
            <button className="btn-retry" onClick={chatError.retry || regenerate}>
              重试
            </button>
          )}
          <button className="btn-dismiss-error" onClick={clearChatError} title="关闭">
            ✕
          </button>
        </div>
      )}
    </>
  );
}
