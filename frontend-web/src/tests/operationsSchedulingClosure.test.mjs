import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { registerHooks, createRequire } from 'node:module';
import vm from 'node:vm';
import * as loader from './support/operationsComponentLoader.mjs';
import { installOperationsActionHarness } from './support/operationsActionHarness.mjs';
const h = await installOperationsActionHarness();
registerHooks({ resolve: loader.resolve, load: loader.load });
const require = createRequire(new URL('../../package.json', import.meta.url));
const React = require('react'), { renderToStaticMarkup } = require('react-dom/server');
const { OperationsScheduleFlow } = await import('../components/operations/OperationsScheduleFlow.tsx');
const intents = await import('../utils/operationsSchedulingIntent.ts');
const { ApiRequestError } = await import('../services/apiError.ts');
const pending = new Map();
globalThis.sessionStorage = { getItem: k => pending.get(k) ?? null, setItem: (k,v) => pending.set(k,v),
  removeItem: k => pending.delete(k), key: i => [...pending.keys()][i] ?? null, get length() { return pending.size; } };
const body = { expectedSessionVersion: 3, groundExecutiveUserId: 82, scheduledAt: '2027-02-14T05:00:00Z', zoneId: 'Asia/Kolkata', overrideReason: null };
const fresh = { sessionId: 17, status: 'DRAFT', version: 3, coordinatorUserId: 91, items: [], linkedRequestVersions: [], representativeUserId: null };
const approval = { session: { ...fresh, status: 'SCHEDULED', version: 4, representativeUserId: 82, scheduledAt: body.scheduledAt }, decisionId: 2, override: false };
const source = loader.ts.createSourceFile('workspace.tsx', readFileSync(new URL('../components/operations/OperationsWorkspace.tsx', import.meta.url), 'utf8'), loader.ts.ScriptTarget.Latest, true, loader.ts.ScriptKind.TSX);
let callback;
function visit(node) {
  if (loader.ts.isVariableDeclaration(node) && node.name.getText(source) === 'reconcileScheduling') callback = node.initializer.arguments[0].getText(source);
  loader.ts.forEachChild(node, visit);
}
visit(source);
const compiled = loader.ts.transpileModule('globalThis.callback = ' + callback, { compilerOptions: { target: loader.ts.ScriptTarget.ES2022, module: loader.ts.ModuleKind.ESNext } }).outputText;
function setup() {
  pending.clear(); h.signIn({ id: 91, role: 'EMPLOYEE', token: 'closure-account' });
  const session = h.session.captureOperationsSession(), intent = intents.preserveSchedulingIntent(session, 17, body);
  const outcomes = [], applied = [], calls = []; let locked = false, valid = true;
  const scope = { AbortController, setTimeout, clearTimeout, JSON, Error, ApiRequestError, ...intents,
    detail: { status: 'ready', snapshot: { session: fresh } },
    ownership: { isLocked: () => locked, begin: () => { locked = true; return { session, controller: new AbortController() }; },
      canCommit: () => valid && h.session.isOperationsSessionActive(session), markDispatched() {}, finish: () => { locked = false; return true; } },
    captureOperationsSession: h.session.captureOperationsSession, scheduleDrawerRef: { current: { sessionId: 17, generation: 1 } },
    authorityRef: { current: { authorizationGeneration: () => 1 } }, openRootRef: { current: null },
    scheduleOutcome: null,
    setBusyCommand() {}, setScheduleOutcome: x => { outcomes.push(x); scope.scheduleOutcome = x; }, scheduleOutcomeContextHolds: () => valid,
    applyAuthoritativeSession: x => { applied.push(x); return true; }, refreshAll: async () => 'loaded',
    isOperationsSessionChangedError: h.session.isOperationsSessionChangedError,
    reportCommandFailure: async () => { calls.push('denied'); }, operationsRecordTitle: () => 'Session 17',
    operationsWorkspaceService: {
      schedulingCommand: async () => { calls.push('receipt'); return null; },
      sessionDetail: async () => { calls.push('current'); return fresh; },
      selectGroundExecutive: async (id, payload) => { calls.push({ id, payload }); return approval; }
    }
  };
  vm.runInNewContext(compiled, scope);
  return { scope, intent, session, outcomes, applied, calls, run: retry => scope.callback(retry), invalidate: () => { valid = false; } };
}
test('NOT_RECORDED plus owned unchanged DRAFT offers explicit retry without sending automatically', async () => {
  const c = setup(); await c.run();
  assert.equal(c.outcomes.at(-1).retryCommandId, c.intent.commandId);
  assert.deepEqual(c.calls, ['receipt','current']); assert.equal(pending.size, 1);
});
test('explicit retry rechecks and sends exact original UUID and immutable payload once under concurrent clicks', async () => {
  const c = setup(); await c.run();
  await Promise.all([c.run(true),c.run(true)]);
  const posts = c.calls.filter(x => typeof x === 'object'); assert.equal(posts.length, 1);
  assert.deepEqual(JSON.parse(JSON.stringify(posts[0])), { id: 17, payload: { ...body, commandId: c.intent.commandId } });
  assert.equal(pending.size, 0); assert.equal(c.outcomes.at(-1).currentSession.status, 'DRAFT');
});
test('changed state/version/owner never permits retry and preserves unresolved intent', async () => {
  for (const changed of [{ status: 'CANCELLED' }, { version: 4 }, { coordinatorUserId: 92 }]) {
    const c = setup(); await c.run(); c.scope.operationsWorkspaceService.sessionDetail = async () => ({ ...fresh, ...changed });
    await c.run(true); assert.equal(c.calls.some(x => typeof x === 'object'), false);
    assert.equal(c.outcomes.at(-1).retryCommandId, undefined); assert.equal(pending.size, 1);
  }
});
test('completed receipt reconciles original acceptance with authoritative cancellation even if queues fail', async () => {
  const c = setup(); const cancelled = { ...approval.session, status: 'CANCELLED', version: 5 };
  c.scope.operationsWorkspaceService.schedulingCommand = async () => approval;
  c.scope.operationsWorkspaceService.sessionDetail = async () => cancelled;
  c.scope.refreshAll = async () => 'failed'; await c.run();
  assert.equal(c.outcomes.at(-1).approval.session.status, 'SCHEDULED');
  assert.equal(c.outcomes.at(-1).currentSession.status, 'CANCELLED');
  assert.equal(c.outcomes.at(-1).refreshFailed, true); assert.equal(c.calls.some(x => typeof x === 'object'), false);
});
test('denial and late responses after account switch or drawer replacement cannot enable retry or POST', async () => {
  const denied = setup(); await denied.run(); denied.scope.operationsWorkspaceService.sessionDetail = async () => { throw new ApiRequestError('denied',403); };
  await denied.run(true); assert.equal(denied.calls.some(x => typeof x === 'object'), false);
  assert.equal(denied.calls.includes('denied'), true);
  for (const change of ['account','drawer']) {
    const c = setup(), deferred = h.deferred(); c.scope.operationsWorkspaceService.sessionDetail = () => deferred.promise;
    const result = c.run(); await new Promise(resolve => setTimeout(resolve,0));
    if (change === 'account') h.signIn({ id: 92, role: 'EMPLOYEE', token: 'replacement' }); else c.invalidate();
    deferred.resolve(fresh); await result; assert.equal(c.outcomes.length, 0); assert.equal(c.calls.some(x => typeof x === 'object'), false);
  }
});
test('retry is never enabled before the current owned Session read succeeds; a changed saved payload is refused', async () => {
  const c = setup(), deferred = h.deferred();
  c.scope.operationsWorkspaceService.sessionDetail = () => deferred.promise;
  const result = c.run(); await new Promise(resolve => setTimeout(resolve,0));
  assert.equal(c.outcomes.length,0);
  const key=[...pending.keys()][0]; const changed=JSON.parse(pending.get(key));changed.body.groundExecutiveUserId=83;
  pending.set(key,JSON.stringify(changed));deferred.resolve(fresh);await result;
  assert.equal(c.outcomes.at(-1).retryCommandId,undefined);assert.equal(c.calls.some(x => typeof x === 'object'),false);
});
test('lost retry acknowledgement preserves the original intent and removes retry eligibility until another check', async () => {
  const c=setup();await c.run();
  c.scope.operationsWorkspaceService.selectGroundExecutive=async()=>{throw Error('connection interrupted');};
  await c.run(true);assert.equal(c.outcomes.at(-1).kind,'uncertain');assert.equal(c.outcomes.at(-1).retryCommandId,undefined);
  assert.equal(intents.readSchedulingIntent(c.session,17).commandId,c.intent.commandId);
});
test('refresh after accepted cancellation keeps its historical receipt and reads fresh truth without a pending intent', async () => {
  const c=setup();const cancelled={...approval.session,status:'CANCELLED',version:5};
  c.scope.operationsWorkspaceService.schedulingCommand=async()=>approval;
  c.scope.operationsWorkspaceService.sessionDetail=async()=>cancelled;
  await c.run();assert.equal(pending.size,0);await c.run();
  assert.equal(c.outcomes.at(-1).currentSession.status,'CANCELLED');
  assert.equal(c.outcomes.at(-1).approval.session.status,'SCHEDULED');
});
test('accepted cancellation survives repeated read failures and recovers with GETs after the intent is cleared', async () => {
  const c=setup(), cancelled={...approval.session,status:'CANCELLED',version:5};
  c.scope.operationsWorkspaceService.schedulingCommand=async()=>approval;
  c.scope.operationsWorkspaceService.sessionDetail=async()=>cancelled;
  await c.run(); assert.equal(pending.size,0);
  c.scope.operationsWorkspaceService.sessionDetail=async()=>{throw Error('temporary transport loss');};
  await c.run(); await c.run();
  const failed=c.outcomes.at(-1);
  let recoveryReads=0;
  c.scope.operationsWorkspaceService.sessionDetail=async()=>{recoveryReads++;return cancelled;};
  await c.run(); await c.run();
  console.log('Recovery negative/control facts:',JSON.stringify({recoveryReads,failedKind:failed.kind,pending:pending.size}));
  assert.equal(recoveryReads,2);
  assert.equal(failed.approval.session.status,'SCHEDULED');
  assert.equal(failed.currentSession.status,'CANCELLED');
  assert.equal(failed.verificationFailed,true);
  assert.equal(c.outcomes.at(-1).verificationFailed,false);
  assert.equal(c.outcomes.at(-1).currentSession.status,'CANCELLED');
  assert.equal(pending.size,0); assert.equal(c.calls.some(x=>typeof x==='object'),false);
});
test('a completed receipt remains accepted when the first current Session read fails', async()=>{
  const c=setup();c.scope.operationsWorkspaceService.schedulingCommand=async()=>approval;
  c.scope.operationsWorkspaceService.sessionDetail=async()=>{throw Error('transport loss');};
  await c.run();
  assert.equal(c.outcomes.at(-1).kind,'scheduled');
  assert.equal(c.outcomes.at(-1).approval.decisionId,approval.decisionId);
  assert.equal(c.outcomes.at(-1).currentSession,null);
  assert.equal(c.outcomes.at(-1).verificationFailed,true);assert.equal(pending.size,0);
  c.scope.operationsWorkspaceService.sessionDetail=async()=>({...approval.session,status:'CANCELLED',version:5});
  await c.run(true);assert.equal(c.calls.some(x=>typeof x==='object'),false);
  await c.run();assert.equal(c.outcomes.at(-1).currentSession.status,'CANCELLED');
});
test('accepted recovery needs no pending-intent storage access',async()=>{
  const c=setup();c.scope.operationsWorkspaceService.schedulingCommand=async()=>approval;
  c.scope.operationsWorkspaceService.sessionDetail=async()=>({...approval.session,status:'CANCELLED',version:5});
  await c.run();const getItem=sessionStorage.getItem;
  sessionStorage.getItem=()=>{throw Error('storage unavailable');};
  try{await c.run();assert.equal(c.outcomes.at(-1).verificationFailed,false);
    assert.equal(c.outcomes.at(-1).currentSession.status,'CANCELLED');}
  finally{sessionStorage.getItem=getItem;}
});
test('accepted recovery ignores late success and failure after account or drawer changes',async()=>{
  for(const change of ['account','drawer'])for(const failure of [false,true]){
    const c=setup();c.scope.operationsWorkspaceService.schedulingCommand=async()=>approval;
    c.scope.operationsWorkspaceService.sessionDetail=async()=>({...approval.session,status:'CANCELLED',version:5});
    await c.run();const count=c.outcomes.length,deferred=h.deferred();
    c.scope.operationsWorkspaceService.sessionDetail=()=>deferred.promise;
    const running=c.run();await new Promise(resolve=>setTimeout(resolve,0));
    if(change==='account')h.signIn({id:92,role:'EMPLOYEE',token:'replacement'});else c.invalidate();
    if(failure)deferred.reject(Error('transport loss'));else deferred.resolve(approval.session);
    await running;assert.equal(c.outcomes.length,count);assert.equal(pending.size,0);
    assert.equal(c.calls.some(x=>typeof x==='object'),false);
  }
});
test('remounted actual React result labels retained state as last verified and offers a safe check',()=>{
  for(const currentSession of [{...approval.session,status:'CANCELLED',version:5},null]){
    const html=renderToStaticMarkup(React.createElement(OperationsScheduleFlow,{session:fresh,blocked:false,
      outcome:{kind:'scheduled',approval,currentSession,refreshFailed:true,verificationFailed:true},
      onClose(){},onAcknowledgeOutcome(){},onConfirm(){},loadRanked(){},loadDiscovery(){},onRefreshSession(){},onRetryScheduling(){}}));
    assert.match(html,/Check the session/);assert.match(html,/temporarily unavailable/i);
    assert.match(html,/Pathome accepted the booking request/);
    assert.doesNotMatch(html,/The visit is scheduled\.|Current Session:|current Session details were verified|Retry scheduling/);
    if(currentSession)assert.match(html,/Last confirmed visit plan status: CANCELLED/);
    else assert.doesNotMatch(html,/SCHEDULED/);
  }
});
test('actual React result exposes CURRENT CANCELLED and never asserts Scheduled when work-list reload fails', () => {
  const cancelled = { ...approval.session, status: 'CANCELLED', version: 5 };
  for (const refreshFailed of [false,true]) {
    const html = renderToStaticMarkup(React.createElement(OperationsScheduleFlow, { session: cancelled, blocked: false,
      outcome: { kind:'scheduled', approval, currentSession: cancelled, refreshFailed },
      onClose() {}, onAcknowledgeOutcome() {}, onConfirm() {}, loadRanked() {}, loadDiscovery() {}, onRefreshSession() {}, onRetryScheduling() {} }));
    assert.match(html, /CANCELLED/); assert.doesNotMatch(html, /Scheduled, but|The visit is scheduled\./);
  }
});
test('actual React result uses later execution statuses and rescheduled time rather than the historical approval', () => {
  for(const status of ['STARTED','COMPLETED','SCHEDULED']) {
    const current={...approval.session,status,version:6,scheduledAt:'2027-02-15T06:00:00Z'};
    const html=renderToStaticMarkup(React.createElement(OperationsScheduleFlow,{session:current,blocked:false,
      outcome:{kind:'scheduled',approval,currentSession:current,refreshFailed:false},
      onClose(){},onAcknowledgeOutcome(){},onConfirm(){},loadRanked(){},loadDiscovery(){},onRefreshSession(){},onRetryScheduling(){}}));
    assert.match(html,new RegExp(status));
    assert.equal(html.includes(h.presentation.formatOperationsDateTime(current.scheduledAt)),true);
    assert.equal(html.includes(h.presentation.formatOperationsDateTime(approval.session.scheduledAt)),false);
    if(status!=='SCHEDULED')assert.doesNotMatch(html,/The visit is scheduled\./);
  }
});
