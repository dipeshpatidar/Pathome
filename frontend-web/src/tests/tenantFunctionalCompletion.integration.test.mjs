import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const read = path => readFileSync(new URL(path, import.meta.url), 'utf8');
const dashboard = read('../components/TenantDashboard.tsx');
const home = read('../components/Home.tsx');
const detail = read('../components/PublicPropertyDetail.tsx');
const notificationDrawer = read('../components/NotificationCenterDrawer.tsx');
const requestModal = read('../components/VisitRequestModal.tsx');

test('Explore, Quick View, and Property Detail reach the real idempotent request flow', () => {
  assert.match(dashboard, /onRequestVisit=\{handleTenantRequestVisit\}/);
  assert.match(dashboard, /return tenantRequestStatusForProperty\(visibleHistory\.requests, propertyId\)/);
  assert.doesNotMatch(dashboard, /visibleHistory\.hasMore \? 'UNKNOWN'/);
  assert.match(home, /requestStatus: tenantRequestStatusForProperty\(page\.requests, publicPropertyId\)/);
  assert.doesNotMatch(home, /visitRequestStatusIncomplete/);
  assert.match(detail, /onRequestVisit\(property\)/);
  assert.doesNotMatch(detail, /visitRequestStatusIncomplete/);
  assert.match(requestModal, /propertyService\.createVisitRequest\(property\.id/);
  assert.match(requestModal, /tenantVisitAcknowledgement\(result\.status, result\.created\)/);
});

test('Visit Sessions render only authoritative time, outcome, and start-code data', () => {
  assert.match(dashboard, /formatVisitTime\(visit\.scheduledAt, visit\.zoneId\)/);
  assert.match(dashboard, /outcome\?\.outcomeReportAvailable &&/);
  assert.match(dashboard, /tenantVisitOutcomeSummaryText\(outcome\)/);
  assert.match(dashboard, /tenantPropertyOutcomeLabel\(property\)/);
  assert.match(dashboard, /property\.reasonLabel/);
  assert.doesNotMatch(dashboard, /property\.privateNote|outcome\.privateNote|remainingVisitCredits|freeVisitBalance/);
  assert.match(dashboard, /visitExecutionService\.issueStartCode\(sessionId\)/);
  assert.match(dashboard, /secondsUntilVisitCodeTime\(code\.nextRequestAt, visitClock\)/);
  assert.match(dashboard, /disabled=\{isPending \|\| \(nextCodeRequestIn !== null && nextCodeRequestIn > 0\)\}/);
  assert.match(dashboard, /visit\.tenantConfirmationState === 'PENDING'/);
  assert.match(dashboard, /'ACCEPT_RESCHEDULE'/);
  assert.match(dashboard, /'REJECT_RESCHEDULE'/);
  assert.match(dashboard, /Ground Executive arrived/);
  assert.match(dashboard, /Reported visit ETA:/);
  assert.doesNotMatch(dashboard, /Your confirmed ETA:/);
  assert.doesNotMatch(dashboard, /live GPS|live tracking|live GE map/i);
});

test('notification entry and same-user auth refresh Visit data without changing routes', () => {
  assert.match(notificationDrawer, /item\.category === 'VISIT_SESSION' && item\.targetRole === 'TENANT'/);
  assert.match(notificationDrawer, /dispatchEvent\(new Event\('pathome_tenant_visit_notification_opened'\)\)/);
  assert.match(dashboard, /addEventListener\('pathome_tenant_visit_notification_opened', refreshIdentity\)/);
  assert.match(dashboard, /removeEventListener\('pathome_tenant_visit_notification_opened', refreshIdentity\)/);
  assert.match(dashboard, /addEventListener\('pathome_auth_changed', refreshIdentity\)/);
  assert.match(dashboard, /setTenantSessionPage\(0\);\s*setTenantSessionsReload\(value => value \+ 1\)/);
  assert.match(dashboard, /location\.hash === '#visit-history' \|\| location\.hash\.startsWith\('#visit-session-'\)/);
  assert.match(dashboard, /location\.hash === '#saved-homes-title'/);
});
