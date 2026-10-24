/**
 * Harness for the P2 availability save callback.
 *
 * `saveAvailability` is extracted from its declaration in `OperationsWorkspace.tsx` and executed
 * with controlled dependencies: the real presentation helpers, a controllable guarded-command
 * runner, the real service shape over a stubbed transport, and an in-memory draft ref. This proves
 * the actual completion classification and draft lifecycle. It is a handler-level harness, not a
 * mounted browser; the mounted/browser gate remains deferred.
 */
import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const require = createRequire(new URL('../../../package.json', import.meta.url));
const ts = require('typescript');

const WORKSPACE = fileURLToPath(
  new URL('../../components/operations/OperationsWorkspace.tsx', import.meta.url)
);

const compileSaveCallback = () => {
  const source = ts.createSourceFile(
    WORKSPACE,
    readFileSync(WORKSPACE, 'utf8'),
    ts.ScriptTarget.Latest,
    true,
    ts.ScriptKind.TSX
  );
  let initializer = null;
  const visit = (node) => {
    if (ts.isVariableDeclaration(node) && node.name.getText(source) === 'saveAvailability') {
      // `const saveAvailability = useCallback(async (...) => {...}, [...])`
      initializer = node.initializer.arguments?.[0]?.getText(source) ?? null;
    }
    ts.forEachChild(node, visit);
  };
  visit(source);
  if (initializer === null) {
    throw new Error(`saveAvailability is no longer declared as a useCallback in ${WORKSPACE}`);
  }
  return ts.transpileModule(`globalThis.__p2SaveAvailability = ${initializer};`, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.React }
  }).outputText;
};

const COMPILED = compileSaveCallback();

export const SESSION_ID = 3108;
export const ITEM_ID = 91;

/**
 * One controlled workspace slice around the real save callback.
 *
 * `outcomes` is a queue of guarded-command results. `reconcile` is the authoritative detail read used
 * only after an unconfirmed outcome; `onWithdraw` mirrors the workspace's denied-root withdrawal.
 */
export const createAvailabilitySaveHarness = (presentation, options = {}) => {
  const state = {
    draft: null,
    drafts: [],
    saves: [],
    notices: [],
    successes: [],
    details: [],
    preflights: [],
    gets: [],
    puts: [],
    locked: false,
    outcomes: [...(options.outcomes ?? [])],
    detailResponses: [...(options.detailResponses ?? [])],
    freshSession: options.freshSession ?? null,
    presentedSession: options.presentedSession
      ?? (typeof options.freshSession === 'function' ? null : options.freshSession)
      ?? null,
    active: true,
    identity: '41|EMPLOYEE|deadbeef',
    authorizationGeneration: 1,
    epoch: 0,
    session: { token: 'token', userId: 41, role: 'EMPLOYEE', identity: '41|EMPLOYEE|deadbeef', generation: 0 }
  };

  /**
   * A draft is normally opened from the Session currently on screen. When a probe sets only the
   * draft and the preflight response, reconstruct that presented availability snapshot from the
   * draft's opening fields so the harness models the editor the OE actually saw.
   */
  let draftValue = null;
  Object.defineProperty(state, 'draft', {
    get: () => draftValue,
    set: (next) => {
      const newEditor = next !== null
        && (draftValue === null
          || next.editorGeneration !== draftValue.editorGeneration
          || next.itemId !== draftValue.itemId);
      draftValue = next;
      if (!newEditor || options.presentedSession !== undefined) return;
      const candidates = [
        typeof state.freshSession === 'object' ? state.freshSession : null,
        ...state.detailResponses
      ];
      const template = candidates.find((candidate) =>
        candidate && !(candidate instanceof Error) && typeof candidate === 'object'
      );
      if (!template) return;
      state.presentedSession = {
        ...template,
        version: next.openingSessionVersion ?? template.version,
        items: template.items.map((candidate) => candidate.itemId === next.itemId
          ? {
              ...candidate,
              removedAt: null,
              availabilityStartAt: next.openingStartAt,
              availabilityEndAt: next.openingEndAt,
              availabilityZoneId: next.openingZoneId,
              availabilitySource: next.openingSource
            }
          : candidate)
      };
    }
  });

  const writeDraft = (next) => {
    if (options.legacyKeepDraftOnInvalidation === true && next === null && state.active === false) {
      return;
    }
    state.draft = next;
    state.drafts.push(next);
  };
  const clearIfGeneration = (generation) => {
    if (options.ignoreGeneration === true) {
      writeDraft(null);
      return true;
    }
    if (!state.draft || state.draft.editorGeneration !== generation) return false;
    writeDraft(null);
    return true;
  };

  const service = {
    async sessionDetail(sessionId) {
      state.gets.push(sessionId);
      const next = state.detailResponses.length > 0
        ? state.detailResponses.shift()
        : (state.freshSession ?? state.presentedSession);
      if (next instanceof Error) throw next;
      const value = typeof next === 'function' ? next(sessionId, state) : next;
      return value instanceof Error ? Promise.reject(value) : value;
    },
    async setSessionItemAvailability(sessionId, itemId, body, context = {}) {
      state.puts.push({ sessionId, itemId, body });
      context.onDispatch?.();
      if (options.onPut) return options.onPut({ sessionId, itemId, body, state });
      return options.appliedSession ?? null;
    }
  };

  const context = {
    ...presentation,
    /** Controlled callback harness: production `instanceof` checks receive this test-only error type. */
    ApiRequestError: class ApiRequestError extends Error {
      constructor(message, status) {
        super(message);
        this.status = status;
      }
    },
    useCallback: (fn) => fn,
    availabilityDraftRef: { get current() { return state.draft; } },
    writeAvailabilityDraft: writeDraft,
    clearAvailabilityDraftIfGeneration: clearIfGeneration,
    captureOperationsSession: () => (state.active ? state.session : null),
    isOperationsSessionActive: () => state.active,
    authorityRef: { current: { authorizationGeneration: () => state.authorizationGeneration } },
    accessGranted: options.accessGranted ?? true,
    ownership: {
      get sessionEpoch() { return state.epoch; },
      isLocked: () => state.locked,
      begin: () => ({ target: { targetType: 'SESSION', targetId: SESSION_ID, mountGeneration: 0 } }),
      canCommit: () => true,
      finish: () => true
    },
    availabilitySettlementFlights: { current: new Map() },
    mountedTargetFor: () => ({ targetType: 'SESSION', targetId: SESSION_ID, mountGeneration: 0 }),
    openRootRef: { current: { targetType: 'SESSION', targetId: SESSION_ID } },
    withdrawDeniedRoot: () => writeDraft(null),
    setDetail: (next) => {
      state.details.push(next);
      if (next?.snapshot?.kind === 'session') state.presentedSession = next.snapshot.session;
    },
    notifySuccess: (...args) => state.successes.push(args),
    showNotice: (text) => state.notices.push(text),
    missingVersionNotice: () => state.notices.push('missing version'),
    commandContextHolds: () => options.contextHolds ?? true,
    applyAuthoritativeSession: () => options.applyResult ?? true,
    isOperationsSessionChangedError: () => false,
    operationsWorkspaceService: service,
    operationsRecordTitle: presentation.operationsRecordTitle,
    runGuardedCommand: async (guardOptions) => {
      if (state.locked) {
        return {
          status: 'abandoned', applied: null, reconciliation: 'skipped',
          failure: null, operation: null, published: false
        };
      }
      const fresh = await guardOptions.loadFresh({ session: state.session });
      if (guardOptions.contextCurrent?.() === false) {
        return {
          status: 'abandoned', applied: null, reconciliation: 'skipped',
          failure: null, operation: null, published: false, preflight: null
        };
      }
      const preflight = guardOptions.preflight?.(fresh) ?? null;
      if (preflight !== null) {
        state.preflights.push({ fresh, preflight });
        guardOptions.onPreflightRefusal?.(preflight, fresh, {});
        return {
          status: 'preflight-refused',
          applied: null,
          reconciliation: 'skipped',
          failure: null,
          preflight,
          operation: {},
          published: false
        };
      }
      const outcome = state.outcomes.shift();
      if (!outcome) throw new Error('save harness: no controlled guarded-command outcome queued');
      if (outcome === 'deferred') {
        state.locked = true;
        return new Promise((resolve) => {
          state.resolveCommand = (result) => {
            state.locked = false;
            state.resolveCommand = null;
            resolve(result);
          };
        });
      }
      const transmit = guardOptions.buildTransmit(fresh);
      if (transmit === null) {
        guardOptions.onMissingVersion(fresh, 'loaded', {});
        return {
          status: 'missing-version',
          applied: null,
          reconciliation: 'loaded',
          failure: null,
          preflight: null,
          operation: {},
          published: false
        };
      }
      if (guardOptions.contextCurrent?.() === false) {
        return {
          status: 'abandoned', applied: null, reconciliation: 'skipped',
          failure: null, operation: null, published: false, preflight: null
        };
      }
      const shouldTransmit = outcome.status === 'applied'
        || (outcome.status === 'failed' && outcome.failure?.kind !== 'pre-dispatch');
      state.saves.push({ fresh, hasTransmit: transmit !== null, outcome });
      if (shouldTransmit && transmit !== null) {
        await transmit({
          session: state.session,
          signal: new AbortController().signal,
          onDispatch: () => { state.dispatched = true; }
        });
      } else {
        state.dispatched = false;
      }
      if (outcome.status === 'applied' && transmit !== null) {
        guardOptions.applyResult(fresh, options.appliedSession ?? fresh, {});
      }
      if (outcome.status === 'failed'
        && outcome.failure
        && [403, 404].includes(outcome.failure.status)) {
        // `reportCommandFailure` withdraws the denied root before it reconciles; the workspace's
        // withdrawal clears the protected draft in the same tick.
        if (options.legacyKeepDraftOnDenial !== true) writeDraft(null);
      }
      return {
        status: outcome.status,
        applied: outcome.applied ?? null,
        reconciliation: outcome.reconciliation ?? 'loaded',
        failure: outcome.failure ?? null,
        operation: {},
        published: outcome.published ?? true,
        preflight: null
      };
    }
  };

  Object.defineProperty(context, 'detail', {
    get: () => ({
      status: state.presentedSession === null ? 'idle' : 'ready',
      snapshot: state.presentedSession === null
        ? null
        : { kind: 'session', session: state.presentedSession }
    })
  });

  vm.runInNewContext(COMPILED, context);

  return {
    state,
    save: (editorGeneration, window) => context.__p2SaveAvailability(
      editorGeneration,
      window,
      options.now ?? new Date('2026-10-22T00:00:00Z')
    ),
    setDraft(next) {
      writeDraft(next);
    },
    draft() {
      return state.draft;
    },
    /** Resolves a command that was deliberately held open for duplicate-submission tests. */
    resolveCommand(result) {
      if (!state.resolveCommand) throw new Error('no deferred command is pending');
      state.resolveCommand(result);
    }
  };
};
