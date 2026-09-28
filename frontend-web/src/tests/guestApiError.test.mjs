import test from 'node:test';
import assert from 'node:assert/strict';
import { createApiRequestError } from '../services/apiError.ts';

test('expired guest proof does not invalidate an authenticated browser session', async () => {
  const events = [];
  globalThis.window = { dispatchEvent: event => events.push(event.type) };
  const response = { status: 401, json: async () => ({ message: 'Unauthorized' }) };

  const error = await createApiRequestError(response, 'Unable to open guest draft.', true);
  assert.equal(error.status, 401);
  assert.match(error.message, /Guest access has expired/);
  assert.deepEqual(events, []);
});
