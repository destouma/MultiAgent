const esbuild = require('esbuild');
const fs = require('node:fs');
const path = require('node:path');

const watch = process.argv.includes('--watch');

// A packaged .vsix can only contain files from inside this directory, so
// personaRegistry.ts's bundled-personas lookup (extensionUri/personas) needs
// a real copy here - the repo-root personas/ it falls back to in dev only
// exists in a full checkout, not inside an installed extension.
function copyPersonas() {
  const srcDir = path.join(__dirname, '..', 'personas');
  const destDir = path.join(__dirname, 'personas');
  fs.mkdirSync(destDir, { recursive: true });
  for (const file of fs.readdirSync(srcDir)) {
    if (!file.endsWith('.json')) continue;
    fs.copyFileSync(path.join(srcDir, file), path.join(destDir, file));
  }
}

const extensionConfig = {
  entryPoints: ['src/extension.ts'],
  bundle: true,
  platform: 'node',
  format: 'cjs',
  target: 'node18',
  external: ['vscode'],
  outfile: 'dist/extension.js',
  sourcemap: true,
};

const webviewConfig = {
  entryPoints: ['webview/main.ts'],
  bundle: true,
  platform: 'browser',
  format: 'iife',
  target: 'es2020',
  outfile: 'dist/webview.js',
  sourcemap: true,
};

const cssConfig = {
  entryPoints: ['webview/main.css'],
  bundle: true,
  outfile: 'dist/webview.css',
};

async function run() {
  copyPersonas();

  const contexts = await Promise.all(
    [extensionConfig, webviewConfig, cssConfig].map((config) => esbuild.context(config)),
  );

  if (watch) {
    await Promise.all(contexts.map((ctx) => ctx.watch()));
    console.log('[esbuild] watching…');
  } else {
    for (const ctx of contexts) {
      await ctx.rebuild();
      await ctx.dispose();
    }
  }
}

run().catch((error) => {
  console.error(error);
  process.exit(1);
});
