import React, { useState, useRef } from 'react';
import useStore from '../store';

export default function ChatInput() {
  const [input, setInput] = useState('');
  const inputRef = useRef(null);
  const sendMessage = useStore((s) => s.sendMessage);
  const streaming = useStore((s) => s.streaming);
  const enableWebSearch = useStore((s) => s.enableWebSearch);
  const toggleWebSearch = useStore((s) => s.toggleWebSearch);

  const handleSend = () => {
    const text = input.trim();
    if (!text || streaming) return;
    setInput('');
    sendMessage(text);
    inputRef.current?.focus();
  };

  const handleKeyDown = (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  };

  return (
    <div className="chat-input-area">
      <div className="input-row">
        <textarea
          ref={inputRef}
          className="chat-textarea"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={handleKeyDown}
          placeholder="输入问题，按 Enter 发送..."
          rows={2}
          disabled={streaming}
        />
        <button
          className="btn-send"
          onClick={handleSend}
          disabled={streaming || !input.trim()}
        >
          发送
        </button>
      </div>
      <div className="input-options">
        <label className="websearch-toggle">
          <input
            type="checkbox"
            checked={enableWebSearch}
            onChange={toggleWebSearch}
          />
          <span>联网搜索</span>
        </label>
      </div>
    </div>
  );
}
