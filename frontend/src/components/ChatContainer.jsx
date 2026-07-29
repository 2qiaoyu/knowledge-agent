import React, { useEffect, useRef } from 'react';
import useStore from '../store';
import ChatMessages from './ChatMessages';
import ChatInput from './ChatInput';
import MarkdownViewer from './MarkdownViewer';

export default function ChatContainer() {
  const messages = useStore((s) => s.messages);
  const streaming = useStore((s) => s.streaming);
  const streamingContent = useStore((s) => s.streamingContent);
  const selectedDomain = useStore((s) => s.selectedDomain);
  const domainContent = useStore((s) => s.domainContent);
  const bottomRef = useRef(null);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages, streamingContent]);

  // Show knowledge domain content if selected
  if (selectedDomain) {
    return (
      <main className="chat-container">
        <div className="knowledge-viewer">
          <div className="knowledge-header">
            <h2>{selectedDomain}</h2>
            <button
              className="btn-back"
              onClick={() => useStore.getState().clearDomainView()}
            >
              返回
            </button>
          </div>
          <MarkdownViewer content={domainContent} />
        </div>
      </main>
    );
  }

  return (
    <main className="chat-container">
      <div className="messages-area">
        {messages.length === 0 && !streaming && (
          <div className="welcome">
            <h1>个人知识库助手</h1>
            <p>问我任何问题，我会帮你整理成结构化知识</p>
          </div>
        )}
        <ChatMessages messages={messages} />
        {streaming && (
          <div className="message assistant streaming">
            <div className="message-role">AI</div>
            <div className="message-content">
              <MarkdownViewer content={streamingContent} streaming />
            </div>
          </div>
        )}
        <div ref={bottomRef} />
      </div>
      <ChatInput />
    </main>
  );
}
