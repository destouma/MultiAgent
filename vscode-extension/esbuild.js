const esbuild = require('esbuild');

const watch = process.argv.includes('--watch');

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
