/**
 * Executes the production availability save callback, guarded runner, protected-root withdrawal and
 * ownership registry against controlled reads and transport. This is the handler layer that exposes
 * pre-dispatch races; it does not mount React or contact HTTP/PostgreSQL.
 */
import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';
import { OwnershipOperationRegistry, operationTargetIsMounted } from '../../utils/operationsOwnershipRegistry.ts';
import { ApiRequestError } from '../../services/apiError.ts';
import {
  OperationsSessionChangedError,
  isOperationsSessionActive,
  isOperationsSessionChangedError
} from '../../utils/operationsSession.ts';

const require = createRequire(new URL('../../../package.json', import.meta.url));
const ts = require('typescript');
const WORKSPACE = fileURLToPath(
  new URL('../../components/operations/OperationsWorkspace.tsx', import.meta.url)
);

const EXTRACTED_NAMES = [
  'beginAvailabilityDraft',
  'discardAvailabilityDraft',
  'changeAvailabilityDraft',
  'applyAuthoritativeSession',
  'clearAvailabilityDraftIfGeneration',
  'mountedTargetFor',
  'reportCommandFailure',
  'runGuardedCommand',
  'saveAvailability',
  'withdrawDeniedRoot'
];

const compileProductionCallbacks = () => {
  const source = ts.createSourceFile(
    WORKSPACE,
    readFileSync(WORKSPACE, 'utf8'),
    ts.ScriptTarget.Latest,
    true,
    ts.ScriptKind.TSX
  );
  const bodies = new Map();
  const visit = (node) => {
    if (ts.isVariableDeclaration(node) && EXTRACTED_NAMES.includes(node.name.getText(source))) {
      const initializer = node.initializer?.arguments?.[0];
      if (initializer) bodies.set(node.name.getText(source), initializer.getText(source));
    }
    ts.forEachChild(node, visit);
  };
  visit(source);
  for (const name of EXTRACTED_NAMES) {
    if (!bodies.has(name)) throw new Error(`${name} is no longer a useCallback in ${WORKSPACE}`);
  }
  return ts.transpileModule(
    [...bodies].map(([name, body]) => `globalThis.${name} = ${body};`).join('\n'),
    { compilerOptions: { target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.React } }
  ).outputText;
};

const COMPILED = compileProductionCallbacks();

const deferred = () => {
  let resolve;
  let reject;
  const promise = new Promise((next, fail) => {
    resolve = next;
    reject = fail;
  });
  return { promise, resolve, reject };
};

export const createProductionAvailabilityHarness = (presentation, options = {}) => {
  const state = {
    puts: [],
    gets: [],
    details: [],
    successes: [],
    notices: [],
    refreshes: 0,
    withdraws: 0,
    displayTarget: { targetType: 'SESSION', targetId: 3108 },
    authorizationGeneration: 1,
    authorized: true,
    active: true
  };
  const session = options.session ?? {
    token: 'local-test-only',
    userId: 41,
    role: 'EMPLOYEE',
    identity: '41|EMPLOYEE|82b8fb74',
    generation: 0
  };
  const registerItemId = options.registerItemId ?? 91;
  const draftRef = {
    current: presentation.createAvailabilityDraft({
      sessionId: 3108,
      sessionVersion: options.before?.version ?? 6,
      item: options.before?.items?.[0],
      identity: session.identity,
      authorizationGeneration: 1,
      editorGeneration: 1
    })
  };
  let displayed = options.before;
  const reads = [...(options.reads ?? [options.before])];
  const ownership = new OwnershipOperationRegistry();
  const target = {
    targetType: 'SESSION',
    targetId: 3108,
    mountGeneration: 0
  };
  const authority = {
    authorizationGeneration: () => state.authorizationGeneration,
    isDispatchAuthorized: () => state.authorized,
    verifySession: async () => state.active && state.authorized
  };

  const writeDraft = (next) => { draftRef.current = next; };
  const context = {
    ...presentation,
    ApiRequestError,
    OperationsSessionChangedError,
    AbortController,
    operationTargetIsMounted,
    ownership,
    authorityRef: { current: authority },
    accessGranted: true,
    availabilityDraftRef: draftRef,
    availabilityEditorGeneration: { current: 1 },
    availabilitySettlementFlights: { current: new Map() },
    openRootRef: { current: target },
    detailController: { current: null },
    writeAvailabilityDraft: writeDraft,
    captureOperationsSession: () => (state.active ? session : null),
    isOperationsSessionActive,
    isOperationsSessionChangedError,
    setBusyCommand: () => {},
    setNotice: () => {},
    setDetail: (next) => {
      state.details.push(next);
      if (next?.snapshot?.kind === 'session') displayed = next.snapshot.session;
    },
    setDisplayContext: (next, operation) => {
      state.displayTarget = next;
      const generation = ownership.advanceDisplayContext();
      if (operation) ownership.markAcceptedDisplayContext(operation);
      return generation;
    },
    abortGuardedReads: () => {},
    closeScheduleDrawer: () => {},
    notifySuccess: (...args) => state.successes.push(args),
    showNotice: (...args) => state.notices.push(args),
    missingVersionNotice: () => state.notices.push('missing version'),
    noticeContextHolds: (operation) =>
      operation.target === null
      || operationTargetIsMounted(operation, context.openRootRef.current)
      || ownership.withdrewOwnDisplayContext(operation),
    commandContextHolds: (outcome) =>
      outcome.operation !== null && ownership.displayContextIsOwned(outcome.operation),
    refreshAll: async () => {
      state.refreshes += 1;
      if (options.refreshCause) throw options.refreshCause;
      return options.refreshOutcome ?? 'loaded';
    },
    operationsWorkspaceService: {
      async sessionDetail() {
        state.gets.push('GET');
        const next = reads.length > 0 ? reads.shift() : options.before;
        if (next instanceof Error) throw next;
        if (typeof next === 'function') return next();
        return next;
      },
      async setSessionItemAvailability(sessionId, itemId, body, requestContext) {
        requestContext.onDispatch();
        state.puts.push({ sessionId, itemId, body });
        if (options.onPut) return options.onPut({ state, draftRef, ownership });
        if (options.lost) throw new Error('lost response');
        return options.applied ?? displayed;
      }
    }
  };
  Object.defineProperty(context, 'detail', {
    get: () => ({
      status: displayed ? 'ready' : 'idle',
      snapshot: displayed ? { kind: 'session', session: displayed } : null
    })
  });
  let source = COMPILED;
  if (options.sourceMutation) {
    source = options.sourceMutation(COMPILED);
    if (source === COMPILED) throw new Error('test fault mutation did not alter production code');
  }
  vm.runInNewContext(source, context);
  const productionWithdrawDeniedRoot = context.withdrawDeniedRoot;
  context.withdrawDeniedRoot = (source) => {
    const cleared = productionWithdrawDeniedRoot(source);
    if (cleared) state.withdraws += 1;
    return cleared;
  };

  return {
    context,
    begin: context.beginAvailabilityDraft,
    discard: context.discardAvailabilityDraft,
    change: context.changeAvailabilityDraft,
    state,
    draftRef,
    ownership,
    settlementFlights: context.availabilitySettlementFlights.current,
    sessionChangedError: OperationsSessionChangedError,
    target,
    save: context.saveAvailability,
    setDisplayed: (next) => { displayed = next; },
    holdRead() {
      const gate = deferred();
      reads.unshift(() => gate.promise);
      return gate;
    }
  };
};
