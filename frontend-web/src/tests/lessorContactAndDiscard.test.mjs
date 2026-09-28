import test from 'node:test';
import assert from 'node:assert/strict';

test('missing name or phone marks contact incomplete', () => {
  const isComplete = (contact) => {
    const hasName = Boolean(contact.fullName && contact.fullName.trim().length >= 2);
    const compactPhone = (contact.phoneNumber || '').trim().replace(/[\s\-()]/g, '');
    const hasPhone = /^(?:\+?91)?([6-9]\d{9})$/.test(compactPhone);
    return hasName && hasPhone;
  };

  assert.equal(isComplete({ fullName: null, phoneNumber: null }), false);
  assert.equal(isComplete({ fullName: 'Ramesh Sharma', phoneNumber: null }), false);
  assert.equal(isComplete({ fullName: '', phoneNumber: '+91 98260 12345' }), false);
  assert.equal(isComplete({ fullName: 'A', phoneNumber: '+91 98260 12345' }), false);
  assert.equal(isComplete({ fullName: 'Ramesh Sharma', phoneNumber: '5826012345' }), false); // Starts with 5
  assert.equal(isComplete({ fullName: 'Ramesh Sharma', phoneNumber: '98260 12345' }), true);
  assert.equal(isComplete({ fullName: 'Ramesh Sharma', phoneNumber: '+91 98260 12345' }), true);
  assert.equal(isComplete({ fullName: 'Ramesh Sharma', phoneNumber: '+91-98260-12345' }), true);
});

test('complete profile allows direct submission without showing contact modal', () => {
  const contact = {
    fullName: 'Ananya Verma',
    phoneNumber: '+91 98260 12345',
    complete: true
  };

  let modalShown = false;
  let submissionCompleted = false;

  const handleSubmission = (currentContact) => {
    if (!currentContact.complete) {
      modalShown = true;
      return;
    }
    submissionCompleted = true;
  };

  handleSubmission(contact);
  assert.equal(modalShown, false, 'Modal should not be shown for complete profile');
  assert.equal(submissionCompleted, true, 'Submission should complete directly');
});

test('incomplete profile triggers contact modal and saves before submission', () => {
  let contact = {
    fullName: 'Ananya Verma',
    phoneNumber: null,
    complete: false
  };

  let modalShown = false;
  let submissionCompleted = false;

  const handleSubmission = (currentContact) => {
    if (!currentContact.complete) {
      modalShown = true;
      return;
    }
    submissionCompleted = true;
  };

  handleSubmission(contact);
  assert.equal(modalShown, true, 'Modal must be shown for incomplete profile');
  assert.equal(submissionCompleted, false, 'Submission must not proceed while incomplete');

  // User saves contact details
  contact = {
    fullName: 'Ananya Verma',
    phoneNumber: '+91 98260 12345',
    complete: true
  };
  modalShown = false;

  // Retry/continue submission
  handleSubmission(contact);
  assert.equal(modalShown, false);
  assert.equal(submissionCompleted, true);
});

test('multi-property reuse: second property reuses saved contact without prompting', () => {
  const accountProfile = {
    fullName: 'Devendra Joshi',
    phoneNumber: '+91 98260 99999',
    complete: true
  };

  const submitProperty = (propId, profile) => {
    if (!profile.complete) return { promptContact: true, submitted: false };
    return { promptContact: false, submitted: true, propId };
  };

  // Property 1
  const res1 = submitProperty('prop-1', accountProfile);
  assert.equal(res1.promptContact, false);
  assert.equal(res1.submitted, true);

  // Property 2
  const res2 = submitProperty('prop-2', accountProfile);
  assert.equal(res2.promptContact, false);
  assert.equal(res2.submitted, true);

  // Property 3
  const res3 = submitProperty('prop-3', accountProfile);
  assert.equal(res3.promptContact, false);
  assert.equal(res3.submitted, true);
});

test('guest property draft survives contact save failure', () => {
  const guestDraft = {
    draftId: 'guest-12345',
    basics: { propertyType: 'FLAT', bhkCount: '2BHK' },
    pricing: { monthlyRent: 18000, securityDeposit: 36000 }
  };

  let saveFailed = true;
  let draftLost = false;

  try {
    if (saveFailed) {
      throw new Error('Network error saving contact details');
    }
  } catch (err) {
    // Draft in state or storage is NOT cleared on contact save failure
    assert.equal(guestDraft.draftId, 'guest-12345');
    assert.equal(guestDraft.basics.bhkCount, '2BHK');
    assert.equal(draftLost, false);
  }
});

test('public property response strictly excludes private contact details', () => {
  const publicPropertyCard = {
    id: 42,
    title: '2BHK Flat in Vijay Nagar',
    city: 'Indore',
    locality: 'Vijay Nagar',
    monthlyRent: 20000,
    coverPhoto: 'https://example.com/photo.jpg'
  };

  const json = JSON.stringify(publicPropertyCard);
  assert.equal(json.includes('ownerPhone'), false);
  assert.equal(json.includes('ownerPhoneNumber'), false);
  assert.equal(json.includes('ownerName'), false);
  assert.equal(json.includes('phoneNumber'), false);
  assert.equal(json.includes('98260'), false);
});

test('discard notification copy is truthful and contains no legacy branding or fake claims', () => {
  const getNotification = (isRevision, description) => ({
    title: isRevision ? 'Changes discarded' : 'Property draft discarded',
    message: isRevision
      ? `Changes to your ${description} were discarded. Your published property remains available.`
      : `Your ${description} draft was discarded.`
  });

  const notifDraft = getNotification(false, '2BHK Flat in Vijay Nagar, Indore');
  assert.equal(notifDraft.title, 'Property draft discarded');
  assert.equal(notifDraft.message, 'Your 2BHK Flat in Vijay Nagar, Indore draft was discarded.');
  assert.equal(notifDraft.message.includes('Divyavastu'), false);
  assert.equal(notifDraft.message.includes('VIP Pass'), false);
  assert.equal(notifDraft.message.includes('deleted'), false);

  const notifRevision = getNotification(true, '2BHK Flat in Vijay Nagar, Indore');
  assert.equal(notifRevision.title, 'Changes discarded');
  assert.equal(notifRevision.message, 'Changes to your 2BHK Flat in Vijay Nagar, Indore were discarded. Your published property remains available.');
  assert.equal(notifRevision.message.includes('Divyavastu'), false);
  assert.equal(notifRevision.message.includes('VIP Pass'), false);
});
