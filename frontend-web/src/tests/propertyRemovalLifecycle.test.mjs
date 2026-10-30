import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '../../..');
const read = (relative) => readFile(path.join(root, relative), 'utf8');

test('the old owner archive route fails closed into the approval workflow', async () => {
  const controller = await read('backend/src/main/java/com/indore/pathome/spaces/controller/LandlordPortfolioController.java');
  assert.match(controller, /legacyArchiveDenied/);
  assert.doesNotMatch(controller, /archive[\s\S]{0,300}changeStatus/);
  assert.match(controller, /PropertyRemovalService/);
});

test('owner removal UI distinguishes pending approval from immediate archive', async () => {
  const owner = await read('frontend-web/src/components/LessorListingView.tsx');
  assert.match(owner, /Request property removal/);
  assert.match(owner, /listing stays in its current state until an Admin approves removal/i);
  assert.match(owner, /Existing visits are not cancelled/);
  assert.match(owner, /withdrawRemoval/);
  assert.match(owner, /decisionReason/);
  assert.doesNotMatch(owner, /Confirm archive/);
});

test('admin decision carries exact request and property versions plus a required reason', async () => {
  const service = await read('frontend-web/src/services/propertyRemovalService.ts');
  const queue = await read('frontend-web/src/components/backoffice/PropertyRemovalQueue.tsx');
  assert.match(service, /'If-Match': String\(detail\.request\.requestVersion\)/);
  assert.match(service, /expectedListingVersion: detail\.request\.currentListingVersion/);
  assert.match(queue, /disabled=\{busy \|\| !reason\.trim\(\)\}/);
  assert.match(queue, /Appointments/);
});

test('removal schema is restrictive and enforces one pending request per property', async () => {
  const migration = await read('backend/src/main/resources/db/migration/V56__property_removal_approval.sql');
  assert.match(migration, /REFERENCES listings\(id\) ON DELETE RESTRICT/);
  assert.match(migration, /REFERENCES users\(id\) ON DELETE RESTRICT/);
  assert.match(migration, /uk_removal_one_pending_per_listing/);
  assert.match(migration, /WHERE request_status = 'PENDING'/);
  assert.match(migration, /terminal property removal request is immutable/);
  assert.doesNotMatch(migration, /DELETE FROM listings|DROP TABLE listings/);
});

test('backoffice staff contract covers every approved normal field and preserves media resources', async () => {
  const editor = await read('frontend-web/src/components/backoffice/BackofficePropertyEditor.tsx');
  for (const field of [
    'monthlyRent', 'securityDeposit', 'availableFrom', 'maintenanceCharge',
    'brokerageAmount', 'brokerageDays', 'securityDepositMonths', 'title',
    'description', 'propertyType', 'bhkCount', 'totalAreaSqFt', 'floorNumber',
    'totalFloors', 'furnishingStatus', 'amenities', 'bathroomCount', 'vastuFacing',
    'bachelorAllowed', 'preferredTenant', 'address', 'landmark', 'colony', 'pincode'
  ]) {
    assert.match(editor, new RegExp(field), `missing normal field ${field}`);
  }
  const publication = await read('backend/src/main/java/com/indore/pathome/spaces/service/BackofficePropertyService.java');
  assert.match(publication, /setRetiredAt/);
  assert.doesNotMatch(publication, /assets\.deleteAll|repository\.delete/);
});

test('fixed property duties are City-only and separate from global staff administration', async () => {
  const capability = await read('backend/src/main/java/com/indore/pathome/spaces/entity/StaffAccessGrant.java');
  assert.match(capability, /PROPERTY_EDIT_PUBLISH[\s\S]*PROPERTY_AUDIT_READ[\s\S]*PROPERTY_LIFECYCLE/);
  assert.match(capability, /case PROPERTY_EDIT_PUBLISH, PROPERTY_AUDIT_READ, PROPERTY_LIFECYCLE[\s\S]*StaffScopeType\.CITY/);
  const admin = await read('backend/src/main/java/com/indore/pathome/spaces/service/PropertyRemovalService.java');
  assert.match(admin, /Role\.ROLE_ADMIN/);
  assert.match(admin, /findEffectiveGlobalAdminSharedByUserId/);
  assert.match(admin, /EmployeeProfile::isStaffActive/);
});

test('the Backoffice route is protected and is not redirected by legacy role routing', async () => {
  const home = await read('frontend-web/src/components/Home.tsx');
  assert.match(home, /path\.startsWith\('\/backoffice\/properties'\)/);
  assert.match(home, /if \(backofficeRoute\) return;/);
  assert.match(home, /usePropertyWorkspaceCapability/);
  assert.match(home, /BackofficePropertiesWorkspace/);
});

test('current-property publication has one same-tab and cross-tab freshness seam', async () => {
  const events = await read('frontend-web/src/utils/propertyCurrentEvents.ts');
  const home = await read('frontend-web/src/components/Home.tsx');
  const detail = await read('frontend-web/src/components/PublicPropertyDetail.tsx');
  assert.match(events, /BroadcastChannel/);
  assert.match(events, /subscribePropertyCurrentChanged/);
  assert.match(home, /subscribePropertyCurrentChanged/);
  assert.match(detail, /subscribePropertyCurrentChanged/);
});

