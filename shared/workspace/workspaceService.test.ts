import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { WorkspaceError, WorkspaceService } from './workspaceService';

describe('WorkspaceService', () => {
  let root: string;
  let workspace: WorkspaceService;

  beforeEach(() => {
    root = fs.mkdtempSync(path.join(os.tmpdir(), 'ma-workspace-'));
    workspace = new WorkspaceService();
  });

  afterEach(() => {
    fs.rmSync(root, { recursive: true, force: true });
  });

  describe('resolveSafe', () => {
    it('resolves the root itself for "."', () => {
      expect(workspace.resolveSafe(root, '.')).toBe(path.resolve(root));
    });

    it('resolves a nested relative path inside the workspace', () => {
      const resolved = workspace.resolveSafe(root, path.join('sub', 'dir', 'file.txt'));
      expect(resolved).toBe(path.resolve(root, 'sub', 'dir', 'file.txt'));
    });

    it('rejects a relative path that escapes the workspace via ..', () => {
      expect(() => workspace.resolveSafe(root, path.join('..', 'outside.txt'))).toThrow(
        WorkspaceError,
      );
    });

    it('rejects a deeply nested .. that still escapes the workspace', () => {
      expect(() =>
        workspace.resolveSafe(root, path.join('a', 'b', '..', '..', '..', 'outside.txt')),
      ).toThrow(WorkspaceError);
    });

    it('rejects an absolute path outside the workspace', () => {
      const outsideDir = fs.mkdtempSync(path.join(os.tmpdir(), 'ma-outside-'));
      try {
        expect(() => workspace.resolveSafe(root, path.join(outsideDir, 'x.txt'))).toThrow(
          WorkspaceError,
        );
      } finally {
        fs.rmSync(outsideDir, { recursive: true, force: true });
      }
    });

    it('rejects a symlink inside the workspace that points outside it', (ctx) => {
      const outsideDir = fs.mkdtempSync(path.join(os.tmpdir(), 'ma-outside-'));
      const linkPath = path.join(root, 'escape-link');
      try {
        fs.symlinkSync(outsideDir, linkPath, 'junction');
      } catch {
        // Creating symlinks/junctions can require elevated privileges on
        // some CI/dev machines; skip rather than fail the suite for that.
        ctx.skip();
        return;
      }

      try {
        expect(() => workspace.resolveSafe(root, path.join('escape-link', 'x.txt'))).toThrow(
          WorkspaceError,
        );
      } finally {
        fs.rmSync(outsideDir, { recursive: true, force: true });
      }
    });
  });

  describe('read/write/list/delete', () => {
    it('writes then reads back a text file, creating parent folders as needed', () => {
      const result = workspace.writeFile(root, path.join('nested', 'note.txt'), 'hello world');
      expect(result).toContain('note.txt');
      expect(workspace.readFile(root, path.join('nested', 'note.txt'))).toBe('hello world');
    });

    it('lists directory entries with a dir/file marker', () => {
      workspace.writeFile(root, 'a.txt', 'content');
      fs.mkdirSync(path.join(root, 'sub'));
      const listing = workspace.listDir(root, '.');
      expect(listing).toContain('file\ta.txt');
      expect(listing).toContain('dir\tsub');
    });

    it('throws when reading a file that does not exist', () => {
      expect(() => workspace.readFile(root, 'missing.txt')).toThrow(WorkspaceError);
    });

    it('deletes a file but refuses to delete a directory', () => {
      workspace.writeFile(root, 'a.txt', 'content');
      workspace.deleteFile(root, 'a.txt');
      expect(fs.existsSync(path.join(root, 'a.txt'))).toBe(false);

      fs.mkdirSync(path.join(root, 'sub'));
      expect(() => workspace.deleteFile(root, 'sub')).toThrow(WorkspaceError);
    });

    it('rejects writes over the size limit', () => {
      const tooLarge = 'x'.repeat(500_001);
      expect(() => workspace.writeFile(root, 'big.txt', tooLarge)).toThrow(WorkspaceError);
    });
  });

  describe('executeTool', () => {
    it('dispatches to the matching operation by tool name', () => {
      workspace.executeTool(root, 'write_file', { path: 'via-tool.txt', content: 'hi' });
      expect(fs.readFileSync(path.join(root, 'via-tool.txt'), 'utf8')).toBe('hi');
    });

    it('throws for an unknown tool name', () => {
      expect(() => workspace.executeTool(root, 'not_a_real_tool', {})).toThrow(WorkspaceError);
    });
  });

  describe('tryReadFile', () => {
    it('returns the file content when the file exists', () => {
      workspace.writeFile(root, 'notes.txt', 'hello world');
      expect(workspace.tryReadFile(root, 'notes.txt')).toBe('hello world');
    });

    it('returns null when the file does not exist, instead of throwing', () => {
      expect(workspace.tryReadFile(root, 'missing.txt')).toBeNull();
    });

    it('returns null for a directory rather than throwing', () => {
      fs.mkdirSync(path.join(root, 'a-dir'));
      expect(workspace.tryReadFile(root, 'a-dir')).toBeNull();
    });

    it('returns null instead of throwing when the path escapes the workspace root', () => {
      expect(workspace.tryReadFile(root, path.join('..', 'outside.txt'))).toBeNull();
    });
  });
});
