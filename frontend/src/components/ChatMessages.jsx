import React from 'react';
import useStore from '../store';
import MarkdownViewer from './MarkdownViewer';

export default function ChatMessages({ messages }) {
  const streaming = useStore((s) => s.streaming);
  const regenerate = useStore((s) => s.regenerate);

  // 只在非流式、且最后一条是 assistant 消息时显示重新生成按钮
  const showRegenerate = !streaming && messages.length > 0 && messages[messages.length - 1].role === 'assistant';

  return (
    <>
      {messages.map((msg) => (
        <div key={msg.id} className={`message ${msg.role}`}>
          <div className="message-role">
            {msg.role === 'user' ? '你' : 'AI'}
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
