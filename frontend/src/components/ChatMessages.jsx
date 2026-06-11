import React from 'react';
import MarkdownViewer from './MarkdownViewer';

export default function ChatMessages({ messages }) {
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
    </>
  );
}
