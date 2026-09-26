import assert from 'node:assert/strict';
import test from 'node:test';
import { getMediaTagLabel, getTaggedAreas } from '../utils/mediaTags.ts';

test('stored known tags get conservative labels without changing their meaning', () => {
  assert.equal(getMediaTagLabel('LIVING_ROOM'), 'Living Room');
  assert.equal(getMediaTagLabel('MASTER_BEDROOM'), 'Master Bedroom');
  assert.equal(getMediaTagLabel('KITCHEN'), 'Kitchen');
  assert.equal(getMediaTagLabel('BEDROOM'), 'Bedroom');
});

test('general, absent, blank, and unknown legacy tags have no visible label', () => {
  for (const value of ['GENERAL', '', 'UNKNOWN', 'KITCHEN_NEW', null, undefined, 42, {}]) {
    assert.equal(getMediaTagLabel(value), null);
  }
});

test('area navigation indexes first tagged image or video and leaves media order intact', () => {
  const media = [
    { tagLabel: null, type: 'IMAGE' },
    { tagLabel: 'Kitchen', type: 'VIDEO' },
    { tagLabel: 'Living Room', type: 'IMAGE' },
    { tagLabel: 'Kitchen', type: 'IMAGE' },
    { tagLabel: null, type: 'VIDEO' }
  ];
  assert.deepEqual(getTaggedAreas(media), [['Kitchen', 1], ['Living Room', 2]]);
  assert.equal(media[0].tagLabel, null);
  assert.equal(media[3].tagLabel, 'Kitchen');
  assert.deepEqual(getTaggedAreas([{ tagLabel: null }]), []);
  assert.deepEqual(getTaggedAreas([{ tagLabel: 'Balcony' }]), [['Balcony', 0]]);
});
