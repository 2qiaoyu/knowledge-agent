import React, { useEffect, useRef } from 'react';
import useStore from '../store';
import ChatMessages from './ChatMessages';
import ChatInput from './ChatInput';
import MarkdownViewer from './MarkdownViewer';
import KnowledgeViewer from './KnowledgeViewer';
import KnowledgeGraph from './KnowledgeGraph';

export default function ChatContainer() {
  const messages = useStore((s) => s.messages);
  const streaming = useStore((s) => s.streaming);
  const streamingContent = useStore((s) => s.streamingContent);
  const selectedDomain = useStore((s) => s.selectedDomain);
  const domainContent = useStore((s) => s.domainContent);
  const showGraph = useStore((s) => s.showGraph);
  const stopGeneration = useStore((s) => s.stopGeneration);
  const currentSessionId = useStore((s) => s.currentSessionId);
  const sessions = useStore((s) => s.sessions);
  const exportSession = useStore((s) => s.exportSession);
  const bottomRef = useRef(null);
  const messagesAreaRef = useRef(null);

  const currentSession = sessions.find((s) => s.id === currentSessionId);
  const sessionTitle = currentSession?.title || '新对话';

  useEffect(() => {
    const el = messagesAreaRef.current;
    if (!el) return;
    // 流式输出时使用即时滚动（无动画），避免平滑滚动导致的抖动
    // 仅在用户已接近底部时才自动滚动
    if (streaming) {
      const isNearBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 100;
      if (isNearBottom) {
        bottomRef.current?.scrollIntoView({ behavior: 'instant' });
      }
    } else {
      bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
    }
  }, [messages, streamingContent, streaming]);

  // Show knowledge graph if active
  if (showGraph) {
    return (
      <main className="chat-container">
        <KnowledgeGraph />
      </main>
    );
  }

  // Show knowledge domain content if selected
  if (selectedDomain) {
    return (
      <main className="chat-container">
        <KnowledgeViewer />
      </main>
    );
  }

  return (
    <main className="chat-container">
      <div className="chat-header">
        <span className="chat-title">{sessionTitle}</span>
        {messages.length > 0 && (
          <button className="btn-export" onClick={exportSession} title="导出对话为 Markdown">
            导出
          </button>
        )}
      </div>
      <div className="messages-area" ref={messagesAreaRef}>
        {messages.length === 0 && !streaming && (
          <div className="welcome">
            <h1>个人知识库助手</h1>
            <p>问我任何问题，我会帮你整理成结构化知识</p>
          </div>
        )}
        <ChatMessages messages={messages} />
        {streaming && (
          <div className="message assistant streaming">
            <div className="message-role">
              <span>AI</span>
              <button className="btn-stop" onClick={stopGeneration} title="停止生成">
                停止生成
              </button>
            </div>
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
