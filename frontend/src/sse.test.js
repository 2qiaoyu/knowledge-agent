import { describe, it, expect } from 'vitest';
import { parseSSEBuffer, stripNestedDataPrefix } from './sse';

describe('parseSSEBuffer', () => {
  it('should parse a single complete event', () => {
    const { events, remainder } = parseSSEBuffer('data: hello\n\n');
    expect(events).toEqual(['hello']);
    expect(remainder).toBe('');
  });

  it('should parse multiple events', () => {
    const { events, remainder } = parseSSEBuffer('data: first\n\ndata: second\n\n');
    expect(events).toEqual(['first', 'second']);
    expect(remainder).toBe('');
  });

  it('should keep incomplete event as remainder', () => {
    const { events, remainder } = parseSSEBuffer('data: complete\n\ndata: partial');
    expect(events).toEqual(['complete']);
    expect(remainder).toBe('data: partial');
  });

  it('should handle multi-line data', () => {
    const { events } = parseSSEBuffer('data: line1\ndata: line2\n\n');
    expect(events).toEqual(['line1\nline2']);
  });

  it('should handle CRLF line endings', () => {
    const { events } = parseSSEBuffer('data: hello\r\n\r\n');
    expect(events).toEqual(['hello']);
  });

  it('should skip comment lines when data prefix is present', () => {
    // When a data: prefix exists, lines starting with : are filtered out
    const { events } = parseSSEBuffer('data: hello\n: comment\n\ndata: world\n\n');
    expect(events).toEqual(['hello', 'world']);
  });

  it('should handle empty buffer', () => {
    const { events, remainder } = parseSSEBuffer('');
    expect(events).toEqual([]);
    expect(remainder).toBe('');
  });

  it('should handle buffer with only whitespace', () => {
    const { events, remainder } = parseSSEBuffer('   \n\n');
    expect(events).toEqual([]);
  });

  it('should handle SESSION_ID control message', () => {
    const { events } = parseSSEBuffer('[SESSION_ID:abc123]\n\n');
    expect(events).toEqual(['[SESSION_ID:abc123]']);
  });

  it('should handle DONE control message', () => {
    const { events } = parseSSEBuffer('[DONE]\n\n');
    expect(events).toEqual(['[DONE]']);
  });

  it('should handle text without data prefix', () => {
    const { events } = parseSSEBuffer('plain text\n\n');
    expect(events).toEqual(['plain text']);
  });

  it('should handle space after data: colon', () => {
    const { events } = parseSSEBuffer('data: hello\n\ndata:world\n\n');
    expect(events).toEqual(['hello', 'world']);
  });
});

describe('stripNestedDataPrefix', () => {
  it('should strip single data: prefix', () => {
    expect(stripNestedDataPrefix('data: hello')).toBe('hello');
  });

  it('should strip double data: prefix', () => {
    expect(stripNestedDataPrefix('data:data: hello')).toBe('hello');
  });

  it('should strip triple data: prefix', () => {
    expect(stripNestedDataPrefix('data:data:data: hello')).toBe('hello');
  });

  it('should not modify text without prefix', () => {
    expect(stripNestedDataPrefix('hello')).toBe('hello');
  });

  it('should handle empty string', () => {
    expect(stripNestedDataPrefix('')).toBe('');
  });
});
