import fs from 'node:fs';
import path from 'node:path';

const IGNORE_DIRS = new Set([
  'node_modules',
  '.git',
  'dist',
  'dist-electron',
  'release',
  '.next',
  'coverage',
  '__pycache__',
  '.venv',
  'venv',
]);

const MAX_TREE_ENTRIES = 400;
const MAX_READ_BYTES = 120_000;
const MAX_WRITE_BYTES = 500_000;

export class WorkspaceError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'WorkspaceError';
  }
}

export class WorkspaceService {
  resolveSafe(workspaceRoot: string, relativePath = '.'): string {
    const root = path.resolve(workspaceRoot);
    const target = path.resolve(root, relativePath || '.');
    const relative = path.relative(root, target);
    if (relative.startsWith('..') || path.isAbsolute(relative)) {
      throw new WorkspaceError('Path escapes the workspace folder');
    }

    // path.resolve alone doesn't follow symlinks: a link inside the workspace
    // that points outside it would otherwise pass the check above. Walk up to
    // the nearest existing ancestor and compare real (symlink-resolved) paths.
    let realRoot: string;
    try {
      realRoot = fs.realpathSync(root);
    } catch {
      throw new WorkspaceError('Workspace folder does not exist');
    }

    let existingAncestor = target;
    while (!fs.existsSync(existingAncestor)) {
      const parent = path.dirname(existingAncestor);
      if (parent === existingAncestor) break;
      existingAncestor = parent;
    }
    const realExistingAncestor = fs.realpathSync(existingAncestor);
    const realRelative = path.relative(realRoot, realExistingAncestor);
    if (realRelative.startsWith('..') || path.isAbsolute(realRelative)) {
      throw new WorkspaceError('Path escapes the workspace folder');
    }

    return target;
  }

  buildTree(workspaceRoot: string): string {
    const root = path.resolve(workspaceRoot);
    if (!fs.existsSync(root) || !fs.statSync(root).isDirectory()) {
      throw new WorkspaceError('Workspace folder does not exist');
    }

    const lines: string[] = [path.basename(root) + '/'];
    let count = 0;

    const walk = (dir: string, prefix: string) => {
      if (count >= MAX_TREE_ENTRIES) return;
      let entries: fs.Dirent[];
      try {
        entries = fs.readdirSync(dir, { withFileTypes: true });
      } catch {
        return;
      }

      entries.sort((a, b) => {
        if (a.isDirectory() !== b.isDirectory()) return a.isDirectory() ? -1 : 1;
        return a.name.localeCompare(b.name);
      });

      for (const entry of entries) {
        if (count >= MAX_TREE_ENTRIES) {
          lines.push(`${prefix}… (truncated)`);
          return;
        }
        if (entry.name.startsWith('.') && entry.name !== '.env.example') continue;
        if (entry.isDirectory() && IGNORE_DIRS.has(entry.name)) continue;

        count += 1;
        const rel = path.relative(root, path.join(dir, entry.name)).replace(/\\/g, '/');
        if (entry.isDirectory()) {
          lines.push(`${prefix}${rel}/`);
          walk(path.join(dir, entry.name), prefix);
        } else {
          lines.push(`${prefix}${rel}`);
        }
      }
    };

    walk(root, '');
    return lines.join('\n');
  }

  listDir(workspaceRoot: string, relativePath = '.'): string {
    const target = this.resolveSafe(workspaceRoot, relativePath);
    if (!fs.existsSync(target) || !fs.statSync(target).isDirectory()) {
      throw new WorkspaceError(`Not a directory: ${relativePath}`);
    }
    const entries = fs.readdirSync(target, { withFileTypes: true });
    return entries
      .filter((entry) => !(entry.isDirectory() && IGNORE_DIRS.has(entry.name)))
      .map((entry) => `${entry.isDirectory() ? 'dir' : 'file'}\t${entry.name}`)
      .join('\n');
  }

  readFile(workspaceRoot: string, relativePath: string): string {
    const target = this.resolveSafe(workspaceRoot, relativePath);
    if (!fs.existsSync(target) || !fs.statSync(target).isFile()) {
      throw new WorkspaceError(`File not found: ${relativePath}`);
    }
    const stat = fs.statSync(target);
    if (stat.size > MAX_READ_BYTES) {
      throw new WorkspaceError(
        `File too large to read (${stat.size} bytes). Max is ${MAX_READ_BYTES}.`,
      );
    }
    return fs.readFileSync(target, 'utf8');
  }

  /** Like readFile, but returns null instead of throwing when the file is missing, not a file, or too large to read — used for checkpoint/diff snapshots where "doesn't exist" is a meaningful, expected state rather than an error. */
  tryReadFile(workspaceRoot: string, relativePath: string): string | null {
    try {
      return this.readFile(workspaceRoot, relativePath);
    } catch {
      return null;
    }
  }

  writeFile(workspaceRoot: string, relativePath: string, content: string): string {
    if (Buffer.byteLength(content, 'utf8') > MAX_WRITE_BYTES) {
      throw new WorkspaceError(`Write too large. Max is ${MAX_WRITE_BYTES} bytes.`);
    }
    const target = this.resolveSafe(workspaceRoot, relativePath);
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.writeFileSync(target, content, 'utf8');
    return `Wrote ${relativePath.replace(/\\/g, '/')} (${Buffer.byteLength(content, 'utf8')} bytes)`;
  }

  writeBinary(workspaceRoot: string, relativePath: string, data: Buffer): string {
    if (data.byteLength > MAX_WRITE_BYTES * 4) {
      throw new WorkspaceError(`Binary write too large (${data.byteLength} bytes).`);
    }
    const target = this.resolveSafe(workspaceRoot, relativePath);
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.writeFileSync(target, data);
    return `Wrote ${relativePath.replace(/\\/g, '/')} (${data.byteLength} bytes)`;
  }

  deleteFile(workspaceRoot: string, relativePath: string): string {
    const target = this.resolveSafe(workspaceRoot, relativePath);
    if (!fs.existsSync(target)) {
      throw new WorkspaceError(`Path not found: ${relativePath}`);
    }
    const stat = fs.statSync(target);
    if (stat.isDirectory()) {
      throw new WorkspaceError('Deleting directories is not allowed');
    }
    fs.unlinkSync(target);
    return `Deleted ${relativePath.replace(/\\/g, '/')}`;
  }

  executeTool(workspaceRoot: string, name: string, args: Record<string, unknown>): string {
    switch (name) {
      case 'list_dir':
        return this.listDir(workspaceRoot, String(args.path ?? '.'));
      case 'read_file':
        return this.readFile(workspaceRoot, String(args.path ?? ''));
      case 'write_file':
        return this.writeFile(workspaceRoot, String(args.path ?? ''), String(args.content ?? ''));
      case 'delete_file':
        return this.deleteFile(workspaceRoot, String(args.path ?? ''));
      case 'generate_image':
        throw new WorkspaceError(
          'generate_image must be executed by ImageService, not WorkspaceService',
        );
      default:
        throw new WorkspaceError(`Unknown tool: ${name}`);
    }
  }
}

export const workspaceTools = [
  {
    type: 'function' as const,
    function: {
      name: 'list_dir',
      description: 'List files and directories under a relative path in the workspace.',
      parameters: {
        type: 'object',
        properties: {
          path: {
            type: 'string',
            description: 'Relative directory path. Use "." for workspace root.',
          },
        },
      },
    },
  },
  {
    type: 'function' as const,
    function: {
      name: 'read_file',
      description: 'Read a UTF-8 text file from the workspace.',
      parameters: {
        type: 'object',
        properties: {
          path: { type: 'string', description: 'Relative file path' },
        },
        required: ['path'],
      },
    },
  },
  {
    type: 'function' as const,
    function: {
      name: 'write_file',
      description:
        'Create or overwrite a UTF-8 text file in the workspace. Creates parent folders as needed.',
      parameters: {
        type: 'object',
        properties: {
          path: { type: 'string', description: 'Relative file path' },
          content: { type: 'string', description: 'Full file contents to write' },
        },
        required: ['path', 'content'],
      },
    },
  },
  {
    type: 'function' as const,
    function: {
      name: 'delete_file',
      description: 'Delete a single file in the workspace (not directories).',
      parameters: {
        type: 'object',
        properties: {
          path: { type: 'string', description: 'Relative file path' },
        },
        required: ['path'],
      },
    },
  },
  {
    type: 'function' as const,
    function: {
      name: 'generate_image',
      description:
        'Generate an image with the local image model and save it into the workspace as a PNG.',
      parameters: {
        type: 'object',
        properties: {
          prompt: { type: 'string', description: 'Image generation prompt' },
          path: {
            type: 'string',
            description: 'Relative PNG path to write, e.g. images/hero.png',
          },
          size: {
            type: 'string',
            description: 'Optional size like 512x512 or 256x256',
          },
        },
        required: ['prompt'],
      },
    },
  },
];
