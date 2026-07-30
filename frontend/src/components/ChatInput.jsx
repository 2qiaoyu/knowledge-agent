import React, { useState, useRef } from 'react';
import useStore from '../store';

const PROVIDER_LABELS = {
  deepseek: 'DeepSeek',
  longcat: 'LongCat',
};

export default function ChatInput() {
  const [input, setInput] = useState('');
  const inputRef = useRef(null);
  const sendMessage = useStore((s) => s.sendMessage);
  const streaming = useStore((s) => s.streaming);
  const enableWebSearch = useStore((s) => s.enableWebSearch);
  const toggleWebSearch = useStore((s) => s.toggleWebSearch);
  const llmProvider = useStore((s) => s.llmProvider);
  const setLlmProvider = useStore((s) => s.setLlmProvider);
  const availableProviders = useStore((s) => s.availableProviders);

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
        {availableProviders.length > 1 && (
          <label className="provider-selector">
            <span>模型:</span>
            <select
              value={llmProvider}
              onChange={(e) => setLlmProvider(e.target.value)}
              disabled={streaming}
            >
              {availableProviders.map((p) => (
                <option key={p} value={p}>
                  {PROVIDER_LABELS[p] || p}
                </option>
              ))}
            </select>
          </label>
        )}
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
