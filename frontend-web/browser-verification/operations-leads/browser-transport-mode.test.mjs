import assert from 'node:assert/strict';
import test from 'node:test';

import {
  BROWSER_TRANSPORT_MODE,
  STRICT_GENUINE_FLAG,
  allowsApiInterception,
  browserTransportMode,
  installTransportRoutes
} from './browser-transport-mode.mjs';

test('absence or explicit false preserves controlled timing mode', () => {
  assert.equal(browserTransportMode({}), BROWSER_TRANSPORT_MODE.CONTROLLED);
  assert.equal(
    browserTransportMode({ [STRICT_GENUINE_FLAG]: 'false' }),
    BROWSER_TRANSPORT_MODE.CONTROLLED
  );
  assert.equal(allowsApiInterception(BROWSER_TRANSPORT_MODE.CONTROLLED), true);
});

test('exactly true selects strict genuine mode with no API interception', () => {
  const mode = browserTransportMode({ [STRICT_GENUINE_FLAG]: 'true' });
  assert.equal(mode, BROWSER_TRANSPORT_MODE.STRICT_GENUINE);
  assert.equal(allowsApiInterception(mode), false);
});

test('ambiguous or malformed values fail closed', () => {
  for (const value of ['', '1', 'TRUE', 'yes', ' strict ', null]) {
    assert.throws(
      () => browserTransportMode({ [STRICT_GENUINE_FLAG]: value }),
      /must be exactly "true" or "false"/
    );
  }
});

test('strict mode registers only the local WhatsApp route', async () => {
  const registrations = [];
  const page = {
    route: async (pattern) => registrations.push(`page:${pattern}`)
  };
  const context = {
    route: async (pattern) => registrations.push(`context:${pattern}`)
  };

  const installed = await installTransportRoutes({
    mode: BROWSER_TRANSPORT_MODE.STRICT_GENUINE,
    page,
    context,
    whatsappHandler: () => {},
    apiRoutes: [['**/api/**', () => {}]]
  });

  assert.deepEqual(installed, []);
  assert.deepEqual(registrations, ['context:https://wa.me/**']);
});

test('controlled mode explicitly registers the provided API routes', async () => {
  const registrations = [];
  const page = {
    route: async (pattern) => registrations.push(`page:${pattern}`)
  };
  const context = {
    route: async (pattern) => registrations.push(`context:${pattern}`)
  };

  const installed = await installTransportRoutes({
    mode: BROWSER_TRANSPORT_MODE.CONTROLLED,
    page,
    context,
    whatsappHandler: () => {},
    apiRoutes: [
      ['**/api/one', () => {}],
      ['**/api/two', () => {}]
    ]
  });

  assert.deepEqual(installed, ['**/api/one', '**/api/two']);
  assert.deepEqual(registrations, [
    'context:https://wa.me/**',
    'page:**/api/one',
    'page:**/api/two'
  ]);
});
