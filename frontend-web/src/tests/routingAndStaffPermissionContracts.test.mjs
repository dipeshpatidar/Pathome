import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const read = (path) => readFileSync(new URL(path, import.meta.url), 'utf8');
const routing = read('../components/operations/OperationsRoutingWorkspace.tsx');
const routingCss = read('../components/operations/operationsRouting.css');
const routingService = read('../services/operationsRoutingService.ts');
const staff = read('../components/OwnerStaffPermissions.tsx');
const staffCss = read('../components/ownerStaffPermissions.css');
const staffPresentation = read('../utils/staffPermissionPresentation.ts');
const admin = read('../components/MasterAdminDashboard.tsx');

test('routing confirmation uses authoritative versions and never assigns a person or books a Visit', () => {
  assert.match(routingService, /expectedRequestVersion/);
  assert.match(routingService, /expectedCityVersion/);
  assert.match(routingService, /expectedTeamVersion/);
  assert.match(routingService, /expectedRoutingTaskVersion/);
  assert.match(routingService, /reasonCode: 'ROUTING_EXCEPTION_TEAM_SELECTED'/);
  assert.match(routing, /no person is assigned and no Visit is booked/);
  const routeBody = routingService.slice(
    routingService.indexOf('body: {'),
    routingService.indexOf("reasonCode: 'ROUTING_EXCEPTION_TEAM_SELECTED'")
  );
  assert.doesNotMatch(routeBody, /coordinatorUserId/);
  assert.doesNotMatch(routingService, /booked.*:\s*true/i);
});

test('routing and no-store reads never derive authority from display labels', () => {
  assert.match(routingService, /cityId: input\.cityId/);
  assert.match(routingService, /teamId:/);
  assert.match(routingService, /cache: 'no-store'/);
  assert.match(routingService, /coverage\/options\?cityId=\$\{cityId\}/);
  assert.doesNotMatch(routingService, /find\([^)]*displayName[^)]*\).*route/);
});

test('Owner permission screen is capability-gated, search-first, and not prepopulated', () => {
  assert.match(staff, /staffAdministrationService\.capabilities\(\{ session \}\)/);
  assert.match(staff, /STAFF_ADMIN[\s\S]{0,120}GLOBAL/);
  assert.match(staff, /Search by User ID, name, or email/);
  assert.match(staff, /No staff account or permission is preselected/);
  assert.match(staff, /Review and confirm/);
  assert.match(staff, /Confirm revocation/);
  assert.match(staff, /Unrelated permissions remain/);
  assert.match(staffPresentation, /effectiveness not verified/);
  assert.match(staff, /authoritative readback did not show that exact fact/);
  assert.match(staff, /Reason code/);
  assert.doesNotMatch(staff, /@[a-z0-9.-]+\.(com|in)\b/i);
});

test('protected response data is checked after JSON and error-body decoding', () => {
  for (const service of [routingService]) {
    const successDecode = service.indexOf('payload = await response.json()');
    const postDecodeCheck = service.indexOf('requireActive(session);', successDecode);
    assert.ok(successDecode >= 0 && postDecodeCheck > successDecode);
    assert.match(service, /failure = await createApiRequestError/);
    assert.ok(service.indexOf('requireActive(session);', service.indexOf('failure = await createApiRequestError'))
      > service.indexOf('failure = await createApiRequestError'));
  }
  const staffService = read('../services/staffAdministrationService.ts');
  assert.ok(staffService.indexOf('requireActive(captured);',
    staffService.indexOf('payload = await response.json()')) > staffService.indexOf('payload = await response.json()'));
});

test('routing and coverage expose bounded page navigation and authoritative overlap lookup', () => {
  assert.match(routing, /<PageControls page=\{tasksPage\}/);
  assert.match(routing, /<PageControls page=\{coveragePage\}/);
  assert.match(routing, /<PageControls page=\{unclaimedPage\}/);
  assert.match(routingService, /coverage\/locality\?cityId=/);
  assert.doesNotMatch(routing, /activeCoverageOverlaps = coverage\.filter/);
});

test('Owner staff permissions are integrated inside the existing Admin portal', () => {
  assert.match(admin, /id: 'staff-permissions'/);
  assert.match(admin, /activeTab === 'staff-permissions' && <OwnerStaffPermissions/);
});

test('affected UI declares mobile targets, responsive layouts and reduced motion', () => {
  for (const css of [routingCss, staffCss]) {
    assert.match(css, /min-height:\s*44px/);
    assert.match(css, /prefers-reduced-motion|@media \(max-width/);
  }
  assert.match(routingCss, /@media \(max-width: 767px\)/);
  assert.match(routingCss, /@media \(max-width: 374px\)/);
  assert.match(staffCss, /@media \(max-width: 560px\)/);
});

test('operation finalizers retire only the slot they still own', () => {
  assert.match(routing,
    /activeRoutingOperation\.current !== operation\) return;[\s\S]{0,180}?busyRef\.current = false/);
  assert.match(staff,
    /activeStaffOperation\.current !== operation\) return;[\s\S]{0,180}?busyRef\.current = false/);
  assert.match(staff,
    /activeSearchOperation\.current === operation\)[\s\S]{0,160}?setSearching\(false\)/);
});
