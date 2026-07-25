import type { ReactNode } from 'react';
import { CodeBlock } from './CodeBlock';

type Segment = { type: 'text'; value: string } | { type: 'code'; language: string; value: string };

function splitFencedCode(content: string): Segment[] {
  const segments: Segment[] = [];
  const fence = /```([^\n`]*)\n?([\s\S]*?)```/g;
  let lastIndex = 0;
  let match: RegExpExecArray | null;

  while ((match = fence.exec(content)) !== null) {
    if (match.index > lastIndex) {
      segments.push({ type: 'text', value: content.slice(lastIndex, match.index) });
    }
    segments.push({
      type: 'code',
      language: (match[1] || '').trim(),
      value: match[2].replace(/\n$/, ''),
    });
    lastIndex = match.index + match[0].length;
  }

  if (lastIndex < content.length) {
    segments.push({ type: 'text', value: content.slice(lastIndex) });
  }

  return segments.length ? segments : [{ type: 'text', value: content }];
}

function renderInline(text: string): ReactNode[] {
  const parts: ReactNode[] = [];
  const inline = /`([^`]+)`/g;
  let lastIndex = 0;
  let match: RegExpExecArray | null;
  let key = 0;

  while ((match = inline.exec(text)) !== null) {
    if (match.index > lastIndex) {
      parts.push(text.slice(lastIndex, match.index));
    }
    parts.push(
      <code key={`inline-${key}`} className="inline-code">
        {match[1]}
      </code>,
    );
    key += 1;
    lastIndex = match.index + match[0].length;
  }

  if (lastIndex < text.length) {
    parts.push(text.slice(lastIndex));
  }

  return parts.length ? parts : [text];
}

type Props = {
  content: string;
  streaming?: boolean;
};

export function MessageContent({ content, streaming }: Props) {
  const segments = splitFencedCode(content);

  return (
    <div className="message-content">
      {segments.map((segment, index) => {
        if (segment.type === 'code') {
          return (
            <CodeBlock key={`code-${index}`} code={segment.value} language={segment.language} />
          );
        }
        return (
          <span key={`text-${index}`} className="message-text">
            {renderInline(segment.value)}
          </span>
        );
      })}
      {streaming ? <span className="cursor">▋</span> : null}
    </div>
  );
}
