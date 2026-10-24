import test from 'node:test';
import assert from 'node:assert/strict';
import { operationsWorkspaceDestinations } from '../utils/operationsWorkspaceNavigation.ts';

const capability = (name, scopeType, cityId = null, teamId = null) => ({
  capability: name,
  scopeType,
  cityId,
  cityDisplayName: cityId === null ? null : 'Bhopal Municipal Central Residential Corridor',
  teamId,
  teamDisplayName: teamId === null ? null : 'North Bhopal Residential Coordination Team'
});

const capabilities = (...entries) => ({
  staffActive: true,
  capabilities: entries,
  serverTime: '2026-10-22T12:00:00Z'
});

const keys = (value) => operationsWorkspaceDestinations(value).map((item) => item.key);

test('desktop and mobile navigation derive one ordered list from effective capability', () => {
  const coordinate = capabilities(capability('OPS_COORDINATE', 'TEAM', 7, 19));
  assert.deepEqual(keys(coordinate), ['home', 'attention', 'my-work', 'claimable', 'leads']);

  const supervisor = capabilities(capability('OPS_SUPERVISE', 'TEAM', 7, 19));
  assert.deepEqual(keys(supervisor), ['leads']);

  const intake = capabilities(capability('OPS_INTAKE', 'CITY', 7));
  assert.deepEqual(keys(intake), ['leads']);

  const admin = capabilities(capability('STAFF_ADMIN', 'GLOBAL'));
  assert.deepEqual(keys(admin), ['leads', 'automatic-routing', 'exceptions']);

  const routingOnly = capabilities(capability('OPS_ROUTING', 'CITY', 7));
  assert.deepEqual(keys(routingOnly), ['automatic-routing']);
});

test('combined grants expose the union but never duplicate Leads', () => {
  const combined = capabilities(
    capability('OPS_COORDINATE', 'TEAM', 7, 19),
    capability('STAFF_ADMIN', 'GLOBAL')
  );
  assert.deepEqual(keys(combined), [
    'home', 'attention', 'my-work', 'claimable', 'leads', 'automatic-routing', 'exceptions'
  ]);
  assert.equal(new Set(keys(combined)).size, keys(combined).length);
});

test('inactive or non-staff capabilities expose no workspace destination', () => {
  assert.deepEqual(keys(null), []);
  assert.deepEqual(keys({
    staffActive: false,
    capabilities: [capability('OPS_COORDINATE', 'TEAM', 7, 19)],
    serverTime: null
  }), []);
});

test('destination labels preserve approved full and compact navigation wording', () => {
  const destinations = operationsWorkspaceDestinations(
    capabilities(capability('OPS_COORDINATE', 'TEAM', 7, 19))
  );
  assert.deepEqual(destinations.map((item) => [item.label, item.short]), [
    ['Overview', 'Home'],
    ['Needs attention', 'Attention'],
    ['My work', 'My work'],
    ['Available work', 'Available'],
    ['Leads', 'Leads']
  ]);
});
