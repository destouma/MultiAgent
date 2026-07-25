import { app } from 'electron';
import fs from 'node:fs';
import path from 'node:path';
import type { Persona } from '../../../shared/types';

function personaDirs(): string[] {
  const dirs: string[] = [];

  // Packaged: extraResources/personas
  if (app.isPackaged) {
    dirs.push(path.join(process.resourcesPath, 'personas'));
  }

  // Dev: repo root /personas (one level up from the desktop/ app root)
  dirs.push(path.join(app.getAppPath(), '..', 'personas'));
  dirs.push(path.join(process.cwd(), '..', 'personas'));

  return dirs;
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

export class PersonaRegistry {
  private personas: Persona[] = [];

  load(): Persona[] {
    const byId = new Map<string, Persona>();

    for (const dir of personaDirs()) {
      for (const persona of readPersonasFromDir(dir)) {
        byId.set(persona.id, persona);
      }
    }

    // Stable order for built-ins
    const preferred = ['general', 'researcher', 'coder', 'critic'];
    const loaded = [...byId.values()];
    loaded.sort((a, b) => {
      const ai = preferred.indexOf(a.id);
      const bi = preferred.indexOf(b.id);
      if (ai === -1 && bi === -1) return a.name.localeCompare(b.name);
      if (ai === -1) return 1;
      if (bi === -1) return -1;
      return ai - bi;
    });

    this.personas = loaded.length
      ? loaded
      : [
          {
            id: 'general',
            name: 'General',
            description: 'Helpful all-purpose assistant',
            systemPrompt:
              'You are a helpful, concise assistant. Answer clearly and ask clarifying questions when needed.',
            color: '#0F766E',
          },
        ];

    return this.personas;
  }

  list(): Persona[] {
    if (!this.personas.length) {
      return this.load();
    }
    return this.personas;
  }

  get(id: string): Persona | undefined {
    return this.list().find((persona) => persona.id === id);
  }
}
