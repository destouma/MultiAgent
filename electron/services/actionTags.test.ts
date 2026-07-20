import { describe, expect, it } from 'vitest';
import { parseActionTags } from './actionTags';

describe('parseActionTags', () => {
  it('returns an empty array when there are no action tags', () => {
    expect(parseActionTags('just a normal reply, no tools needed')).toEqual([]);
  });

  it('parses self-closing list_dir, read_file, and delete_file tags', () => {
    const content = [
      '<list_dir path="." />',
      '<read_file path="src/index.ts" />',
      '<delete_file path="tmp.txt" />',
    ].join('\n');

    expect(parseActionTags(content)).toEqual([
      { name: 'list_dir', args: { path: '.' } },
      { name: 'read_file', args: { path: 'src/index.ts' } },
      { name: 'delete_file', args: { path: 'tmp.txt' } },
    ]);
  });

  it('parses write_file with its full inner content, including multiple lines', () => {
    const content = '<write_file path="notes.md">line one\nline two</write_file>';

    expect(parseActionTags(content)).toEqual([
      { name: 'write_file', args: { path: 'notes.md', content: 'line one\nline two' } },
    ]);
  });

  it('parses generate_image with prompt, path, and size', () => {
    const content = '<generate_image prompt="a red circle" path="images/out.png" size="256x256" />';

    expect(parseActionTags(content)).toEqual([
      {
        name: 'generate_image',
        args: { prompt: 'a red circle', path: 'images/out.png', size: '256x256' },
      },
    ]);
  });

  it('defaults generate_image path when omitted and omits size when absent', () => {
    const content = '<generate_image prompt="a blue square" />';

    expect(parseActionTags(content)).toEqual([
      { name: 'generate_image', args: { prompt: 'a blue square', path: 'images/generated.png' } },
    ]);
  });

  it('ignores generate_image tags with no prompt', () => {
    const content = '<generate_image path="images/out.png" />';
    expect(parseActionTags(content)).toEqual([]);
  });

  it('groups results by tag kind (self-closing, then write_file, then generate_image) in source order within each group', () => {
    const content = [
      '<list_dir path="." />',
      '<write_file path="a.txt">hello</write_file>',
      '<read_file path="b.txt" />',
      '<generate_image prompt="cat" path="img.png" size="256x256" />',
    ].join('\n');

    expect(parseActionTags(content)).toEqual([
      { name: 'list_dir', args: { path: '.' } },
      { name: 'read_file', args: { path: 'b.txt' } },
      { name: 'write_file', args: { path: 'a.txt', content: 'hello' } },
      { name: 'generate_image', args: { prompt: 'cat', path: 'img.png', size: '256x256' } },
    ]);
  });
});
