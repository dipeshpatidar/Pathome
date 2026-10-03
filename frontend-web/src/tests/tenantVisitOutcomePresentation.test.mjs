import test from 'node:test';
import assert from 'node:assert/strict';
import {
  tenantOutcomeSummaryLabel,
  tenantPropertyOutcomeLabel,
  tenantVisitOutcomeStatusLabel,
  tenantVisitOutcomeSummaryText
} from '../utils/tenantVisitOutcomePresentation.ts';

const outcome = (overrides = {}) => ({
  sessionId: 12, lifecycle: 'DETAILS_PENDING', outcomeSummary: null, outcomeReportAvailable: true,
  scheduledAt: null, startedAt: null, finishedAt: '2026-01-01T00:00:00Z', city: 'City', locality: null,
  totalProperties: 2, viewedProperties: null, lastUpdatedAt: null, properties: [], ...overrides
});

test('tenant history uses truthful human states for pending, legacy, and all summary outcomes', () => {
  assert.equal(tenantVisitOutcomeStatusLabel(outcome()), 'Visit ended · details pending');
  assert.equal(tenantVisitOutcomeSummaryText(outcome()), 'Visit ended; details are still being completed.');
  assert.equal(tenantVisitOutcomeStatusLabel(outcome({ lifecycle: 'RESULTS_NOT_RECORDED' })), 'Results not recorded');
  assert.equal(tenantVisitOutcomeSummaryText(outcome({ lifecycle: 'RESULTS_NOT_RECORDED' })), 'Results were not recorded for this visit.');
  assert.equal(tenantOutcomeSummaryLabel('ALL_VIEWED'), 'All properties viewed');
  assert.equal(tenantOutcomeSummaryLabel('PARTLY_VIEWED'), 'Some properties viewed');
  assert.equal(tenantOutcomeSummaryLabel('NONE_VIEWED'), 'No properties viewed');
});

test('viewed counts appear only when the server marks final outcomes available', () => {
  assert.equal(tenantVisitOutcomeSummaryText(outcome({
    lifecycle: 'PARTIALLY_COMPLETED', viewedProperties: 1, totalProperties: 3
  })), '1 of 3 properties viewed.');
  assert.equal(tenantVisitOutcomeSummaryText(outcome({ lifecycle: 'IN_PROGRESS', viewedProperties: null })),
    'Property results will appear after visit details are recorded.');
});

test('tenant property labels translate pending and final states without rendering internal enums or notes', () => {
  const base = { position: 1, title: 'Home', address: 'Street', city: 'City', sector: 'Area', reasonLabel: null,
    attribution: null, correctedAt: null };
  assert.equal(tenantPropertyOutcomeLabel({ ...base, outcome: 'PENDING' }), 'Pending');
  assert.equal(tenantPropertyOutcomeLabel({ ...base, outcome: 'VIEWED' }), 'Viewed');
  assert.equal(tenantPropertyOutcomeLabel({ ...base, outcome: 'NOT_VIEWED' }), 'Not viewed');
  assert.equal(tenantVisitOutcomeStatusLabel(null, 'PROVISIONAL_NO_SHOW'), 'Under attendance review');
});
