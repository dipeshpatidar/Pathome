import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { ApiRequestError } from '../services/apiError';
import { operationsWorkspaceService } from '../services/operationsWorkspaceService';
import {
  captureOperationsSession,
  isOperationsSessionActive,
  isOperationsSessionChangedError,
  sameOperationsCredential,
  subscribeOperationsSession,
  type OperationsSession
} from '../utils/operationsSession';
import {
  hasOperationsWorkspaceAccess,
  type StaffCapabilities
} from '../utils/operationsWorkspacePresentation';

/**
 * Runtime authority state for the Operations (OE) workspace.
 *
 * - `checking`   — the initial verification (or the verification after an account change) is still
 *                  running. No protected data may be shown or fetched.
 * - `granted`    — the backend confirmed `staffActive` plus an effective `OPS_COORDINATE` Team grant.
 * - `restricted` — the backend explicitly denied it (an eligible-looking response without the grant,
 *                  or 401/403). Authority is revoked and the entry point is withdrawn.
 * - `unavailable`— authority could not be verified right now (network, timeout, 5xx). This is a
 *                  recoverable state: protected data is hidden, mutations are blocked, and Retry is
 *                  offered.
 */
export type OperationsAuthorityState = 'checking' | 'granted' | 'restricted' | 'unavailable';

export interface OperationsAuthority {
  state: OperationsAuthorityState;
  capabilities: StaffCapabilities | null;
  /** Entry-point visibility: granted, or still trusted from the last verified grant. */
  canEnter: boolean;
  /**
   * Admission for the workspace entry that is on screen *right now*: a verification that actually
   * succeeded for this exact session **and** this exact entry. It is false while a new entry's check
   * is pending, so protected work is withheld from the very first render of that entry rather than
   * after an effect has run.
   */
  entryAdmitted: boolean;
  /** Manual retry from the recoverable `unavailable` state. */
  revalidate: () => void;
  /**
   * Fresh re-verification bound to one captured session. Resolves `true` only when that exact
   * session is still signed in and eligible, so a caller can refuse to dispatch a mutation. An
   * optional signal cancels the check itself when the operation is abandoned, and an aborted check
   * never counts as a verdict.
   */
  verifySession: (session: OperationsSession, options?: { signal?: AbortSignal }) => Promise<boolean>;
  /**
   * Monotonic authorization generation. It changes whenever authority is invalidated — a denial, a
   * transient failure, a session change, an unmount — so an ownership operation can capture it
   * before it awaits anything and refuse to dispatch once it no longer matches.
   */
  authorizationGeneration: () => number;
  /**
   * Synchronous dispatch gate for one exact session: the newest settled verdict is a grant produced
   * for this exact credential, no newer capability check is pending, and the session is still
   * current. Evaluated in the same tick as the transmission, with no `await` in between.
   */
  isDispatchAuthorized: (session: OperationsSession) => boolean;
}

/** Approved lightweight revalidation cadence while the workspace is visible. */
export const OPERATIONS_REVERIFICATION_INTERVAL_MS = 30_000;

/**
 * Why a capability check was issued. The source decides whether it may be shared with a check that is
 * already running, and nothing else:
 *
 * - `background` — the approved initial/focus/visibility/cadence revalidation. These never stack.
 * - `entry`      — the fresh check every new workspace entry requires. Two requests for the *same*
 *                  entry and session are one verification, so a new entry issues exactly one read.
 * - `mutation`   — a claim/release pre-flight check. It is deliberately never shared: it must be
 *                  issued for the mutation that asked for it, in the same tick, to stay a real
 *                  re-authorisation of that command.
 */
type VerificationSource = 'background' | 'entry' | 'mutation';

/**
 * One in-flight capability check, owned by the exact session, workspace entry and verification purpose
 * that issued it, together with the controller that can abort its transport.
 *
 * The three parts of that ownership are what let the lifecycle be decided correctly: a check is
 * *entry-bound* when it was issued for a workspace entry (the `entry` verification and any
 * `background` revalidation of that entry), while a `mutation` check belongs to the single ownership
 * operation that asked for it. Retiring entry-bound checks by their entry generation is what stops an
 * obsolete entry's verification from gating a later, valid mutation — without ever touching the
 * current operation's own security check.
 */
interface PendingCapabilityCheck {
  session: OperationsSession;
  source: VerificationSource;
  /** The workspace-entry generation this check was issued for. */
  entryGeneration: number;
  control: AbortController;
  /**
   * Removes this check's caller (operation) abort listener. Idempotent, so every retirement path can
   * detach without the listener accumulating on a long-lived signal.
   */
  detach: () => void;
}

/**
 * The newest capability check the hook keeps a pointer to, so a new caller can reuse it instead of
 * making the transport carry a duplicate read: its unique request identity, the ownership facts a new
 * caller has to match, and the promise that settles with its verdict.
 *
 * The pointer is *not* the authority for what may be reused. A check can be retired — a committed
 * workspace-entry transition, an account switch, or a cancelled caller — and a retired verification's
 * promise deliberately never settles, so only the pending-check registry can say whether a check is
 * still live. See `isInFlightCheckShareable`.
 */
interface InFlightCapabilityCheck {
  id: number;
  session: OperationsSession;
  source: VerificationSource;
  entryGeneration: number;
  promise: Promise<boolean>;
}

/**
 * Retires every entry-bound capability check that a workspace-entry change has made obsolete: its gate
 * entry is removed and its transport is aborted in the same tick, so an obsolete entry's verification
 * can neither keep gating a mutation nor be counted as belonging to the entry now on screen. The
 * removal is immediate and deliberately does not depend on the aborted promise settling.
 *
 * This runs only from the commit-phase entry transition, so an abandoned speculative render can never
 * reach it.
 *
 * A `mutation` check is left alone: it is the *current ownership operation's own* security check and
 * owns its lifecycle (its operation aborts it), so it must never be retired as though it were an
 * obsolete entry check. Checks issued for the entry on screen (same generation) are untouched, which is
 * what keeps a genuinely current, still-pending revalidation failing the dispatch gate closed.
 */
const retireObsoleteEntryChecks = (
  pending: Map<number, PendingCapabilityCheck>,
  currentEntryGeneration: number
): void => {
  for (const [id, check] of pending) {
    if (check.source === 'mutation') continue;
    if (check.entryGeneration === currentEntryGeneration) continue;
    check.detach();
    pending.delete(id);
    check.control.abort();
  }
};

/**
 * Whether a capability check that has already been issued may be reused by a new caller instead of the
 * hook transmitting a fresh read.
 *
 * `pending` is the live pending-check registry — the authority for what is still running — and `shared`
 * is the newest issued check the hook keeps a pointer to. Reuse requires all of:
 *
 * - the check is still registered and its transport is not aborted. Every retirement path (a committed
 *   workspace-entry transition, an account switch, the caller's own cancellation) removes the gate
 *   entry in the same tick it aborts the transport, and a retired verification's promise deliberately
 *   never settles, so a retired check could not produce a verdict even if it were awaited;
 * - the registered record is the one the pointer was taken from (same request identity, purpose and
 *   entry generation), so the pointer can never describe a check that has since been replaced;
 * - the initiating session is the exact session that is asking now, never a previous account's;
 * - the purpose is compatible: a mutation pre-flight is never shared in either direction, because it
 *   must stay a real re-authorisation of the command that asked for it;
 * - the check belongs to the workspace entry on screen now, so a superseded entry generation is never
 *   reused;
 * - an `entry` verification is only ever satisfied by the entry check already running for that same
 *   entry, while a `background` revalidation may additionally adopt an in-progress entry check — which
 *   is what keeps one entry from issuing two reads.
 */
const isInFlightCheckShareable = (
  pending: ReadonlyMap<number, PendingCapabilityCheck>,
  shared: InFlightCapabilityCheck,
  session: OperationsSession,
  source: VerificationSource,
  entryGeneration: number
): boolean => {
  const registered = pending.get(shared.id);
  if (registered === undefined || registered.control.signal.aborted) return false;
  if (registered.source !== shared.source || registered.entryGeneration !== shared.entryGeneration) return false;
  if (!sameOperationsCredential(registered.session, session)) return false;
  if (source === 'mutation' || shared.source === 'mutation') return false;
  if (shared.entryGeneration !== entryGeneration) return false;
  return source === 'background' || shared.source === 'entry';
};

export interface UseOperationsCapabilityOptions {
  /**
   * Identity of the Operations surface currently on screen — the dedicated destination, an embedded
   * console tab — or null when no Operations surface is open.
   *
   * Any change is a *new entry*: the previous entry's verification never carries over, even when both
   * surfaces are individually "open". Naming the surface is what makes an embedded workspace
   * transitioning to `/operations` re-verify instead of inheriting the earlier verdict.
   */
  workspaceEntry?: string | null;
}

export const useOperationsCapability = (
  options: UseOperationsCapabilityOptions = {}
): OperationsAuthority => {
  const workspaceEntry = options.workspaceEntry ?? null;
  const workspaceOpen = workspaceEntry !== null;
  const [state, setState] = useState<OperationsAuthorityState>('checking');
  const [capabilities, setCapabilities] = useState<StaffCapabilities | null>(null);

  /**
   * The workspace entry React has actually *committed*, and its generation.
   *
   * Render is side-effect free. A render pass only *derives* whether the surface it was asked to render
   * is the committed one; committed ownership advances exclusively in the commit-phase
   * `useLayoutEffect` below. A render that React starts and then suspends, interrupts or discards (a
   * concurrent transition that never commits) therefore cannot advance ownership, abort a live
   * transport or retire the still-committed entry's checks.
   *
   * The ref is the synchronous source of truth for continuations (a grant comparison, a dispatch gate);
   * the state mirror drives the render gate and the verification effect, and both are set in the same
   * commit. Nothing is committed until React commits the first render, so the very first paint also
   * fails closed: admission is refused until a verification for the committed entry succeeds.
   */
  const [committedEntry, setCommittedEntry] = useState<{ key: string | null; generation: number } | null>(null);
  const committedEntryRef = useRef<{ key: string | null; generation: number } | null>(null);
  /**
   * The one settled admission, bound to the entry generation and the exact session that earned it. An
   * older entry's — or another account's — completion can never match a newer entry generation, so a
   * stale verdict can never admit the workspace.
   */
  const [admission, setAdmission] = useState<{ generation: number; session: OperationsSession } | null>(null);

  const mountedRef = useRef(true);
  /** Monotonic request generation: an older response can never overwrite a newer verdict. */
  const requestRef = useRef(0);
  const inFlightRef = useRef<InFlightCapabilityCheck | null>(null);
  /**
   * In-flight capability checks, keyed by the unique id of each check and tagged with both the exact
   * session it was issued for, the workspace-entry generation it belongs to, its verification purpose,
   * the controller that can abort its transport and the detach hook that removes the initiating
   * operation's abort listener. A pending check fails the dispatch gate closed — but only while it is
   * live for the entry on screen and for the session it belongs to.
   *
   * Two lifecycles retire a check, and both abort its transport and remove its gate entry in one tick:
   *   - an account switch retires every check that no longer belongs to the current session, so a
   *     verification left pending by a previous account can never block the new account's claim; and
   *   - a committed workspace-entry change retires every *entry-bound* check of the superseded entry, so
   *     the same account leaving and re-entering Operations is not blocked by the earlier entry's
   *     pending check. That retirement happens in the commit-phase transition, never during render.
   *
   * Cleanup is deliberately keyed by the check's own id: a retired check's finalizer removes only its
   * own entry, so it can never clear, decrement or otherwise corrupt a newer check's gate.
   */
  const pendingChecksRef = useRef<Map<number, PendingCapabilityCheck>>(new Map());
  /**
   * The newest *settled* verdict, kept synchronously so a dispatch gate running inside a promise
   * continuation never reads a stale React state. `generation` only changes when authority is
   * invalidated, so an operation that captured it can tell the difference between "still granted"
   * and "a newer check, denial or transient failure took it away".
   */
  const verdictRef = useRef<{ state: OperationsAuthorityState; session: OperationsSession | null; generation: number }>({
    state: 'checking',
    session: null,
    generation: 0
  });
  /** Last *verified* eligibility. A transient failure keeps the entry point but never the data. */
  const verifiedGrantRef = useRef(false);
  /*
   * No ref is written anywhere during a render pass of this hook.
   *
   * The focus / visibility / cadence handlers must not re-bind on every verdict, so they read the
   * authority they need from refs — but only from committed facts: `committedEntryRef` (advanced
   * exclusively in the commit-phase layout effect below) is the *committed* workspace ownership, and
   * `verdictRef` (written only from callbacks and continuations) is the newest *settled* verdict. See
   * `revalidateOnActivity`. Mirroring the requested `workspaceEntry` prop, or a render-pass state
   * value, into a ref would let a render React starts and then suspends, interrupts or discards change
   * what those handlers believe is on screen even though the committed surface never changed — a
   * discarded exit render must not suppress the still-committed entry's revalidation, and a discarded
   * open render must not start polling for a committed closed surface.
   */

  const applyGrant = useCallback((
    session: OperationsSession,
    result: StaffCapabilities,
    admittedEntry: number
  ): void => {
    verifiedGrantRef.current = true;
    verdictRef.current = { state: 'granted', session, generation: verdictRef.current.generation };
    if (!mountedRef.current) return;
    // Admission is entry-scoped: a verdict produced for an earlier entry can never admit a later one,
    // even when the same account and credential produced it.
    if (admittedEntry === committedEntryRef.current?.generation) setAdmission({ generation: admittedEntry, session });
    setCapabilities(result);
    setState('granted');
  }, []);

  /**
   * Retires every in-flight check that does not belong to `current` (and every check when `current`
   * is null, i.e. after a logout): the check's transport is aborted and its gate entry is removed, so
   * an obsolete verification can neither keep gating dispatch nor be counted as belonging to a newer
   * session. Because the gate and the cleanup are both keyed by the check's own id, retiring a check
   * here is safe — its own finalizer simply finds nothing left to remove and can never corrupt a
   * newer session's active checks.
   */
  const retireObsoleteChecks = useCallback((current: OperationsSession | null): void => {
    for (const [id, check] of pendingChecksRef.current) {
      if (current !== null && sameOperationsCredential(check.session, current)) continue;
      check.detach();
      pendingChecksRef.current.delete(id);
      check.control.abort();
    }
  }, []);

  /**
   * Records a non-granted verdict. The authorization generation moves forward, which invalidates
   * every ownership operation that captured the previous generation — an older in-flight grant can
   * therefore never be used to dispatch after a newer denial or transient failure.
   *
   * An explicit denial also revokes the entry point. A transient failure only withdraws the protected
   * data and the mutations: it leaves the last verified eligibility (and therefore the entry point)
   * intact, exactly as before, so a recoverable outage does not lock the OE out of the destination.
   */
  const applyInvalidation = useCallback((next: 'restricted' | 'unavailable'): void => {
    if (next === 'restricted') verifiedGrantRef.current = false;
    verdictRef.current = { state: next, session: null, generation: verdictRef.current.generation + 1 };
    if (!mountedRef.current) return;
    // An explicit denial withdraws the entry admission itself, so the workspace can never stay
    // admitted on a verdict that is no longer granted. A transient failure keeps it, exactly as it
    // keeps the entry point: the protected data and the mutations are withdrawn by the state above.
    if (next === 'restricted') setAdmission(null);
    setCapabilities(null);
    setState(next);
  }, []);

  const runVerification = useCallback(
    (
      session: OperationsSession | null,
      mode: { source?: VerificationSource; entryGeneration?: number; signal?: AbortSignal } = {}
    ): Promise<boolean> => {
      const source = mode.source ?? 'background';
      // The entry this check belongs to, read at issue time from the committed entry, so every verdict
      // is bound to the entry that was actually on screen when the check started.
      const entry = mode.entryGeneration ?? committedEntryRef.current?.generation ?? 0;
      if (!session) {
        requestRef.current += 1;
        inFlightRef.current = null;
        // With no signed-in session nothing may still gate a dispatch: retire (and abort) every check.
        retireObsoleteChecks(null);
        applyInvalidation('restricted');
        return Promise.resolve(false);
      }

      // Background checks never stack, and an entry check already running for this exact session and
      // entry *is* this entry's verification, so one entry never issues two reads. A mutation's
      // pre-flight check is never shared: it is issued for the mutation that asked for it.
      //
      // Sharing is conditional on the candidate still being *eligible*: a check that has been retired
      // (an obsolete entry, an account switch, a cancelled caller) is no longer in the registry, so its
      // obsolete promise is never adopted in place of the fresh read a new caller needs.
      const shared = inFlightRef.current;
      if (shared !== null && isInFlightCheckShareable(pendingChecksRef.current, shared, session, source, entry)) {
        return shared.promise;
      }
      // A check that was abandoned before it started is not a verdict, must not disturb the state, and
      // must not register a live gate: an already-aborted operation can never hold the gate shut.
      if (mode.signal?.aborted) return Promise.resolve(false);

      const id = (requestRef.current += 1);
      // The check owns its own controller so an account switch can abort it even when the caller passed
      // no signal (a background poll). A caller signal is forwarded onto the same controller.
      const control = new AbortController();
      const externalSignal = mode.signal;
      /**
       * Removes this check's caller-signal listener. Idempotent.
       */
      const detach = (): void => {
        externalSignal?.removeEventListener('abort', forwardAbort);
      };
      /**
       * Retires this check's own gate entry, keyed by its id, so it can never remove another request's
       * gate. Called synchronously the moment the owning operation is cancelled: a mutation check whose
       * transport never settles must stop failing the dispatch gate closed immediately, and must never
       * block a newer, valid operation.
       */
      const retireOwnPendingCheck = (): void => {
        const registered = pendingChecksRef.current.get(id);
        if (registered !== undefined && registered.control === control) pendingChecksRef.current.delete(id);
        if (inFlightRef.current !== null && inFlightRef.current.id === id) inFlightRef.current = null;
      };
      const forwardAbort = (): void => {
        detach();
        retireOwnPendingCheck();
        control.abort();
      };
      if (externalSignal) {
        if (externalSignal.aborted) control.abort();
        else externalSignal.addEventListener('abort', forwardAbort, { once: true });
      }
      pendingChecksRef.current.set(id, { session, source, entryGeneration: entry, control, detach });
      const promise = (async (): Promise<boolean> => {
        const isCurrent = () => mountedRef.current && id === requestRef.current;
        try {
          const result = await operationsWorkspaceService.capabilities({ session, signal: control.signal });
          // A response whose check was abandoned while it was in flight is not a verdict: it must not
          // restore authority after a newer denial or transient failure, and it must not outrun the
          // exact session it was issued for.
          if (!isCurrent()) return false;
          if (control.signal.aborted) return false;
          if (hasOperationsWorkspaceAccess(result)) {
            applyGrant(session, result, entry);
            return true;
          }
          applyInvalidation('restricted');
          return false;
        } catch (cause) {
          // An abandoned check (workspace closed, session replaced, caller aborted) is not evidence
          // about authority.
          if (control.signal.aborted || (cause as { name?: string } | null)?.name === 'AbortError') return false;
          if (!isCurrent()) return false;
          // The identity changed: a newer session owns the UI and has already been scheduled.
          if (isOperationsSessionChangedError(cause)) return false;
          const denied = cause instanceof ApiRequestError && (cause.status === 401 || cause.status === 403);
          applyInvalidation(denied ? 'restricted' : 'unavailable');
          return false;
        } finally {
          detach();
          // Remove only this check's own entry: a retired check that settles late can never subtract
          // from, clear or otherwise disturb a newer session's pending checks.
          pendingChecksRef.current.delete(id);
          if (inFlightRef.current?.id === id) inFlightRef.current = null;
        }
      })();

      inFlightRef.current = { id, session, source, entryGeneration: entry, promise };
      return promise;
    },
    [applyGrant, applyInvalidation, retireObsoleteChecks]
  );

  const verifySession = useCallback(
    (session: OperationsSession, options: { signal?: AbortSignal } = {}): Promise<boolean> =>
      runVerification(session, { source: 'mutation', signal: options.signal }),
    [runVerification]
  );

  const authorizationGeneration = useCallback(() => verdictRef.current.generation, []);

  const isDispatchAuthorized = useCallback(
    (session: OperationsSession): boolean => {
      if (!mountedRef.current) return false;
      // A capability check for this exact session that is still running means the verdict is uncertain:
      // fail closed. Checks belonging to a previous account were retired on the account switch and never
      // gate these credentials, so an obsolete Account A request cannot block Account B's dispatch.
      for (const check of pendingChecksRef.current.values()) {
        if (sameOperationsCredential(check.session, session)) return false;
      }
      const verdict = verdictRef.current;
      if (verdict.state !== 'granted' || verdict.session === null) return false;
      // The grant must have been produced for this exact credential, and that credential must still
      // be the signed-in one.
      if (!sameOperationsCredential(verdict.session, session)) return false;
      // And it must have admitted the entry that is on screen now: a grant earned by an earlier entry
      // never authorises a command issued from a later one.
      const committed = committedEntryRef.current;
      if (committed === null || admission === null || admission.generation !== committed.generation
          || !sameOperationsCredential(admission.session, session)) return false;
      return isOperationsSessionActive(session);
    },
    [admission]
  );

  const revalidate = useCallback(() => {
    // A manual retry is this entry's verification: its success has to admit the entry, otherwise the
    // recoverable `unavailable` state could never be left.
    void runVerification(captureOperationsSession(), { source: 'entry' });
  }, [runVerification]);

  /**
   * The commit-phase ownership transition for the workspace entry.
   *
   * React runs a layout effect only for a render it actually committed, so this is the only place the
   * committed entry generation advances and the only place a superseded entry's obsolete checks are
   * retired. A speculative render that React suspends, interrupts or discards reaches the render gate
   * but never this effect: the still-committed entry keeps its generation, its in-flight verification
   * and its admission untouched.
   *
   * The transition is deliberately idempotent, so a StrictMode remount (which re-runs committed
   * effects) neither advances ownership again nor retires the new entry's own checks.
   */
  useLayoutEffect(() => {
    mountedRef.current = true;
    const committed = committedEntryRef.current;
    if (committed !== null && committed.key === workspaceEntry) return;
    const generation = (committed?.generation ?? 0) + 1;
    committedEntryRef.current = { key: workspaceEntry, generation };
    // Retire the superseded entry's own checks synchronously, in the commit that establishes the new
    // ownership: their gate entries are removed and their transports aborted in this tick, without ever
    // waiting for an abandoned promise to settle.
    retireObsoleteEntryChecks(pendingChecksRef.current, generation);
    // The sharing pointer must not outlive the record it points at. A transition that retires the
    // superseded entry's checks therefore also drops a pointer to a retired check, so the very next
    // caller cannot even consider an obsolete promise — it sees no candidate and transmits a fresh read.
    const pointedAt = inFlightRef.current;
    if (pointedAt !== null && !pendingChecksRef.current.has(pointedAt.id)) inFlightRef.current = null;
    setCommittedEntry({ key: workspaceEntry, generation });
  }, [workspaceEntry]);

  /**
   * The initial verification for the signed-in identity, and a fresh verification for every new
   * workspace entry.
   *
   * Leaving a surface keeps the verdict the entry point already trusts (and the approved focus,
   * visibility and cadence revalidation keep it current); entering one always requires its own
   * successful check for the exact session and the exact entry before protected work is admitted.
   *
   * It is keyed on the *committed* entry, and runs as a passive effect: a StrictMode remount (which
   * re-runs passive effects) therefore re-establishes the verification instead of leaving the committed
   * entry unverified, while an abandoned render changes nothing here.
   */
  useEffect(() => {
    mountedRef.current = true;
    if (committedEntry === null) return;
    const { key, generation } = committedEntry;
    if (key === null && verdictRef.current.state !== 'checking') return;
    void runVerification(captureOperationsSession(), {
      source: key === null ? 'background' : 'entry',
      entryGeneration: generation
    });
  }, [committedEntry, runVerification]);

  // Logout, account switch or cross-tab session replacement drops the previous account's authority.
  useEffect(() => {
    const unsubscribe = subscribeOperationsSession(() => {
      requestRef.current += 1;
      inFlightRef.current = null;
      verifiedGrantRef.current = false;
      verdictRef.current = { state: 'checking', session: null, generation: verdictRef.current.generation + 1 };
      // The previous account's in-flight checks are retired and aborted before the new account's
      // verification starts, so they can neither keep gating dispatch nor be counted as current.
      const nextSession = captureOperationsSession();
      retireObsoleteChecks(nextSession);
      if (!mountedRef.current) return;
      // No entry stays admitted across a session change: the account that is signed in now has to be
      // verified for the entry that is still on screen, even though the surface never changed.
      setAdmission(null);
      setCapabilities(null);
      setState('checking');
      const committed = committedEntryRef.current;
      void runVerification(nextSession, {
        source: committed === null || committed.key === null ? 'background' : 'entry',
        entryGeneration: committed?.generation
      });
    });
    return () => {
      mountedRef.current = false;
      requestRef.current += 1;
      inFlightRef.current = null;
      verdictRef.current = { state: 'checking', session: null, generation: verdictRef.current.generation + 1 };
      // Unmounting retires (and aborts) every pending check: no verification may outlive its consumer.
      retireObsoleteChecks(null);
      unsubscribe();
    };
  }, [runVerification, retireObsoleteChecks]);

  /**
   * Returning focus to the application and restoring visibility always re-verify while the workspace
   * is open, and also re-verify a non-granted verdict while it is closed — that is what lets a
   * restored grant (or a transient failure) recover without a reload. A granted workspace that is not
   * on screen is not polled from focus events.
   *
   * Both halves of that decision are read from committed facts — the committed entry generation's
   * ownership and the newest settled verdict — never from the requested `workspaceEntry` prop or a
   * render-pass state mirror. A render that React discards (a suspended or interrupted concurrent
   * transition) therefore cannot change whether the entry that is *actually* on screen is revalidated:
   * a discarded render of a closed surface can no longer suppress the committed open entry's check,
   * and a discarded render of an open surface can no longer start polling for a committed closed one.
   */
  const revalidateOnActivity = useCallback(() => {
    if (typeof document !== 'undefined' && document.visibilityState === 'hidden') return;
    const committed = committedEntryRef.current;
    const committedWorkspaceOpen = committed !== null && committed.key !== null;
    if (!committedWorkspaceOpen && verdictRef.current.state === 'granted') return;
    void runVerification(captureOperationsSession());
  }, [runVerification]);

  useEffect(() => {
    if (typeof window === 'undefined' || typeof document === 'undefined') return undefined;
    const onVisibilityChange = () => {
      if (document.visibilityState === 'visible') revalidateOnActivity();
    };
    window.addEventListener('focus', revalidateOnActivity);
    document.addEventListener('visibilitychange', onVisibilityChange);
    return () => {
      window.removeEventListener('focus', revalidateOnActivity);
      document.removeEventListener('visibilitychange', onVisibilityChange);
    };
  }, [revalidateOnActivity]);

  /**
   * Approved ~30s cadence, only while the workspace is open and only while it is visible.
   *
   * This is a passive effect, so it is established and torn down only by a render React actually
   * committed: `workspaceOpen` in this closure is the committed prop of that render, never the
   * speculative request of a render that was suspended, interrupted or discarded. A discarded open
   * render therefore cannot leave a cadence running for a closed surface.
   */
  useEffect(() => {
    if (!workspaceOpen) return undefined;
    if (typeof window === 'undefined' || typeof document === 'undefined') return undefined;
    const intervalId = window.setInterval(() => {
      if (document.visibilityState !== 'visible') return;
      void runVerification(captureOperationsSession());
    }, OPERATIONS_REVERIFICATION_INTERVAL_MS);
    return () => window.clearInterval(intervalId);
  }, [workspaceOpen, runVerification]);

  // The entry point follows the last verified grant; it is withdrawn the moment authority is denied.
  const canEnter = state === 'granted'
    || ((state === 'checking' || state === 'unavailable') && verifiedGrantRef.current);

  /**
   * Admission for the entry on screen now: a successful verification for this exact entry generation
   * and this exact credential. It is false the instant a new entry is *requested* - synchronously, in
   * the render that asks for it - so the workspace withholds protected content and protected reads from
   * its first render, not from an effect that runs after that render. Because the requested surface is
   * compared with the surface React actually committed, an entry change only becomes admissible after
   * the commit-phase transition advanced ownership and that committed entry's own verification
   * succeeded.
   */
  const entryAdmitted = workspaceOpen
    && committedEntry !== null
    && committedEntry.key === workspaceEntry
    && admission !== null
    && admission.generation === committedEntry.generation
    && isOperationsSessionActive(admission.session);

  return useMemo(
    () => ({
      state,
      capabilities,
      canEnter,
      entryAdmitted,
      revalidate,
      verifySession,
      authorizationGeneration,
      isDispatchAuthorized
    }),
    [
      state,
      capabilities,
      canEnter,
      entryAdmitted,
      revalidate,
      verifySession,
      authorizationGeneration,
      isDispatchAuthorized
    ]
  );
};
