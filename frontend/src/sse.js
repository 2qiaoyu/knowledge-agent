/**
 * Parse Server-Sent Events from a text buffer.
 * Events are delimited by blank lines (\n\n). Multi-line payloads use repeated "data:" lines.
 */
export function parseSSEBuffer(buffer) {
  const normalized = buffer.replace(/\r\n/g, '\n');
  const events = [];
  const parts = normalized.split('\n\n');
  const remainder = parts.pop() ?? '';

  for (const raw of parts) {
    if (!raw.trim()) continue;
    events.push(stripNestedDataPrefix(extractEventData(raw)));
  }

  return { events, remainder };
}

function extractEventData(raw) {
  const lines = raw.split('\n');
  const dataLines = [];
  let hasDataPrefix = false;

  for (const line of lines) {
    if (line.startsWith('data:')) {
      hasDataPrefix = true;
      dataLines.push(readDataLine(line));
    } else if (!line.startsWith(':')) {
      dataLines.push(line);
    }
  }

  if (hasDataPrefix) {
    return dataLines.join('\n');
  }
  return raw;
}

/** SSE spec: optional single space after "data:" */
function readDataLine(line) {
  let value = line.slice(5);
  if (value.startsWith(' ')) {
    value = value.slice(1);
  }
  return value;
}

/** Undo accidental double-encoding from older backend (data:data:foo). */
export function stripNestedDataPrefix(text) {
  let value = text;
  while (value.startsWith('data:')) {
    value = readDataLine(value);
  }
  return value;
}
