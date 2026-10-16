import { writePrivate } from '../lib/runtime.mjs';
import { mkdirSync } from 'node:fs';
import { dirname, basename, join } from 'node:path';
import { randomUUID } from 'node:crypto';

export const isObject = value => value !== null && typeof value === 'object' && !Array.isArray(value);
export const positive = value => Number.isSafeInteger(Number(value)) && Number(value) > 0;
export const versioned = value => Number.isSafeInteger(value) && value >= 0;
export const unchanged = (before, after) => JSON.stringify(before) === JSON.stringify(after);
export const snapshot = json => {
  if (!isObject(json)) return null;
  return Object.fromEntries(['requestId', 'sessionId', 'targetId', 'version', 'expectedVersion', 'status',
    'teamId', 'coordinatorUserId', 'observedOwnerUserId', 'scheduledAt', 'reservedEndAt', 'durationMinutes',
    'zoneId', 'assignedAt', 'tenantConfirmationState', 'representativeUserId', 'linkedRequestVersions', 'items']
    .filter(key => json[key] !== undefined).map(key => [key, key === 'items' ? json.items.map(item =>
      Object.fromEntries(['itemId', 'listingId', 'position', 'removedAt', 'confirmationStatus',
        'propertyAvailabilityStartAt', 'propertyAvailabilityEndAt', 'propertyAvailabilityZoneId',
        'propertyAvailabilitySource', 'availabilityConfirmedAt', 'availabilityConfirmedByUserId'].filter(field => item[field] !== undefined)
        .map(field => [field, item[field]]))) : json[key]]));
};
export const offsetWindow = (now = Date.now()) => {
  const base = Math.ceil((now + 86400000) / 900000) * 900000;
  const offset = 330 * 60000;
  const local = delta => new Date(base + delta + offset).toISOString().slice(0, -1) + '+05:30';
  return { windowStart: local(0), windowEnd: local(8 * 3600000), availabilityStart: local(3600000),
    availabilityEnd: local(7 * 3600000), scheduledAt: new Date(base + 3600000).toISOString(),
    rescheduleAt: new Date(base + 5 * 3600000).toISOString(), staleRescheduleAt: new Date(base + 6 * 3600000).toISOString(),
    availabilityZone: 'Asia/Kolkata' };
};
export class Evidence {
  constructor(scenarios, file, mode = 'API', provenance = {}) {
    this.directory = join(dirname(file), 'attempts', mode.toLowerCase() + '-' + Date.now() + '-' + randomUUID());
    mkdirSync(this.directory, { recursive: true, mode: 0o700 });
    this.file = join(this.directory, basename(file)); this.mode = mode;
    this.pointer = join(dirname(file), 'latest-success-' + mode.toLowerCase() + '.json');
    writePrivate(join(dirname(file),'latest-attempt-'+mode.toLowerCase()+'.json'),{file:this.file,runId:provenance.runId});
    this.startedAt = new Date().toISOString(); this.endedAt = null; this.status = 'RUNNING'; this.checkpoint = 'START';
    this.provenance = provenance;
    this.recorded = new Set(); this.log = console.log;
    this.records = new Map(scenarios.map(s => [s.id, { id: s.id, kind: s.kind, actor: s.actor,
      authority: null, method: s.method, endpoint: s.apiPath, httpStatus: null, before: null, after: null,
      requiredFields: [], result: 'NOT TESTED', reason: 'Execution has not reached this required scenario' }]));
    this.flush();
  }
  record(id, entry) {
    const previous = this.records.get(id);
    if (!previous || this.recorded.has(id)) throw Error('Missing or duplicate scenario evidence: ' + id);
    this.recorded.add(id);
    this.records.set(id, { ...previous, ...entry }); this.checkpoint = id; this.flush();
    this.log(entry.result + ' ' + id + (entry.reason ? ' ' + entry.reason : ''));
  }
  flush() {
    const records = [...this.records.values()];
    writePrivate(this.file, { ...this.provenance, mode: this.mode, startedAt: this.startedAt, endedAt: this.endedAt,
      status: this.status, checkpoint: this.checkpoint, recordedAt: new Date().toISOString(),
      counts: Object.fromEntries(['PASS', 'FAIL', 'NOT TESTED'].map(result => [result, records.filter(r => r.result === result).length])), records });
  }
  complete() { return [...this.records.values()].every(r => r.result === 'PASS'); }
  watchInterruptions() {
    this.interrupt = signal => { this.finish('INTERRUPTED'); process.exit(signal === 'SIGINT' ? 130 : 143); };
    this.onInt = () => this.interrupt('SIGINT'); this.onTerm = () => this.interrupt('SIGTERM');
    process.once('SIGINT',this.onInt); process.once('SIGTERM',this.onTerm);
  }
  finish(status = this.complete() ? 'PASSED' : 'FAILED') {
    if (this.status !== 'RUNNING') return;
    this.status = status; this.endedAt = new Date().toISOString(); this.flush();
    if(this.onInt) process.removeListener('SIGINT',this.onInt);
    if(this.onTerm) process.removeListener('SIGTERM',this.onTerm);
    if (status === 'PASSED' && this.complete()) writePrivate(this.pointer, { file: this.file, runId: this.provenance.runId });
  }
}

export const successfulEvidence = (root, mode, privateRead) => {
  const pointer = JSON.parse(privateRead(root + '/evidence/latest-success-' + mode.toLowerCase() + '.json'));
  const latest = JSON.parse(privateRead(root + '/evidence/latest-attempt-' + mode.toLowerCase() + '.json'));
  if(latest.file!==pointer.file || latest.runId!==pointer.runId) throw Error('Latest attempt has not completed successfully');
  if (typeof pointer.file !== 'string' || !pointer.file.startsWith(root + '/evidence/attempts/') || pointer.file.includes('..')) throw Error('Invalid evidence pointer');
  const report = JSON.parse(privateRead(pointer.file));
  if (report.status !== 'PASSED' || !report.endedAt || report.runId !== pointer.runId) throw Error('Incomplete evidence attempt');
  return report;
};

export const containsContact = value => {
  if (Array.isArray(value)) return value.some(containsContact);
  if (!isObject(value)) return false;
  return Object.entries(value).some(([key, child]) => /^(mobile|phone|phoneNumber|email|name|fullName|contact|lessorContact)$/i.test(key) || containsContact(child));
};
const instantEquals = (a, b) => Number.isFinite(Date.parse(a)) && Date.parse(a) === Date.parse(b);
const stateVersion = value => value?.version ?? value?.expectedVersion;
const owner = value => value?.coordinatorUserId ?? value?.observedOwnerUserId ?? null;

export function mutationPostcondition(scenario, response, ctx, before, after) {
  const claim = ['C12','C16','C19'].includes(scenario.id), release = ['C17','C20'].includes(scenario.id);
  if (claim || release) {
    const expectedId = ['C16','C17'].includes(scenario.id) ? ctx.secondRequestId : scenario.id === 'C19' || scenario.id === 'C20' ? ctx.sessionId : ctx.requestId;
    const id = state => state?.sessionId ?? state?.requestId ?? state?.targetId;
    if (!before || !after || id(before) !== expectedId || id(after) !== expectedId
        || !versioned(stateVersion(before)) || stateVersion(after) !== stateVersion(before) + 1
        || before.status !== after.status
        || (claim ? owner(before) !== null || owner(after) !== ctx.actorUserId : owner(before) !== ctx.actorUserId || owner(after) !== null)) return 'Ownership transition was not persisted by the authoritative reads';
  }
  if (scenario.id === 'C41') {
    const submitted = ctx.submittedBody;
    const item = value => value?.items?.find(i => i.itemId === ctx.itemId);
    const matches = current => current && submitted && current.confirmationStatus === submitted.status
      && instantEquals(current.propertyAvailabilityStartAt, submitted.availabilityStartAt)
      && instantEquals(current.propertyAvailabilityEndAt, submitted.availabilityEndAt)
      && current.propertyAvailabilityZoneId === submitted.availabilityZoneId
      && current.propertyAvailabilitySource === submitted.availabilitySource;
    if (!matches(item(response.json)) || !matches(item(after)) || !versioned(stateVersion(before))
        || !versioned(stateVersion(after)) || stateVersion(after) < stateVersion(before)
        || (!matches(item(before)) && stateVersion(after) <= stateVersion(before))) return 'Availability does not match the submitted persisted window/state';
  }
  if (['C32','C35','C36','C37','C38','C45','C47','C53'].includes(scenario.id)) {
    const returned = snapshot(response.json?.session ?? response.json);
    if (!after || !returned || !Object.entries(returned).every(([key, value]) => unchanged(value, after[key]))) return 'Successful command disagrees with the authoritative reread';
    if (scenario.id !== 'C32' && (!versioned(stateVersion(before)) || !(stateVersion(after) > stateVersion(before)))) return 'Required mutation did not advance authoritative state';
  }
  if (['C48','C52'].includes(scenario.id) && (!before || !after || !unchanged(before, after))) return 'Idempotent retry changed reservation state';
  return true;
}

export function assertResponse(scenario, response, ctx, before, after) {
  if (!scenario.expect.includes(response.status)) return 'Unexpected HTTP status ' + response.status;
  if (response.status === 200 && !isObject(response.json)) return 'Expected a real JSON object';
  if (response.status === 200) {
    const json = response.json;
    if (scenario.apiPath.endsWith('/capabilities') && (!Array.isArray(json.capabilities) || typeof json.staffActive !== 'boolean')) return 'Incomplete capability contract';
    if (['C07','C08','C10','C13','C61','C63'].includes(scenario.id)
        && (!Array.isArray(json.items) || !Number.isSafeInteger(json.totalCount))) return 'Incomplete collection contract';
    if (scenario.id === 'C21' && (!Array.isArray(json.requests) || !Number.isSafeInteger(json.totalCount))) return 'Incomplete Request collection contract';
    if (['C24','C36','C37','C38','C41','C48','C50','C51','C52','C53','C62'].includes(scenario.id)
        && (!positive(json.sessionId) || !versioned(json.version) || !Array.isArray(json.items)
          || Number(json.sessionId) !== Number(ctx.sessionId))) return 'Incomplete or wrong Session contract';
    if (scenario.id === 'C22' && (!positive(json.requestId) || !versioned(json.version))) return 'Incomplete Request detail contract';
    if (scenario.id === 'C35' && (!positive(json.requestId) || !versioned(json.version))) return 'Incomplete unavailable Request contract';
    if (scenario.id === 'C32' && (!positive(json.sessionId) || !versioned(json.version) || !Array.isArray(json.items)
      || !json.items.some(i => i.sourceRequestId === ctx.requestId))) return 'Coordination did not return the linked Request stop';
  }
  if (response.status >= 400) {
    const emptyEntryPoint401 = response.status === 401 && response.json === null && response.text.trim() === '';
    if (!emptyEntryPoint401 && (!isObject(response.json) || (typeof response.json.message !== 'string'
        && !(response.json.status === response.status && typeof response.json.error === 'string')))) return 'Malformed denial/conflict response';
    for (const secret of ctx.contactValues ?? []) if (secret && response.text.includes(secret)) return 'Denied response disclosed contact data';
    if (containsContact(response.json)) return 'Denied response carries contact fields';
    if (before && !unchanged(before, after)) return 'Refused command changed authoritative state';
  }
  if (scenario.assert) {
    const verdict = scenario.assert({ ...response, ctx });
    if (verdict !== true) return String(verdict);
  }
  if (['C44', 'C46'].includes(scenario.id)) {
    const candidates = response.json?.candidates;
    if (!Array.isArray(candidates) || response.json.sessionId !== ctx.sessionId || response.json.sessionVersion !== ctx.sessionVersion
        || !candidates.some(c => Number(c.groundExecutiveUserId) === Number(ctx.geUserId)
        && c.feasibilityStatus === 'FEASIBLE' && Number.isFinite(Date.parse(c.scheduledAt))
        && Date.parse(c.reservedEndAt) > Date.parse(c.scheduledAt) && Number.isInteger(c.durationMinutes)
        && c.durationMinutes > 0 && c.zoneId === ctx.availabilityZone)) return 'No actual eligible GE candidate';
  }
  if (['C45', 'C47'].includes(scenario.id)) {
    const session = response.json?.session;
    if (!positive(response.json?.decisionId) || session?.sessionId !== ctx.sessionId || !versioned(session?.version)
        || session.status !== 'SCHEDULED' || Number(session.representativeUserId) !== Number(ctx.geUserId)) return 'Selection did not persist a real scheduled Session and decision';
  }
  if (scenario.id === 'C53' && (Date.parse(response.json.scheduledAt) !== Date.parse(ctx.rescheduleAt)
      || !(response.json.version > before?.version))) return 'Reschedule did not persist the new time/version';
  if (response.status < 400) return mutationPostcondition(scenario, response, ctx, before, after);
  return true;
}
