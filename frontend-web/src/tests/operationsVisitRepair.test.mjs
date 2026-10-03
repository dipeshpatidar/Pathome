import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const repairServiceSource = await readFile(new URL('../services/operationsVisitRepairService.ts', import.meta.url), 'utf8');
const previewImplementation = repairServiceSource.match(/async recommendations\([\s\S]*?\n  },\n  approve/);

test('repair option preview uses one recommendation request without reopening the session', () => {
  assert.ok(previewImplementation);
  assert.doesNotMatch(previewImplementation[0], /\/reopen|\.reopen\(/);
  assert.match(previewImplementation[0], /return request<VisitRecommendationView>[\s\S]*visit-sessions\/\$\{sessionId\}\/recommendations/);
  assert.match(previewImplementation[0], /expectedSessionVersion\s*\n\s*\}/);
});

test('preview errors cannot trigger a second mutating request', () => {
  assert.ok(previewImplementation);
  assert.equal((previewImplementation[0].match(/request<VisitRecommendationView>/g) ?? []).length, 1);
  assert.doesNotMatch(previewImplementation[0], /reopen/);
});
