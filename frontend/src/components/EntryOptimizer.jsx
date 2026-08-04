import React, { useState, useRef, useEffect } from 'react';
import useStore from '../store';
import MarkdownViewer from './MarkdownViewer';

export default function EntryOptimizer({ entry, onClose, onReplace }) {
  const availableProviders = useStore((s) => s.availableProviders);
  const defaultProvider = useStore((s) => s.defaultProvider);

  const [provider, setProvider] = useState(defaultProvider || 'deepseek');
  const [enableWebSearch, setEnableWebSearch] = useState(false);
  const [optimizing, setOptimizing] = useState(false);
  const [output, setOutput] = useState('');
  const [done, setDone] = useState(false);
  const outputRef = useRef(null);

  // 自动滚动到底部
  useEffect(() => {
    if (outputRef.current) {
      outputRef.current.scrollTop = outputRef.current.scrollHeight;
    }
  }, [output]);

  const handleOptimize = async () => {
    setOptimizing(true);
    setOutput('');
    setDone(false);

    try {
      const response = await fetch('/api/knowledge/optimize-entry', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          question: entry.question,
          answer: entry.answer,
          provider,
          enableWebSearch,
        }),
      });

      if (!response.ok) {
        throw new Error(`请求失败: ${response.status}`);
      }

      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';

      // 健壮的 SSE 解析：处理一行中多个 data: 前缀的情况
      const parseSSEChunk = (chunk) => {
        // SSE 格式: data: <content>\n\n
        // 但 LongCat 可能返回嵌套的 data: data: <content>
        // 需要提取所有 data: 行并拼接内容
        const events = chunk.split('\n\n');
        let content = '';
        for (const event of events) {
          // 处理事件中的每一行
          const lines = event.split('\n');
          for (const line of lines) {
            const trimmed = line.trim();
            // 移除所有层级的 "data: " 前缀
            if (trimmed.startsWith('data:')) {
              let text = trimmed.slice(5).trimStart();
              // 处理嵌套的 data: data: 情况
              while (text.startsWith('data:')) {
                text = text.slice(5).trimStart();
              }
              content += text;
            }
          }
        }
        return content;
      };

      while (true) {
        const { value, done: streamDone } = await reader.read();

        if (value) {
          buffer += decoder.decode(value, { stream: true });
        }

        // 处理所有完整的 SSE 事件（以 \n\n 分隔）
        const events = buffer.split('\n\n');
        // 最后一个元素可能是不完整的，保留到下次处理
        buffer = events.pop() || '';

        for (const event of events) {
          const text = parseSSEChunk(event);
          if (text) {
            setOutput((prev) => prev + text);
          }
        }

        if (streamDone) {
          // 流结束，处理 buffer 中剩余的数据
          if (buffer.trim()) {
            const text = parseSSEChunk(buffer);
            if (text) {
              setOutput((prev) => prev + text);
            }
          }
          break;
        }
      }
      setDone(true);
    } catch (e) {
      setOutput(`优化失败: ${e.message}`);
      setDone(true);
    } finally {
      setOptimizing(false);
    }
  };

  const handleReplace = () => {
    if (output.trim()) {
      onReplace(output.trim());
      onClose();
    }
  };

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(output);
    } catch {
      // fallback
      const textarea = document.createElement('textarea');
      textarea.value = output;
      document.body.appendChild(textarea);
      textarea.select();
      document.execCommand('copy');
      document.body.removeChild(textarea);
    }
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal entry-optimizer" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <h3>优化知识条目</h3>
          <button className="btn-close" onClick={onClose}>×</button>
        </div>

        <div className="modal-body">
          <div className="optimize-original">
            <label>原始问题</label>
            <div className="optimize-question">{entry.question}</div>
          </div>

          <div className="optimize-options">
            <div className="optimize-option-group">
              <label>模型</label>
              <div className="optimize-providers">
                {availableProviders.length > 0 ? (
                  availableProviders.map((p) => (
                    <label key={p} className="radio-label">
                      <input
                        type="radio"
                        name="provider"
                        value={p}
                        checked={provider === p}
                        onChange={() => setProvider(p)}
                      />
                      {p === 'deepseek' ? 'DeepSeek' : p === 'longcat' ? 'LongCat' : p}
                    </label>
                  ))
                ) : (
                  <>
                    <label className="radio-label">
                      <input
                        type="radio"
                        name="provider"
                        value="deepseek"
                        checked={provider === 'deepseek'}
                        onChange={() => setProvider('deepseek')}
                      />
                      DeepSeek
                    </label>
                    <label className="radio-label">
                      <input
                        type="radio"
                        name="provider"
                        value="longcat"
                        checked={provider === 'longcat'}
                        onChange={() => setProvider('longcat')}
                      />
                      LongCat
                    </label>
                  </>
                )}
              </div>
            </div>

            <label className="checkbox-label">
              <input
                type="checkbox"
                checked={enableWebSearch}
                onChange={(e) => setEnableWebSearch(e.target.checked)}
              />
              启用联网搜索
            </label>
          </div>

          {!output && !optimizing && (
            <button className="btn-start-optimize" onClick={handleOptimize}>
              开始优化
            </button>
          )}

          {(output || optimizing) && (
            <div className="optimize-result">
              <label>优化结果 {optimizing && '(生成中...)'}</label>
              <div className="optimize-output" ref={outputRef}>
                {output ? <MarkdownViewer content={output} streaming={!done} /> : (
                  <div className="optimize-loading">正在生成...</div>
                )}
              </div>
            </div>
          )}
        </div>

        <div className="modal-footer">
          <button className="btn-cancel" onClick={onClose} disabled={optimizing}>
            取消
          </button>
          {output && done && (
            <>
              <button className="btn-copy" onClick={handleCopy}>
                复制
              </button>
              <button className="btn-replace" onClick={handleReplace}>
                替换原内容
              </button>
            </>
          )}
        </div>
      </div>
    </div>
  );
}
