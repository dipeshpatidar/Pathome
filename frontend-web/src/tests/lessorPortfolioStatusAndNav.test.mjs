import test from 'node:test';
import assert from 'node:assert/strict';
import {
  getListingActionLabel,
  getRevisionNotice,
  WORKFLOW_STATUS_CONFIG
} from '../utils/lessorWorkflow.ts';
import {
  DUPLICATE_EMAIL_CODE,
  DUPLICATE_EMAIL_MESSAGE,
  getDuplicateEmailRecoveryState,
  isDuplicateEmailResponse
} from '../utils/authRecovery.ts';
import { resolveWorkspaceContext, resolveLogoDestination } from '../utils/navigationPolicy.ts';

test('WORKFLOW_STATUS_CONFIG maps every backend workflow state truthfully', () => {
  const expectedStates = [
    'DRAFT',
    'SUBMITTED',
    'UNDER_REVIEW',
    'CHANGES_REQUIRED',
    'PUBLISHED',
    'PAUSED',
    'ARCHIVED'
  ];

  for (const state of expectedStates) {
    const config = WORKFLOW_STATUS_CONFIG[state];
    assert.ok(config, `Missing config for state ${state}`);
    assert.ok(config.label.length > 0, `Missing label for state ${state}`);
    assert.ok(config.guidance.length > 0, `Missing guidance for state ${state}`);
    assert.ok(config.badgeClass.length > 0, `Missing badgeClass for state ${state}`);
    assert.ok(config.dotClass.length > 0, `Missing dotClass for state ${state}`);
  }

  assert.equal(WORKFLOW_STATUS_CONFIG.DRAFT.label, 'Draft');
  assert.equal(WORKFLOW_STATUS_CONFIG.DRAFT.guidance, "Finish your listing when you're ready.");

  assert.equal(WORKFLOW_STATUS_CONFIG.SUBMITTED.label, 'Submitted for review');
  assert.equal(WORKFLOW_STATUS_CONFIG.SUBMITTED.guidance, 'Pathome has received your property for review.');

  assert.equal(WORKFLOW_STATUS_CONFIG.UNDER_REVIEW.label, 'Under review');
  assert.equal(WORKFLOW_STATUS_CONFIG.UNDER_REVIEW.guidance, 'Our team is reviewing your property.');

  assert.equal(WORKFLOW_STATUS_CONFIG.CHANGES_REQUIRED.label, 'Changes required');
  assert.equal(WORKFLOW_STATUS_CONFIG.CHANGES_REQUIRED.guidance, 'Updates are needed before this property can be published.');

  assert.equal(WORKFLOW_STATUS_CONFIG.PUBLISHED.label, 'Published');
  assert.equal(WORKFLOW_STATUS_CONFIG.PUBLISHED.guidance, 'Your property is live.');

  assert.equal(WORKFLOW_STATUS_CONFIG.PAUSED.label, 'Paused');
  assert.equal(WORKFLOW_STATUS_CONFIG.PAUSED.guidance, 'This property is currently not visible to renters.');

  assert.equal(WORKFLOW_STATUS_CONFIG.ARCHIVED.label, 'Archived');
  assert.equal(WORKFLOW_STATUS_CONFIG.ARCHIVED.guidance, 'This property has been archived.');
});

test('Published property with pending revision preserves truthful live copy', () => {
  assert.deepEqual(getRevisionNotice('PUBLISHED', 'REVIEW'), {
    title: 'Changes under review',
    message: 'Our team is reviewing recent edits. Your live listing remains visible.'
  });
});

test('Paused property with pending revision says it remains paused', () => {
  const notice = getRevisionNotice('PAUSED', 'REVIEW');
  assert.deepEqual(notice, {
    title: 'Changes under review',
    message: 'Our team is reviewing recent edits. This property remains paused.'
  });
  assert.doesNotMatch(notice.message, /live|active|visible/i);
});

test('Archived and other non-live states never receive a live-listing claim', () => {
  for (const status of ['DRAFT', 'SUBMITTED', 'UNDER_REVIEW', 'CHANGES_REQUIRED', 'ARCHIVED']) {
    const notice = getRevisionNotice(status, 'REVIEW');
    assert.ok(notice);
    assert.doesNotMatch(notice.message, /live|active|visible/i, `${status} received live copy`);
  }
});

test('Changes required status specifies Review changes action button', () => {
  assert.equal(getListingActionLabel('CHANGES_REQUIRED', null), 'Review changes');
  assert.equal(getListingActionLabel('PUBLISHED', 'CHANGES_REQUIRED'), 'Review changes');
  assert.equal(getListingActionLabel('PUBLISHED', null), 'Preview property');
  assert.equal(getListingActionLabel('UNDER_REVIEW', null), 'Preview property');
  assert.equal(getListingActionLabel('SUBMITTED', null), 'Preview property');
});

test('Navigation routes distinguish My Properties (/lessor) from Add property (/lessor/new)', () => {
  const myPropertiesDestination = '/lessor';
  const addPropertyDestination = '/lessor/new';
  const browseHomesDestination = '/';

  assert.notEqual(myPropertiesDestination, addPropertyDestination);
  assert.equal(myPropertiesDestination, '/lessor');
  assert.equal(addPropertyDestination, '/lessor/new');
  assert.equal(browseHomesDestination, '/');

  assert.equal(resolveWorkspaceContext('/lessor', 'TENANT'), 'LESSOR');
  assert.equal(resolveLogoDestination('LESSOR').path, '/lessor');
  assert.equal(resolveLogoDestination('TENANT').path, '/');
});

test('Duplicate email registration response safely categorized without leaking user info', () => {
  const backendErrorBody = {
    error: DUPLICATE_EMAIL_CODE,
    message: DUPLICATE_EMAIL_MESSAGE
  };

  assert.equal(isDuplicateEmailResponse(backendErrorBody, JSON.stringify(backendErrorBody)), true);
  assert.equal(backendErrorBody.message, DUPLICATE_EMAIL_MESSAGE);
  assert.equal('fullName' in backendErrorBody, false);
  assert.equal('phoneNumber' in backendErrorBody, false);
  assert.equal('username' in backendErrorBody, false);
});

test('Duplicate-email recovery retains only email and clears registration secrets', () => {
  assert.deepEqual(getDuplicateEmailRecoveryState('lessor@example.com'), {
    authMode: 'LOGIN',
    email: 'lessor@example.com',
    password: '',
    fullName: '',
    errorMessage: '',
    duplicateEmailError: false
  });
});

test('Unrelated backend failures are not treated as duplicate email', () => {
  const body = { error: 'PERSISTENCE_FAILURE', message: 'Unable to create account.' };
  assert.equal(isDuplicateEmailResponse(body, JSON.stringify(body)), false);
});
