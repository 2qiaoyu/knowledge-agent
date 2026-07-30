import React from 'react';
import { Streamdown } from 'streamdown';
import { code } from '@streamdown/code';

const plugins = { code };

export default function MarkdownViewer({ content, streaming = false }) {
  if (!content) {
    return null;
  }

  // 流式渲染时使用纯文本，避免不完整的 Markdown（如 ##）被渲染成原始符号
  // 流结束后切换为 streamdown 渲染完整 Markdown
  if (streaming) {
    return (
      <div className="streamdown-message whitespace-pre-wrap break-words">
        {content}
      </div>
    );
  }

  return (
    <Streamdown
      className="streamdown-message"
      mode="static"
      plugins={plugins}
      shikiTheme={['github-light', 'github-light']}
      lineNumbers
    >
      {content}
    </Streamdown>
  );
}
