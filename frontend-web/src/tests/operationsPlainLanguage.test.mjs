import test from 'node:test';
import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const directory = fileURLToPath(new URL('../components/operations/', import.meta.url));
const source = readdirSync(directory)
  .filter((name) => name.endsWith('.tsx'))
  .map((name) => readFileSync(join(directory, name), 'utf8'))
  .join('\n');
const notificationDrawer = readFileSync(
  new URL('../components/NotificationCenterDrawer.tsx', import.meta.url),
  'utf8'
);
const leadPresentation = readFileSync(
  new URL('../utils/operationsLeadPresentation.ts', import.meta.url),
  'utf8'
);
const sharePreparation = readFileSync(
  new URL('../utils/operationsSharePreparation.ts', import.meta.url),
  'utf8'
);
const workspaceCss = readFileSync(
  new URL('../components/operations/operationsWorkspace.css', import.meta.url),
  'utf8'
);

test('OE primary copy uses the approved plain-language terms', () => {
  for (const approved of [
    'Share property details',
    'New message',
    'City and team',
    'Enquiry source',
    'Matching Ground Executives',
    'Property visit times',
    'Visit progress',
    'Refresh visit plan',
    'No matching visit time was found in this check',
    'Show phone number',
    'Save permission and continue',
    'Check if message is ready',
    'Prepare property message',
    'Prepare message',
    'No message has been sent to the customer',
    'Attempted to open WhatsApp',
    'We recorded your attempt to open WhatsApp, but couldn’t confirm that it opened. Check WhatsApp before trying again.'
  ]) {
    assert.ok(source.includes(approved) || leadPresentation.includes(approved),
      `missing approved wording: ${approved}`);
  }
});

test('removed developer-facing OE phrases do not return', () => {
  for (const removed of [
    'Prepare the immutable share snapshot',
    'Browse feasible GEs',
    'Property windows',
    'Reload the session',
    'No feasible matches found',
    'Source exceptions',
    'Intake destination',
    'Source attribution',
    'Record consent and continue',
    'Manual WhatsApp handoff',
    'The prepare reply was lost',
    'Check share status',
    'WhatsApp was already opened',
    'completed receipt was recorded',
    'canonical lessor contact',
    'backend can resolve',
    'current persisted state'
  ]) {
    assert.equal(source.includes(removed) || leadPresentation.includes(removed), false,
      `developer-facing or inaccurate phrase remains: ${removed}`);
  }
});

test('OE failure copy separates attempt, saved result and verified delivery', () => {
  assert.match(source,
    /recorded your attempt to open WhatsApp, but couldn’t confirm that it opened/);
  assert.match(source + leadPresentation,
    /Marked as sent by the coordinator|You reported sending it/);
  assert.doesNotMatch(source, /WhatsApp (was )?(already )?opened for this message/);
  assert.doesNotMatch(source, /server rejected this command and no completed receipt/);
});

test('notification category and error-dismiss controls retain the 44px target', () => {
  assert.match(notificationDrawer,
    /role="tab"[\s\S]{0,400}min-h-11[\s\S]{0,160}text-\[10px\]/);
  assert.match(notificationDrawer,
    /aria-label="Dismiss error"[\s\S]{0,260}min-h-11 min-w-11/);
  assert.match(workspaceCss,
    /\.oe-notice-dismiss\s*\{[\s\S]*?min-width:\s*44px;[\s\S]*?min-height:\s*44px;/);
});
