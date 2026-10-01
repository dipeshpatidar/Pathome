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

test('production public and owner property error states keep Retry and a recovery action', async () => {
  const output = await build({
    stdin: {
      contents: `import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { PropertyDetailErrorState } from '../components/PropertyDetailErrorState';
import { LessorListingErrorState } from '../components/LessorListingErrorState';
export const renderPublic = () => renderToStaticMarkup(<PropertyDetailErrorState
  error="Property unavailable" onRetry={() => {}} onBack={() => {}} />);
export const renderOwner = () => renderToStaticMarkup(<LessorListingErrorState
  error="Owner property unavailable" onRetry={() => {}} onBack={() => {}} />);`,
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
  const publicMarkup = bundled.exports.renderPublic();
  assert.match(publicMarkup, /Property unavailable/);
  assert.match(publicMarkup, />Retry</);
  assert.match(publicMarkup, />Back to discovery</);
  assert.equal((publicMarkup.match(/min-h-11/g) || []).length, 2);

  const ownerMarkup = bundled.exports.renderOwner();
  assert.match(ownerMarkup, /Owner property unavailable/);
  assert.match(ownerMarkup, />Retry</);
  assert.match(ownerMarkup, />Back to My Properties</);
  assert.equal((ownerMarkup.match(/min-h-11/g) || []).length, 2);
});
