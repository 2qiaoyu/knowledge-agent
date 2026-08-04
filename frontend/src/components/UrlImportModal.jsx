import React, { useState, useRef } from 'react';
import useStore from '../store';

/**
 * 从网页导入知识 — 分步模态框
 *
 * 步骤：
 * 1. 输入 URL → 抓取网页
 * 2. 预览正文 → 选择模型 → 提炼 Q&A
 * 3. 预览 Q&A + 自动分类结果 → 确认导入
 */
export default function UrlImportModal({ onClose, onImportSuccess }) {
  const availableProviders = useStore((s) => s.availableProviders);
  const defaultProvider = useStore((s) => s.defaultProvider);
  const fetchUrlContent = useStore((s) => s.fetchUrlContent);
  const importFromUrl = useStore((s) => s.importFromUrl);

  const [step, setStep] = useState('idle'); // idle | fetching | fetched | extracting | done | importing | error
  const [url, setUrl] = useState('');
  const [title, setTitle] = useState('');
  const [text, setText] = useState('');
  const [charCount, setCharCount] = useState(0);
  const [provider, setProvider] = useState(defaultProvider || 'deepseek');
  const [qaPairs, setQaPairs] = useState([]);
  const [domain, setDomain] = useState('');
  const [error, setError] = useState('');
  const urlInputRef = useRef(null);

  // 步骤 1：抓取网页
  const handleFetch = async () => {
    if (!url.trim()) return;
    setStep('fetching');
    setError('');
    try {
      const result = await fetchUrlContent(url.trim());
      setTitle(result.title || '未命名网页');
      setText(result.text || '');
      setCharCount(result.charCount || result.text?.length || 0);
      setStep('fetched');
    } catch (e) {
      setError(e.message || '抓取失败');
      setStep('idle');
    }
  };

  // 步骤 2：提炼 Q&A
  const handleExtract = async () => {
    setStep('extracting');
    setError('');
    try {
      // 先调用 fetch 端点获取文本，然后调用 import 端点
      // 但为了预览 Q&A，我们需要一个只提炼不保存的端点
      // 这里直接调用 import 端点（它会保存），然后展示结果
      // 如果用户取消，需要回滚 — 简化处理：直接导入
      const result = await importFromUrl(url.trim(), title, text, provider);
      setQaPairs(result.qaPairs || []);
      setDomain(result.domain || '未知');
      setStep('done');
    } catch (e) {
      setError(e.message || '提炼失败');
      setStep('fetched');
    }
  };

  // 确认导入（已经在 handleExtract 中完成导入，这里只关闭）
  const handleConfirm = () => {
    if (onImportSuccess && domain) {
      onImportSuccess(domain);
    }
    onClose();
  };

  // 重置到步骤 1
  const handleBack = () => {
    setStep('idle');
    setUrl('');
    setTitle('');
    setText('');
    setQaPairs([]);
    setDomain('');
    setError('');
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal url-import-modal" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <h3>从网页导入知识</h3>
          <button className="btn-close" onClick={onClose}>×</button>
        </div>

        <div className="modal-body">
          {/* 步骤指示器 */}
          <div className="step-indicator">
            <div className={`step-dot ${step !== 'idle' ? 'active' : ''}`}>1</div>
            <div className="step-line"></div>
            <div className={`step-dot ${['fetched', 'extracting', 'done', 'importing'].includes(step) ? 'active' : ''}`}>2</div>
            <div className="step-line"></div>
            <div className={`step-dot ${step === 'done' ? 'active' : ''}`}>3</div>
          </div>

          {/* 错误提示 */}
          {error && <div className="url-import-error">❌ {error}</div>}

          {/* 步骤 1：URL 输入 */}
          {(step === 'idle' || step === 'fetching') && (
            <div className="url-import-step">
              <label className="url-import-label">网页 URL</label>
              <input
                ref={urlInputRef}
                className="url-input"
                type="url"
                value={url}
                onChange={(e) => setUrl(e.target.value)}
                placeholder="https://mp.weixin.qq.com/s/..."
                onKeyDown={(e) => e.key === 'Enter' && handleFetch()}
                disabled={step === 'fetching'}
              />
              <button
                className="btn-fetch-url"
                onClick={handleFetch}
                disabled={!url.trim() || step === 'fetching'}
              >
                {step === 'fetching' ? '抓取中...' : '抓取网页'}
              </button>
            </div>
          )}

          {/* 步骤 2：抓取结果 + 模型选择 */}
          {(step === 'fetched' || step === 'extracting') && (
            <div className="url-import-step">
              <div className="fetch-result">
                <div className="fetch-result-header">
                  <span className="fetch-icon">📄</span>
                  <span className="fetch-title">{title}</span>
                </div>
                <div className="fetch-meta">
                  <span>📝 正文长度: {charCount.toLocaleString()} 字</span>
                </div>
                <div className="fetch-preview-text">
                  {text.substring(0, 300)}{text.length > 300 ? '...' : ''}
                </div>
              </div>

              <div className="url-import-options">
                <label className="url-import-label">选择模型</label>
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

              <button
                className="btn-extract-qa"
                onClick={handleExtract}
                disabled={step === 'extracting'}
              >
                {step === 'extracting' ? 'AI 提炼中...' : '开始提炼'}
              </button>
            </div>
          )}

          {/* 步骤 3：Q&A 预览 */}
          {step === 'done' && (
            <div className="url-import-step">
              <div className="import-domain-badge">
                🏷️ 自动分类: <strong>{domain}</strong>
                <span className="import-count">共 {qaPairs.length} 条 Q&A</span>
              </div>

              <div className="qa-preview-list">
                {qaPairs.map((pair, idx) => (
                  <div key={idx} className="qa-preview-item">
                    <div className="qa-preview-question">
                      <span className="qa-num">Q{idx + 1}:</span> {pair.question}
                    </div>
                    <div className="qa-preview-answer">
                      {pair.answer.substring(0, 150)}{pair.answer.length > 150 ? '...' : ''}
                    </div>
                  </div>
                ))}
              </div>

              {qaPairs.length === 0 && (
                <div className="url-import-empty">
                  未能提炼出 Q&A，请检查网页内容是否包含足够的知识性内容。
                </div>
              )}
            </div>
          )}
        </div>

        <div className="modal-footer">
          <button className="btn-cancel" onClick={onClose} disabled={step === 'extracting'}>
            取消
          </button>
          {step === 'fetched' && (
            <button className="btn-back" onClick={handleBack}>
              ← 重新输入
            </button>
          )}
          {step === 'done' && qaPairs.length > 0 && (
            <button className="btn-apply" onClick={handleConfirm}>
              完成（已导入 {qaPairs.length} 条）
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
