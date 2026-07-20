export type ParsedAction = {
  name: 'list_dir' | 'read_file' | 'write_file' | 'delete_file' | 'generate_image';
  args: Record<string, unknown>;
};

/**
 * Fallback parser for models without native tool-calling: extracts
 * <list_dir/>, <read_file/>, <write_file>, <delete_file/>, and
 * <generate_image/> action tags from a completion's raw text content.
 */
export function parseActionTags(content: string): ParsedAction[] {
  const actions: ParsedAction[] = [];

  const selfClosing = /<(list_dir|read_file|delete_file)\s+path="([^"]+)"\s*\/>/gi;
  let match: RegExpExecArray | null;
  while ((match = selfClosing.exec(content)) !== null) {
    actions.push({
      name: match[1] as ParsedAction['name'],
      args: { path: match[2] },
    });
  }

  const writeRe = /<write_file\s+path="([^"]+)"\s*>([\s\S]*?)<\/write_file>/gi;
  while ((match = writeRe.exec(content)) !== null) {
    actions.push({
      name: 'write_file',
      args: { path: match[1], content: match[2] },
    });
  }

  const imageRe = /<generate_image\s+([^>]+?)\s*\/>/gi;
  while ((match = imageRe.exec(content)) !== null) {
    const attrs = match[1];
    const prompt = /prompt="([^"]+)"/i.exec(attrs)?.[1] ?? '';
    const pathAttr = /path="([^"]+)"/i.exec(attrs)?.[1] ?? 'images/generated.png';
    const size = /size="([^"]+)"/i.exec(attrs)?.[1];
    if (prompt) {
      actions.push({
        name: 'generate_image',
        args: { prompt, path: pathAttr, ...(size ? { size } : {}) },
      });
    }
  }

  return actions;
}
