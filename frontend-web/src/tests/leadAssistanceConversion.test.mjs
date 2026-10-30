import test from 'node:test';
import assert from 'node:assert/strict';
import { installOperationsActionHarness } from './support/operationsActionHarness.mjs';

const harness = await installOperationsActionHarness();
const { leadAssistanceService } = await import('../services/leadAssistanceService.ts');

const property = {
  id: 31,
  listingVersion: 4,
  title: '2 BHK Flat in Vijay Nagar, Indore',
  city: 'Indore',
  sector: 'Vijay Nagar',
  bhk: '2 BHK',
  listingType: 'RENT',
  propertyType: 'FLAT',
  monthlyRent: 25000,
  securityDeposit: 50000,
  totalAreaSqFt: 900,
  images: [],
  verified: true
};

const request = {
  preferredVisitTiming: 'Saturday afternoon',
  availabilityStartAt: null,
  availabilityEndAt: null,
  availabilityZoneId: null,
  preferredAt: null
};

const ownResult = {
  requestId: 55,
  propertyId: 31,
  requestVersion: null,
  status: 'RECEIVED',
  created: true,
  requiresAcknowledgement: false,
  associationStatus: 'UNAVAILABLE',
  message: 'Visit Request recorded and entered normal City/Team intake.',
  receivedAt: '2026-10-27T10:00:00'
};

test('own-account Visit command uses server session identity and no staff or Lead fields', async () => {
  harness.reset(() => harness.json(200, ownResult));
  harness.signIn({ id: 7, role: 'TENANT', token: 'tenant-token' });
  const session = harness.session.captureOperationsSession();
  assert.ok(session);

  const result = await leadAssistanceService.createOwnVisitRequest(property, request, {
    session,
    operationId: '11111111-1111-4111-8111-111111111111'
  });

  assert.equal(result.requestId, 55);
  assert.equal(harness.calls.length, 1);
  const call = harness.calls[0];
  assert.equal(call.url.endsWith('/tenant/property-visit-commands'), true);
  assert.equal(call.authorization, 'Bearer tenant-token');
  assert.equal(call.body.operationId, '11111111-1111-4111-8111-111111111111');
  assert.equal(call.body.listingId, 31);
  assert.equal(call.body.expectedListingVersion, 4);
  assert.equal(call.body.expectedRequestVersion, null);
  assert.equal(call.body.explicitVisitIntent, true);
  assert.equal('userId' in call.body, false);
  assert.equal('leadId' in call.body, false);
  assert.equal('phoneProof' in call.body, false);
});

test('explicit invitation consent is transmitted only with the opaque context', async () => {
  harness.reset(() => harness.json(200, {
    ...ownResult,
    associationStatus: 'PENDING',
    message: 'Visit Request recorded. Optional association pending.'
  }));
  harness.signIn({ id: 7, role: 'TENANT', token: 'tenant-token' });
  const session = harness.session.captureOperationsSession();
  const handoffContext = {
    contextId: '22222222-2222-4222-8222-222222222222',
    proposalId: '33333333-3333-4333-8333-333333333333',
    expiresAt: '2026-10-27T10:30:00Z',
    property: { id: 31, title: 'Structured home', location: 'Indore', bhk: '2 BHK', monthlyRent: 25000, version: 4 }
  };

  await leadAssistanceService.createOwnVisitRequest(property, request, {
    session,
    handoffContext,
    associateEnquiry: true,
    consentWordingVersion: 'VISIT_INVITATION_ENQUIRY_ASSOCIATION_V1',
    operationId: '44444444-4444-4444-8444-444444444444'
  });

  const call = harness.calls[0];
  assert.equal(call.body.handoffContextId, handoffContext.contextId);
  assert.equal(call.body.associateEnquiry, true);
  assert.equal(call.body.consentWordingVersion, 'VISIT_INVITATION_ENQUIRY_ASSOCIATION_V1');
});

test('account switch prevents an old Tenant response from rendering', async () => {
  const pending = harness.deferred();
  harness.reset(() => pending.promise);
  harness.signIn({ id: 7, role: 'TENANT', token: 'tenant-token' });
  const session = harness.session.captureOperationsSession();

  const operation = leadAssistanceService.createOwnVisitRequest(property, request, {
    session,
    operationId: '55555555-5555-4555-8555-555555555555'
  });
  harness.signOut();
  pending.resolve(harness.json(200, ownResult));

  await assert.rejects(operation,
    (error) => error?.name === 'OperationsSessionChangedError');
});

test('existing-history acknowledgement transmits the server-returned Request version', async () => {
  harness.reset(() => harness.json(200, {
    ...ownResult,
    created: false,
    requestVersion: 9
  }));
  harness.signIn({ id: 7, role: 'TENANT', token: 'tenant-token' });
  const session = harness.session.captureOperationsSession();

  const first = await leadAssistanceService.createOwnVisitRequest(property, request, {
    session,
    operationId: '66666666-6666-4666-8666-666666666666'
  });
  assert.equal(first.requestVersion, 9);

  await leadAssistanceService.createOwnVisitRequest(property, request, {
    session,
    acknowledgeExistingRequest: true,
    expectedRequestVersion: first.requestVersion,
    operationId: '77777777-7777-4777-8777-777777777777'
  });

  const acknowledgement = harness.calls[1];
  assert.equal(acknowledgement.body.acknowledgeExistingRequest, true);
  assert.equal(acknowledgement.body.expectedRequestVersion, 9);
});
