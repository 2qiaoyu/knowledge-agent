import React from 'react';
import { Streamdown } from 'streamdown';
import { code } from '@streamdown/code';

const plugins = { code };

export default function MarkdownViewer({ content, streaming = false }) {
  if (!content) {
    return null;
  }

  return (
    <Streamdown
      className="streamdown-message"
      mode={streaming ? 'streaming' : 'static'}
      isAnimating={streaming}
      parseIncompleteMarkdown={streaming}
      plugins={plugins}
      shikiTheme={['github-light', 'github-light']}
      lineNumbers
    >
      {content}
    </Streamdown>
  );
}
