import { app, dialog, type BrowserWindow } from 'electron';
import fs from 'node:fs';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import type { GeneratedImageInfo, GenerateImageRequest } from '../../../shared/types';
import { ProviderError, type LlmClient } from '../../../shared/llm/types';
import { WorkspaceError, WorkspaceService } from '../../../shared/workspace/workspaceService';
import { emitModelStatus } from './modelStatus';

export class ImageService {
  private workspace = new WorkspaceService();

  constructor(
    private getClientFor: (serverId: string | null) => LlmClient,
    private getImageModel: () => string,
    private getWindow: () => BrowserWindow | null,
  ) {}

  imagesDir(): string {
    const dir = path.join(app.getPath('userData'), 'images');
    fs.mkdirSync(dir, { recursive: true });
    return dir;
  }

  absolutePath(fileName: string): string {
    const safe = path.basename(fileName);
    return path.join(this.imagesDir(), safe);
  }

  async generate(
    request: GenerateImageRequest,
    workspaceRoot: string | null,
    serverId: string | null = null,
  ): Promise<GeneratedImageInfo> {
    const client = this.getClientFor(serverId);
    if (!client.supportsImageGeneration()) {
      throw new ProviderError(
        'unsupported',
        'The current provider does not support image generation. Switch to an OpenAI-compatible provider in Settings to use this feature.',
      );
    }

    const model = request.model || this.getImageModel();
    if (!model) {
      throw new ProviderError(
        'model_not_loaded',
        'No image model selected. Choose an image model in the generator.',
      );
    }

    await client.ensureModelLoaded(model, {
      onStatus: (message) => emitModelStatus(this.getWindow, model, message),
    });

    const size = request.size || '512x512';
    const b64 = await client.generateImage({
      prompt: request.prompt,
      model,
      size,
      steps: request.steps,
      cfgScale: request.cfgScale,
      seed: request.seed,
    });

    const id = randomUUID();
    const fileName = `${id}.png`;
    const absolute = this.absolutePath(fileName);
    fs.writeFileSync(absolute, Buffer.from(b64, 'base64'));

    let workspaceRelativePath: string | null = null;
    if (workspaceRoot) {
      const rel = request.saveToWorkspacePath?.trim() || `images/${fileName}`;
      this.workspace.writeBinary(workspaceRoot, rel, Buffer.from(b64, 'base64'));
      workspaceRelativePath = rel.replace(/\\/g, '/');
    }

    return {
      id,
      conversationId: request.conversationId,
      prompt: request.prompt,
      model,
      size,
      fileName,
      workspaceRelativePath,
      createdAt: Date.now(),
    };
  }

  getDataUrl(fileName: string): string {
    const absolute = this.absolutePath(fileName);
    if (!fs.existsSync(absolute)) {
      throw new ProviderError('unknown', 'Image file not found');
    }
    const buffer = fs.readFileSync(absolute);
    return `data:image/png;base64,${buffer.toString('base64')}`;
  }

  async download(fileName: string, defaultName?: string): Promise<string | null> {
    const absolute = this.absolutePath(fileName);
    if (!fs.existsSync(absolute)) {
      throw new ProviderError('unknown', 'Image file not found');
    }

    const win = this.getWindow();
    const result = win
      ? await dialog.showSaveDialog(win, {
          title: 'Download image',
          defaultPath: defaultName || fileName,
          filters: [{ name: 'PNG image', extensions: ['png'] }],
        })
      : await dialog.showSaveDialog({
          title: 'Download image',
          defaultPath: defaultName || fileName,
          filters: [{ name: 'PNG image', extensions: ['png'] }],
        });

    if (result.canceled || !result.filePath) return null;
    fs.copyFileSync(absolute, result.filePath);
    return result.filePath;
  }

  saveToWorkspace(fileName: string, workspaceRoot: string, relativePath?: string): string {
    const absolute = this.absolutePath(fileName);
    if (!fs.existsSync(absolute)) {
      throw new ProviderError('unknown', 'Image file not found');
    }
    const buffer = fs.readFileSync(absolute);
    const rel = relativePath?.trim() || `images/${path.basename(fileName)}`;
    this.workspace.writeBinary(workspaceRoot, rel, buffer);
    return rel.replace(/\\/g, '/');
  }

  async generateForTool(input: {
    prompt: string;
    workspaceRoot: string;
    relativePath?: string;
    model?: string;
    size?: string;
    conversationId: string;
    serverId?: string | null;
  }): Promise<string> {
    const info = await this.generate(
      {
        conversationId: input.conversationId,
        prompt: input.prompt,
        model: input.model,
        size: input.size,
        saveToWorkspacePath: input.relativePath || `images/gen-${Date.now()}.png`,
      },
      input.workspaceRoot,
      input.serverId ?? null,
    );
    return `Generated image saved to workspace as ${info.workspaceRelativePath} (cache id ${info.fileName})`;
  }
}

export function assertWorkspaceRoot(root: string | null): string {
  if (!root) {
    throw new WorkspaceError('No workspace folder bound to this chat');
  }
  return root;
}
