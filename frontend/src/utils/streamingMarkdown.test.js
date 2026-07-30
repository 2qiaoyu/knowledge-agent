import { describe, it, expect } from 'vitest';
import { fixIncompleteMarkdown, hasIncompleteMarkdown } from './streamingMarkdown';

describe('fixIncompleteMarkdown', () => {
  describe('code fences', () => {
    it('should close unclosed triple backtick fence', () => {
      const input = '```javascript\nconst x = 1;';
      const result = fixIncompleteMarkdown(input);
      expect(result).toBe('```javascript\nconst x = 1;\n```');
    });

    it('should not modify closed code fence', () => {
      const input = '```javascript\nconst x = 1;\n```';
      const result = fixIncompleteMarkdown(input);
      expect(result).toBe(input);
    });

    it('should close unclosed tilde fence', () => {
      const input = '~~~\ncode here';
      const result = fixIncompleteMarkdown(input);
      expect(result).toBe('~~~\ncode here\n~~~');
    });

    it('should handle multiple code blocks - all closed', () => {
      const input = '```js\na\n```\n\n```py\nb\n```';
      const result = fixIncompleteMarkdown(input);
      expect(result).toBe(input);
    });

    it('should handle multiple code blocks - last unclosed', () => {
      const input = '```js\na\n```\n\n```py\nb';
      const result = fixIncompleteMarkdown(input);
      expect(result).toBe('```js\na\n```\n\n```py\nb\n```');
    });
  });

  describe('inline code', () => {
    it('should close unclosed inline code', () => {
      const input = 'Use `console.log() to print';
      const result = fixIncompleteMarkdown(input);
      expect(result).toBe('Use `console.log() to print`');
    });

    it('should not modify closed inline code', () => {
      const input = 'Use `console.log()` to print';
      const result = fixIncompleteMarkdown(input);
      expect(result).toBe(input);
    });

    it('should handle multiple inline code spans', () => {
      const input = 'Use `foo` and `bar` to print';
      const result = fixIncompleteMarkdown(input);
      expect(result).toBe(input);
    });
  });

  describe('bold emphasis', () => {
    it('should close unclosed double asterisk bold', () => {
      const input = 'This is **bold text';
      const result = fixIncompleteMarkdown(input);
      expect(result).toBe('This is **bold text**');
    });

    it('should not modify closed bold', () => {
      const input = 'This is **bold text**';
      const result = fixIncompleteMarkdown(input);
      expect(result).toBe(input);
    });

    it('should close unclosed double underscore bold', () => {
      const input = 'This is __bold text';
      const result = fixIncompleteMarkdown(input);
      expect(result).toBe('This is __bold text__');
    });
  });

  describe('italic emphasis', () => {
    it('should close unclosed single asterisk italic', () => {
      const input = 'This is *italic text';
      const result = fixIncompleteMarkdown(input);
      expect(result).toBe('This is *italic text*');
    });

    it('should not modify closed italic', () => {
      const input = 'This is *italic text*';
      const result = fixIncompleteMarkdown(input);
      expect(result).toBe(input);
    });
  });

  describe('links', () => {
    it('should close unclosed link with open paren', () => {
      const input = 'Check [this link](https://example';
      const result = fixIncompleteMarkdown(input);
      expect(result).toBe('Check [this link](https://example)');
    });

    it('should remove incomplete link without paren', () => {
      const input = 'Check [this link';
      const result = fixIncompleteMarkdown(input);
      expect(result.trim()).toBe('Check');
    });
  });

  describe('edge cases', () => {
    it('should handle empty string', () => {
      expect(fixIncompleteMarkdown('')).toBe('');
    });

    it('should handle null/undefined', () => {
      expect(fixIncompleteMarkdown(null)).toBe(null);
      expect(fixIncompleteMarkdown(undefined)).toBe(undefined);
    });

    it('should handle plain text without markdown', () => {
      const input = 'Just some plain text without any markdown.';
      const result = fixIncompleteMarkdown(input);
      expect(result).toBe(input);
    });

    it('should handle complete markdown document', () => {
      const input = '# Title\n\nSome **bold** and *italic* text.\n\n```js\ncode\n```\n\n- item 1\n- item 2\n\n[link](https://example.com)';
      const result = fixIncompleteMarkdown(input);
      expect(result).toBe(input);
    });
  });
});

describe('hasIncompleteMarkdown', () => {
  it('should detect unclosed code fence', () => {
    expect(hasIncompleteMarkdown('```js\ncode')).toBe(true);
  });

  it('should detect unclosed inline code', () => {
    expect(hasIncompleteMarkdown('Use `code here')).toBe(true);
  });

  it('should detect unclosed bold', () => {
    expect(hasIncompleteMarkdown('This is **bold')).toBe(true);
  });

  it('should return false for complete markdown', () => {
    expect(hasIncompleteMarkdown('This is **bold** and `code`')).toBe(false);
  });

  it('should return false for empty string', () => {
    expect(hasIncompleteMarkdown('')).toBe(false);
  });

  it('should return false for null/undefined', () => {
    expect(hasIncompleteMarkdown(null)).toBe(false);
    expect(hasIncompleteMarkdown(undefined)).toBe(false);
  });
});
