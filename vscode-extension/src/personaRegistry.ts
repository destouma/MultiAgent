import * as vscode from 'vscode';
import * as fs from 'node:fs';
import * as path from 'node:path';
import type { Persona } from '../../shared/types';

const FALLBACK_PERSONA: Persona = {
  id: 'general',
  name: 'General',
  description: 'Helpful all-purpose assistant',
  systemPrompt:
    'You are a helpful, concise assistant. Answer clearly and ask clarifying questions when needed.',
  color: '#0F766E',
};

const PREFERRED_ORDER = ['general', 'researcher', 'coder', 'critic'];

function personaDirs(extensionUri: vscode.Uri): string[] {
  return [
    // Bundled alongside the extension (esbuild.js copies repo-root personas/ here at build time)
    path.join(extensionUri.fsPath, 'personas'),
    // Dev (F5 from this repo checkout): repo root /personas, one level up from vscode-extension/
    path.join(extensionUri.fsPath, '..', 'personas'),
  ];
}

function readPersonasFromDir(dir: string): Persona[] {
  if (!fs.existsSync(dir)) return [];

  return fs
    .readdirSync(dir)
    .filter((file) => file.endsWith('.json'))
    .map((file) => {
      const raw = fs.readFileSync(path.join(dir, file), 'utf8');
      return JSON.parse(raw) as Persona;
    })
    .filter((persona) => persona.id && persona.name && persona.systemPrompt);
}

export function loadPersonas(extensionUri: vscode.Uri): Persona[] {
  const byId = new Map<string, Persona>();
  for (const dir of personaDirs(extensionUri)) {
    for (const persona of readPersonasFromDir(dir)) {
      byId.set(persona.id, persona);
    }
  }

  const loaded = [...byId.values()];
  loaded.sort((a, b) => {
    const ai = PREFERRED_ORDER.indexOf(a.id);
    const bi = PREFERRED_ORDER.indexOf(b.id);
    if (ai === -1 && bi === -1) return a.name.localeCompare(b.name);
    if (ai === -1) return 1;
    if (bi === -1) return -1;
    return ai - bi;
  });

  return loaded.length ? loaded : [FALLBACK_PERSONA];
}
