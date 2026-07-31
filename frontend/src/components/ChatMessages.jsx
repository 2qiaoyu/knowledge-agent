import React from 'react';
import useStore from '../store';
import MarkdownViewer from './MarkdownViewer';

export default function ChatMessages({ messages }) {
  const streaming = useStore((s) => s.streaming);
  const regenerate = useStore((s) => s.regenerate);
  const deleteMessage = useStore((s) => s.deleteMessage);

  // 只在非流式、且最后一条是 assistant 消息时显示重新生成按钮
  const showRegenerate = !streaming && messages.length > 0 && messages[messages.length - 1].role === 'assistant';

  const handleDelete = (msg) => {
    if (window.confirm('确定删除这条消息？')) {
      deleteMessage(msg.id);
    }
  };

  return (
    <>
      {messages.map((msg) => (
        <div key={msg.id} className={`message ${msg.role}`}>
          <div className="message-role">
            {msg.role === 'user' ? '你' : 'AI'}
            <button
              className="btn-delete-msg"
              onClick={() => handleDelete(msg)}
              title="删除此消息"
            >
              ✕
            </button>
          </div>
          <div className="message-content">
            <MarkdownViewer content={msg.content} />
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
