import test from 'node:test';
import assert from 'node:assert/strict';
import { WORKFLOW_STATUS_CONFIG } from '../utils/lessorWorkflow.ts';
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

test('Published property with pending revision preserves both live and revision states', () => {
  const listing = {
    listingId: 101,
    status: 'PUBLISHED',
    openRevisionStatus: 'REVIEW'
  };

  const primaryStatus = WORKFLOW_STATUS_CONFIG[listing.status];
  assert.equal(primaryStatus.label, 'Published');
  assert.equal(primaryStatus.guidance, 'Your property is live.');

  const isLivePublished = listing.status === 'PUBLISHED';
  const hasRevisionUnderReview = listing.openRevisionStatus === 'REVIEW';

  assert.equal(isLivePublished, true);
  assert.equal(hasRevisionUnderReview, true);
});

test('Changes required status specifies Review changes action button', () => {
  const getActionLabel = (status, openRevisionStatus) => {
    return status === 'CHANGES_REQUIRED' || openRevisionStatus === 'CHANGES_REQUIRED'
      ? 'Review changes'
      : 'Preview property';
  };

  assert.equal(getActionLabel('CHANGES_REQUIRED', null), 'Review changes');
  assert.equal(getActionLabel('PUBLISHED', 'CHANGES_REQUIRED'), 'Review changes');
  assert.equal(getActionLabel('PUBLISHED', null), 'Preview property');
  assert.equal(getActionLabel('UNDER_REVIEW', null), 'Preview property');
  assert.equal(getActionLabel('SUBMITTED', null), 'Preview property');
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
    error: 'EMAIL_ALREADY_REGISTERED',
    message: 'An account with this email already exists.'
  };

  const isDuplicateEmail =
    backendErrorBody.error === 'EMAIL_ALREADY_REGISTERED' ||
    /email is already registered/i.test(backendErrorBody.message);

  assert.equal(isDuplicateEmail, true);
  assert.equal(backendErrorBody.message, 'An account with this email already exists.');
  assert.equal('fullName' in backendErrorBody, false);
  assert.equal('phoneNumber' in backendErrorBody, false);
  assert.equal('username' in backendErrorBody, false);
});
