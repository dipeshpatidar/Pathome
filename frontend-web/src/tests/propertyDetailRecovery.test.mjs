import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { build } from 'esbuild';
import { resolvePropertyDetailBackTarget } from '../utils/propertyDetailRecovery.ts';

test('property detail uses router history only when an in-app entry is available', () => {
  assert.equal(resolvePropertyDetailBackTarget({ idx: 2 }), -1);
  assert.equal(resolvePropertyDetailBackTarget({ idx: 1 }), -1);
  assert.equal(resolvePropertyDetailBackTarget({ idx: 0 }), '/');
  assert.equal(resolvePropertyDetailBackTarget(null), '/');
  assert.equal(resolvePropertyDetailBackTarget({ idx: -1 }), '/');
  assert.equal(resolvePropertyDetailBackTarget({ idx: '2' }), '/');
});

test('production property detail error state keeps retry and a discovery escape', async () => {
  const output = await build({
    stdin: {
      contents: `import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { PropertyDetailErrorState } from '../components/PropertyDetailErrorState';
export const render = () => renderToStaticMarkup(<PropertyDetailErrorState
  error="Property unavailable" onRetry={() => {}} onBack={() => {}} />);`,
      resolveDir: new URL('.', import.meta.url).pathname,
      sourcefile: 'property-detail-error-harness.tsx',
      loader: 'tsx'
    },
    bundle: true, platform: 'node', format: 'cjs', write: false,
    external: ['react', 'react-dom/server', 'lucide-react']
  });
  const bundled = { exports: {} };
  new Function('require', 'module', 'exports', output.outputFiles[0].text)(
    createRequire(import.meta.url), bundled, bundled.exports);
  const markup = bundled.exports.render();
  assert.match(markup, /Property unavailable/);
  assert.match(markup, />Retry</);
  assert.match(markup, />Back to discovery</);
  assert.equal((markup.match(/min-h-11/g) || []).length, 2);
});
