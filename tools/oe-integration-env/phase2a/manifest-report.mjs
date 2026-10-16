/**
 * Prints the Phase 2A acceptance manifest in a reviewable form. Static only: it reads the manifest
 * module and writes text. It touches no database, backend, container or external service.
 */
import { PHASE2A_ACTORS, PHASE2A_AREAS, PHASE2A_META, PHASE2A_RULES, SCENARIOS } from './manifest.mjs';

const pad = (value, width) => String(value).padEnd(width);

console.log(`manifest version ${PHASE2A_META.manifestVersion}`);
console.log(`scenarios: ${SCENARIOS.length} (${SCENARIOS.filter((s) => s.kind === 'fixture').length} fixture, `
  + `${SCENARIOS.filter((s) => s.kind === 'coverage').length} coverage)\n`);

console.log('approved Phase 2A areas');
for (const area of PHASE2A_AREAS) {
  const count = SCENARIOS.filter((scenario) => scenario.kind === 'coverage' && scenario.area === area.id).length;
  console.log(`  ${pad(area.id, 40)} coverage=${count}  ${area.title}`);
}

console.log('\nactors (authority is always read from live facts)');
for (const [id, actor] of Object.entries(PHASE2A_ACTORS)) {
  console.log(`  ${pad(id, 18)} ${actor.description}`);
}

console.log('\nfixtures (real APIs only - never a SQL seed, never a stub)');
for (const scenario of SCENARIOS.filter((entry) => entry.kind === 'fixture')) {
  console.log(`  ${pad(scenario.id, 5)} ${pad(scenario.method, 6)} ${pad(scenario.apiPath, 62)} ${scenario.note}`);
}

console.log('\ncoverage');
for (const scenario of SCENARIOS.filter((entry) => entry.kind === 'coverage')) {
  console.log(`  ${pad(scenario.id, 5)} stage ${pad(scenario.stage, 3)} ${pad(scenario.actor, 12)}`
    + ` ${pad(scenario.method, 6)} ${pad(scenario.apiPath, 62)} expect ${scenario.expect.join('/')}`);
}

console.log('\nrules');
for (const rule of PHASE2A_RULES) console.log(`  - ${rule}`);

console.log('\nmedia dependency');
console.log(`  ${PHASE2A_META.requiredMediaDependency}`);
console.log('\nforbidden in the suite source (checked statically)');
console.log(`  ${PHASE2A_META.forbiddenInSuite.join(', ')}`);
