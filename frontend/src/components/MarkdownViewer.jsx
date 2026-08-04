import React, { useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter';
import { oneLight } from 'react-syntax-highlighter/dist/esm/styles/prism';
import { fixIncompleteMarkdown, normalizeHeadingSpace } from '../utils/streamingMarkdown';

/**
 * MarkdownViewer - 支持流式渲染的 Markdown 组件
 *
 * 特性：
 * - 使用 react-markdown 渲染 Markdown
 * - 流式输出时自动修复未闭合的 Markdown 标记
 * - 支持 GFM（表格、删除线、任务列表）
 * - 代码块使用 react-syntax-highlighter 高亮
 */
export default function MarkdownViewer({ content, streaming = false }) {
  if (!content) {
    return null;
  }

  // 流式输出时修复未闭合的 Markdown 标记；始终规范化标题空格（修复 ##标题 → ## 标题）
  let processedContent = streaming ? fixIncompleteMarkdown(content) : content;
  processedContent = normalizeHeadingSpace(processedContent);

  return (
    <div className="markdown-body">
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          code({ node, inline, className, children, ...props }) {
            const match = /language-(\w+)/.exec(className || '');
            const language = match ? match[1] : '';

            if (inline) {
              return (
                <code className="inline-code" {...props}>
                  {children}
                </code>
              );
            }

            return (
              <CodeBlock language={language}>
                {String(children).replace(/\n$/, '')}
              </CodeBlock>
            );
          },
          table({ children }) {
            return <table className="markdown-table">{children}</table>;
          },
        }}
      >
        {processedContent}
      </ReactMarkdown>
    </div>
  );
}

/**
 * CodeBlock with copy button
 */
function CodeBlock({ children, language }) {
  const [copied, setCopied] = useState(false);

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(children);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // Fallback for older browsers
      const textarea = document.createElement('textarea');
      textarea.value = children;
      document.body.appendChild(textarea);
      textarea.select();
      document.execCommand('copy');
      document.body.removeChild(textarea);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    }
  };

  return (
    <div className="code-block-wrapper">
      {language && (
        <div className="code-block-header">
          <span className="code-block-lang">{language}</span>
          <button className="btn-copy-code" onClick={handleCopy} title="复制代码">
            {copied ? '✓ 已复制' : '复制'}
          </button>
        </div>
      )}
      <SyntaxHighlighter
        style={oneLight}
        language={language || 'text'}
        PreTag="div"
        customStyle={{
          margin: 0,
          borderRadius: language ? '0 0 8px 8px' : '8px',
          fontSize: '14px',
          lineHeight: '1.6',
        }}
      >
        {children}
      </SyntaxHighlighter>
    </div>
  );
}
