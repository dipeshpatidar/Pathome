import assert from 'node:assert/strict';
import { chmodSync, readFileSync } from 'node:fs';
import { resolve, dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { test } from 'node:test';
import {
  ROUTING_ACCEPTANCE_FILE, ROUTING_ACCEPTANCE_ID, approvedIntegrationCoordinates,
  routingAcceptanceTarget
} from '../lib/runtime.mjs';
import { validateReadiness } from '../lib/readiness.mjs';

const tool = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const repo = resolve(tool, '../..');
const targetFile = join(tool, 'routing-acceptance-target.env');
const target = Object.fromEntries(readFileSync(targetFile, 'utf8').split('\n')
  .filter(line => line && !line.startsWith('#'))
  .map(line => [line.slice(0, line.indexOf('=')), line.slice(line.indexOf('=') + 1)]));
const env = {
  PATH: '/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin',
  HOME: process.env.HOME,
  LC_ALL: 'C',
  PATHOME_OE_TEST_TARGET_FILE: ROUTING_ACCEPTANCE_FILE,
  ...target,
  PATHOME_OE_TEST_DB_PASSWORD: 'a'.repeat(48),
  PATHOME_OE_TEST_DB_MIGRATOR_PASSWORD: 'b'.repeat(48),
  SPRING_DATASOURCE_URL: `jdbc:postgresql://${target.PATHOME_OE_TEST_DB_HOST}:${target.PATHOME_OE_TEST_DB_PORT}/${target.PATHOME_OE_TEST_DB}`,
  SPRING_DATASOURCE_USERNAME: target.PATHOME_OE_TEST_DB_APP_USER,
  SPRING_FLYWAY_USER: target.PATHOME_OE_TEST_DB_MIGRATOR_USER
};
const q = value => "'" + value.replaceAll("'", "'\\''") + "'";
const bash = source => spawnSync('/bin/bash', ['-c', source], { encoding: 'utf8', env, timeout: 10000 });
const shellPrelude = `. ${q(join(tool, 'lib/db-guard.sh'))}\n${Object.entries(target).map(([key,value]) => `export ${key}=${q(value)}`).join('\n')}\nexport PATHOME_OE_TEST_TARGET_FILE=${q(ROUTING_ACCEPTANCE_FILE)}\n`;
const noSubprocess = 'oe_runtime_exec(){ printf "UNEXPECTED_SUBPROCESS\\n"; return 99; }\n';

test('the owner-approved routing target is the only accepted routing coordinate', () => {
  const accepted = routingAcceptanceTarget(env);
  assert.equal(accepted.PATHOME_OE_TEST_ACCEPTANCE, ROUTING_ACCEPTANCE_ID);
  assert.equal(approvedIntegrationCoordinates(accepted.PATHOME_OE_TEST_DB,
    accepted.PATHOME_OE_TEST_RUN_DIR, env), true);
  assert.equal(approvedIntegrationCoordinates('pathome_oe_test_g10_20261022',
    '/private/tmp/pathome-oe-g10-20261022', env), false);
  assert.equal(approvedIntegrationCoordinates('pathome_oe_test_phase2a_20261015',
    '/private/tmp/pathome-oe-phase2a-20261015', env), false);
});

test('wrong host, port, database, run, schema, fixture prefix and roles refuse before subprocess', () => {
  const cases = [
    ['PATHOME_OE_TEST_DB_HOST','localhost'],
    ['PATHOME_OE_TEST_DB_PORT','5433'],
    ['PATHOME_OE_TEST_DB','pathome_oe_test_integration'],
    ['PATHOME_OE_TEST_RUN_ID','routing-p1-20261024-foreign'],
    ['PATHOME_OE_TEST_SCHEMA','routing_p1_foreign'],
    ['PATHOME_OE_TEST_FIXTURE_PREFIX','routing_p1_foreign_'],
    ['PATHOME_OE_TEST_DB_APP_USER','wrong_runtime'],
    ['PATHOME_OE_TEST_DB_MIGRATOR_USER','wrong_migrator']
  ];
  for (const [key,value] of cases) {
    const changed = { ...env, [key]: value };
    assert.throws(() => routingAcceptanceTarget(changed), key);
  }
  for (const database of ['pathome_oe_test_g10_20261022','pathome_oe_test_phase2a_20261015',
    'pathome_oe_test_routing_20261023','pathome_oe_test_routing_closure_20261023_01a169a2',
    'pathome_oe_test_integration','pathome_db']) {
    const result = bash(shellPrelude + noSubprocess +
      `export PATHOME_OE_TEST_DB=${q(database)}; oe_guard_resolve`);
    assert.notEqual(result.status, 0, database);
    assert.doesNotMatch(result.stdout, /UNEXPECTED_SUBPROCESS/);
  }
});

test('shell accepts exact target and refuses contradictory JDBC and protected aliases', () => {
  const accepted = bash(shellPrelude + noSubprocess +
    `oe_guard_resolve; oe_guard_verify_jdbc ${q(env.SPRING_DATASOURCE_URL)}`);
  assert.equal(accepted.status, 0, accepted.stderr);
  assert.doesNotMatch(accepted.stdout, /UNEXPECTED_SUBPROCESS/);
  for (const url of [
    env.SPRING_DATASOURCE_URL.replace('127.0.0.1','localhost'),
    env.SPRING_DATASOURCE_URL.replace(':5432',':5433'),
    env.SPRING_DATASOURCE_URL + '?currentSchema=public',
    env.SPRING_DATASOURCE_URL.replace(target.PATHOME_OE_TEST_DB,'pathome_db')
  ]) {
    const result = bash(shellPrelude + noSubprocess +
      `export PATHOME_OE_TEST_DB_HOST=${q(new URL(url.replace('jdbc:postgresql:','http:')).hostname)};
       export PATHOME_OE_TEST_DB_PORT=${q(new URL(url.replace('jdbc:postgresql:','http:')).port)};
       oe_guard_verify_jdbc ${q(url)}`);
    assert.notEqual(result.status, 0, url);
    assert.doesNotMatch(result.stdout, /UNEXPECTED_SUBPROCESS/);
  }
});

test('runtime and migrator credentials cannot be shared or malformed', () => {
  for (const changed of [
    { PATHOME_OE_TEST_DB_PASSWORD: 'short', PATHOME_OE_TEST_DB_MIGRATOR_PASSWORD: 'b'.repeat(48) },
    { PATHOME_OE_TEST_DB_PASSWORD: 'a'.repeat(48), PATHOME_OE_TEST_DB_MIGRATOR_PASSWORD: 'a'.repeat(48) }
  ]) {
    const result = bash('set -e\n' + shellPrelude + noSubprocess +
      `export PATHOME_OE_TEST_DB_PASSWORD=${q(changed.PATHOME_OE_TEST_DB_PASSWORD)}
       export PATHOME_OE_TEST_DB_MIGRATOR_PASSWORD=${q(changed.PATHOME_OE_TEST_DB_MIGRATOR_PASSWORD)}
       printf '%s\\n' "$PATHOME_OE_TEST_DB_PASSWORD" | grep -Eq '^[a-f0-9]{48}$'
       test "$PATHOME_OE_TEST_DB_PASSWORD" != "$PATHOME_OE_TEST_DB_MIGRATOR_PASSWORD"`);
    assert.notEqual(result.status, 0);
  }
});

test('invalid target-file permissions and a changed source manifest are refused', () => {
  assert.throws(() => routingAcceptanceTarget({ ...env,
    PATHOME_OE_TEST_TARGET_FILE: '/private/tmp/foreign-routing-target.env' }));
  const original = 0o600;
  try {
    chmodSync(targetFile, 0o640);
    assert.throws(() => routingAcceptanceTarget(env), /mode 600/);
  } finally {
    chmodSync(targetFile, original);
  }
  const receipt = {
    database: target.PATHOME_OE_TEST_DB,
    host: target.PATHOME_OE_TEST_DB_HOST,
    port: target.PATHOME_OE_TEST_DB_PORT,
    adminUser: target.PATHOME_OE_TEST_DB_ADMIN_USER,
    appUser: target.PATHOME_OE_TEST_DB_APP_USER,
    acceptance: target.PATHOME_OE_TEST_ACCEPTANCE,
    runId: target.PATHOME_OE_TEST_RUN_ID,
    schema: target.PATHOME_OE_TEST_SCHEMA,
    migratorUser: target.PATHOME_OE_TEST_DB_MIGRATOR_USER,
    fixturePrefix: target.PATHOME_OE_TEST_FIXTURE_PREFIX,
    targetFile: ROUTING_ACCEPTANCE_FILE,
    backendPort: 8080,
    root: target.PATHOME_OE_TEST_RUN_DIR,
    image: 'routing-acceptance-no-media',
    imageHash: 'image',
    mediaHash: 'media',
    sourceHash: 'source',
    completedAt: new Date().toISOString(),
    offlineMaven: true,
    frontendBuilt: true,
    chromium: '/synthetic/chromium',
    distHash: 'dist'
  };
  assert.equal(validateReadiness(receipt, receipt, 'dist'), receipt);
  assert.throws(() => validateReadiness({ ...receipt, sourceHash: 'changed' }, receipt, 'dist'));
});
