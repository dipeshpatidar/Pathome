import {
  isOperationsSessionActive,
  sameOperationsCredential,
  type OperationsSession
} from './operationsSession';

/**
 * Lifecycle and ownership boundary for one logical OE ownership command (a claim or a release).
 *
 * Package 1A left two races open. A claim or release could still transmit its POST after the
 * workspace had unmounted, after the account had changed, or after authority had been withdrawn,
 * because the only guard was re-read *before* the pre-mutation read and again before dispatch with
 * no binding to the component, the workspace surface or the authorization that was in force when the
 * operation began. Separately, an abandoned Account A operation could run its `finally` after
 * Account B had started its own operation and release B's mutation lock.
 *
 * This registry closes both. One live operation at a time is bound to:
 *   - the component lifecycle (unmount cancels it),
 *   - the workspace surface bearing authority (deactivation cancels it),
 *   - the initiating session (account, role and the exact captured credential),
 *   - the authorization generation captured before anything was awaited,
 *   - the exact mounted root instance its command acts on, so a denial can only ever withdraw the
 *     instance the operation itself started against, and
 *   - the display context it began in, so a refusal awaited across a reconciliation can tell the one
 *     withdrawal it performed itself from any later navigation and never lands in a record that
 *     replaced its own.
 *
 * The display dimension has a second half, and it is the one a *successful* completion needs. A
 * validated server success is a success whatever the OE is looking at by the time it arrives
 * (`A. authoritative business completion`). What travels with it, though — the authoritative root it
 * would install, the navigation it would perform, the drawer outcome it would write — is a *local
 * publication* (`B. local UI publication`), and that belongs to the display context the operation
 * began in. The operation records that context before its first `await`, and also records any display
 * change it publishes itself as an authorised step of its own flow, so a coordination that
 * legitimately replaces its initiating Request with the returned Session is not suppressed by its own
 * transition while a later user-initiated navigation still invalidates it.
 *
 * The mutation lock belongs to the operation that acquired it: `finish` releases it only for the
 * owner, so a stale operation can never unlock, cancel or duplicate a newer one.
 *
 * Every method here is deliberately synchronous. {@link OwnershipOperationRegistry.blockReasonForDispatch}
 * must be evaluated in the same tick as the transmission, because no check can survive an `await`
 * between the verdict and the POST.
 */

/**
 * Every user-initiated operational command shares this one serialized lifecycle. Claim and release
 * answer `204`; the Phase 2A commands answer `200` with authoritative JSON, but their dispatch,
 * ownership and staleness guarantees are identical, so they are the same kind of operation.
 */
export type OwnershipOperationKind =
  | 'claim'
  | 'release'
  | 'coordinate'
  | 'unavailable'
  | 'add-item'
  | 'remove-item'
  | 'reorder'
  | 'availability'
  | 'schedule';

/** Why an ownership command was abandoned before it could be transmitted. */
export type OwnershipBlockReason =
  /** A newer logical operation owns the workspace. */
  | 'operation-superseded'
  /** The component-local session epoch moved forward (account change, logout, unmount, loss of authority). */
  | 'session-epoch-advanced'
  /** The workspace surface (or the whole component) is no longer the live authority-bearing one. */
  | 'workspace-closed'
  /** The signed-in account or the exact captured credential is no longer current. */
  | 'session-changed'
  /** Authorization is no longer currently granted for this exact session. */
  | 'authorization-lost';

/**
 * The authority the workspace consults at dispatch time. Production passes the operations
 * capability hook; tests pass the same shape, so the gate is exercised as real runtime logic.
 */
export interface OwnershipAuthorizationGate {
  /** Monotonic authorization generation; it changes whenever authority is invalidated. */
  authorizationGeneration: () => number;
  /** Synchronous verdict for this exact session: currently evaluated as `granted`, still current. */
  isDispatchAuthorized: (session: OperationsSession) => boolean;
}

export interface OwnershipOperation {
  /** Unique identity of this logical operation, used to keep lock ownership unambiguous. */
  readonly id: number;
  readonly kind: OwnershipOperationKind;
  /** The session captured once when the logical operation began. */
  readonly session: OperationsSession;
  /** The authorization generation in force when the operation began. */
  readonly authorizationGeneration: number;
  /**
   * The exact protected root this command acts on, captured when the operation began — before
   * anything is awaited. The root identity (Request vs Session) and the mounted-instance generation
   * both come from the command itself, never from whatever happens to be on screen when its failure
   * arrives. `null` means the command's root was not the displayed root when the operation began (a
   * claim started from the list), so the operation has no mounted detail of its own.
   */
  readonly target: OwnershipOperationTarget | null;
  /**
   * The component-local session epoch in force when the operation began. It moves forward on every
   * account change, logout, unmount, workspace deactivation or loss of authority, so a continuation
   * from a previous incarnation of the workspace can never write into the current one.
   */
  readonly sessionEpoch: number;
  /** Cancels every pre-mutation read of this operation. */
  readonly controller: AbortController;
  /** True only once the POST has been handed to the transport in this same tick. */
  dispatched: boolean;
  cancelled: boolean;
  blockReason: OwnershipBlockReason | null;
  /**
   * The display context this operation produced *itself* by withdrawing its own mounted root, or
   * `null` when it never withdrew one.
   *
   * A definitive refusal has to take the refused record off the screen, so the operation that learned
   * the denial also removes the context it was started against. That is the one display change which
   * belongs to the operation, and a later verdict must be able to tell it apart from the OE navigating
   * somewhere else while the failure was being reconciled — otherwise the refusal either loses its own
   * truthful notice or is written into a context it does not describe.
   */
  withdrawnDisplayContext: number | null;
  /**
   * The display context that was in force when the operation began — captured before its first
   * `await`, so it describes the record the OE was looking at when the command was started rather than
   * whatever happens to be on screen when the answer arrives.
   */
  readonly displayContextGeneration: number;
  /**
   * A display context this operation published *itself* as an authorised step of its own flow, or
   * `null` when it has published none.
   *
   * These transitions are legitimately the operation's own — a coordination replaces its initiating
   * Request with the authoritative Session the server returned, a claim opens the root it just claimed,
   * a release drops the root it released — so the remainder of that same flow must not be suppressed by
   * them. {@link OwnershipOperationRegistry.displayContextIsOwned} honours the generation of each such
   * publication, and any later user-initiated navigation or remount moves the generation past it,
   * invalidating every further publication exactly like any other replaced display.
   */
  acceptedDisplayContext: number | null;
}

/**
 * The exact protected root instance a command acted on, frozen at the moment the operation began.
 *
 * `targetType` separates Request roots from Session roots even when their numeric ids coincide: a
 * Request #3108 and a Session #3108 are different protected roots. `mountGeneration` identifies the
 * *instance* of that root that was on screen when the operation began — a record the OE closes and
 * re-opens is a new instance, with its own reads and its own revealed contact, so an older
 * operation's denial can never be applied to it.
 */
export interface OwnershipOperationTarget {
  readonly targetType: 'REQUEST' | 'SESSION';
  readonly targetId: number;
  readonly mountGeneration: number;
}

export class OwnershipOperationRegistry {
  private counter = 0;
  private current: OwnershipOperation | null = null;
  private mounted = true;
  private workspaceActive = true;
  /**
   * Component-local session epoch. It is deliberately distinct from the global invalidation
   * generation: the workspace advances it for its own protected UI state (including a loss of
   * authority while the same account stays signed in) so any in-flight continuation can tell that its
   * captured epoch is stale and abandon its write.
   */
  private epoch = 0;
  /**
   * Monotonic generation of the *display context*: which protected root the OE is looking at, and how
   * many times that has changed.
   *
   * The mounted-root identity on the operation answers "is the exact record instance this command
   * targeted still on screen?" — but it cannot answer the second, independent question a refusal has
   * to ask once its reconciliation has been awaited: when that record is no longer mounted, was it
   * this operation that took it away (the security response to its own denial, which must still be
   * announced), or did the OE open another record — or this one again — in the meantime (which must
   * never inherit the old refusal)? The workspace advances this counter on every display change,
   * including leaving the detail for a queue, and records the generation its own withdrawal produced
   * on the operation itself.
   */
  private display = 0;

  setMounted(mounted: boolean): void {
    this.mounted = mounted;
  }

  setWorkspaceActive(active: boolean): void {
    this.workspaceActive = active;
  }

  /** Advances the component-local session epoch and returns the new value. */
  advanceSessionEpoch(): number {
    this.epoch += 1;
    return this.epoch;
  }

  /** The current component-local session epoch. */
  get sessionEpoch(): number {
    return this.epoch;
  }

  /** Moves the display context forward: a different root is on screen, or the detail was left. */
  advanceDisplayContext(): number {
    this.display += 1;
    return this.display;
  }

  /** The display context that is currently in force. */
  get displayContextGeneration(): number {
    return this.display;
  }

  /**
   * Records that this operation itself published the display change now in force — it moved the
   * workspace onto the authoritative root the server returned, onto the root it just claimed, or away
   * from the root it just released.
   *
   * Only the live operation may record it, and it is re-recorded on each of its own publications, so a
   * stale continuation can never claim a later navigation as its own.
   */
  markAcceptedDisplayContext(operation: OwnershipOperation): boolean {
    if (this.current !== operation) return false;
    operation.acceptedDisplayContext = this.display;
    return true;
  }

  /**
   * True only while the display change in force is the context this operation began in, or one it
   * published itself as an authorised step of its flow.
   *
   * Deliberately independent of the live-operation check, and therefore safe to evaluate after
   * `finish` has released the mutation lock: the question here is about the *display*, not about
   * ownership of the lock, and a caller reporting a confirmed command's outcome has to ask it in the
   * same tick as its own write.
   */
  displayContextIsOwned(operation: OwnershipOperation): boolean {
    return operation.displayContextGeneration === this.display
      || operation.acceptedDisplayContext === this.display;
  }

  /**
   * Records that this operation withdrew its own mounted root, at the display context that withdrawal
   * produced. Only the live operation may record it, and only once, so a stale continuation can never
   * claim a later navigation as its own withdrawal.
   */
  markWithdrewOwnDisplayContext(operation: OwnershipOperation): boolean {
    if (this.current !== operation) return false;
    if (operation.withdrawnDisplayContext !== null) return false;
    operation.withdrawnDisplayContext = this.display;
    return true;
  }

  /**
   * True while the display change in force is still the withdrawal this operation performed itself:
   * its record is gone because the operation took it away, and the OE has not navigated since. This is
   * the only way a refusal can still describe the context on screen once its own withdrawal has
   * unmounted the record it names.
   */
  withdrewOwnDisplayContext(operation: OwnershipOperation): boolean {
    return operation.withdrawnDisplayContext !== null
      && operation.withdrawnDisplayContext === this.display;
  }

  get activeOperation(): OwnershipOperation | null {
    return this.current;
  }

  /** True while an ownership command owns the workspace's single mutation lock. */
  isLocked(): boolean {
    return this.current !== null;
  }

  /**
   * Starts the one live ownership operation. Any older operation is cancelled first, so a lock can
   * never be held by two operations at once.
   *
   * `target` is the immutable root identity the command acts on: the mounted instance it was started
   * against, or `null` when its root was not the displayed one (a claim started from the list).
   */
  begin(
    kind: OwnershipOperationKind,
    session: OperationsSession,
    authorizationGeneration: number,
    target: OwnershipOperationTarget | null = null
  ): OwnershipOperation {
    this.cancelActive('operation-superseded');
    const operation: OwnershipOperation = {
      id: (this.counter += 1),
      kind,
      session,
      authorizationGeneration,
      target,
      sessionEpoch: this.epoch,
      controller: new AbortController(),
      dispatched: false,
      cancelled: false,
      blockReason: null,
      withdrawnDisplayContext: null,
      displayContextGeneration: this.display,
      acceptedDisplayContext: null
    };
    this.current = operation;
    return operation;
  }

  /** True while this exact operation is still the live one and has not been cancelled. */
  isCurrent(operation: OwnershipOperation): boolean {
    return !operation.cancelled && this.current === operation;
  }

  /**
   * The synchronous dispatch gate. Returns `null` only when every invariant still holds in this
   * same tick: the operation is current, the component is mounted, the workspace surface is active,
   * the initiating account and its exact credential are current, and authorization is currently
   * granted under the generation the operation captured.
   */
  blockReasonForDispatch(
    operation: OwnershipOperation,
    authorization: OwnershipAuthorizationGate
  ): OwnershipBlockReason | null {
    if (!this.isCurrent(operation)) return operation.blockReason ?? 'operation-superseded';
    if (operation.sessionEpoch !== this.epoch) return 'session-epoch-advanced';
    if (!this.mounted) return 'workspace-closed';
    if (!this.workspaceActive) return 'workspace-closed';
    if (!isOperationsSessionActive(operation.session)) return 'session-changed';
    if (authorization.authorizationGeneration() !== operation.authorizationGeneration) return 'authorization-lost';
    if (!authorization.isDispatchAuthorized(operation.session)) return 'authorization-lost';
    return null;
  }

  /**
   * The synchronous commit guard for every operation-originated UI write (not only the POST). It is
   * exactly the dispatch gate: an operation may touch the workspace only while it still owns the
   * registry, the component is mounted, the surface is active, its captured epoch and session are
   * current, and authorization is still granted under the generation it captured. Evaluated in the
   * same tick as the write, with no `await` in between.
   */
  canCommit(operation: OwnershipOperation, authorization: OwnershipAuthorizationGate): boolean {
    return this.blockReasonForDispatch(operation, authorization) === null;
  }

  /**
   * Records that the command was transmitted. Returns false when this operation already dispatched
   * one, which keeps a single live operation from ever issuing a duplicate command.
   */
  markDispatched(operation: OwnershipOperation): boolean {
    if (operation.dispatched) return false;
    operation.dispatched = true;
    return true;
  }

  /**
   * Invalidates the live operation: it is marked cancelled (so no further step of its flow may
   * commit) and its pending pre-mutation reads are aborted. The lock is released immediately, since
   * an invalidated operation can never continue.
   */
  cancelActive(reason: OwnershipBlockReason): boolean {
    const operation = this.current;
    if (!operation) return false;
    operation.cancelled = true;
    operation.blockReason = reason;
    operation.controller.abort();
    this.current = null;
    return true;
  }

  /**
   * Releases the lock. Only the operation that owns it may do so, which is what stops an abandoned
   * older operation's `finally` block from clearing a newer operation's lock or busy state.
   */
  finish(operation: OwnershipOperation): boolean {
    if (this.current !== operation) return false;
    this.current = null;
    return true;
  }
}

/** True when the given session is still the exact signed-in credential of the captured operation. */
export const operationSessionIsCurrent = (operation: OwnershipOperation): boolean =>
  !operation.cancelled && isOperationsSessionActive(operation.session);

/** True when the operation carries the same exact credential as the given session. */
export const operationMatchesSession = (
  operation: OwnershipOperation,
  session: OperationsSession
): boolean => sameOperationsCredential(operation.session, session);

/**
 * True only while `mounted` is the exact protected root instance the operation was started against:
 * the same Request/Session root *and* the same mount generation.
 *
 * Checking the root identity alone would let a record that was closed and re-opened — a new instance,
 * with its own reads and its own revealed contact — be cleared by an operation that started against
 * the previous one; checking only what is on screen would let any operation's denial clear whatever
 * record the OE happens to be viewing when the failure arrives. Both mistakes are the same defect
 * seen from two sides, and this comparison is the one place that decides it.
 */
export const operationTargetIsMounted = (
  operation: OwnershipOperation,
  mounted: OwnershipOperationTarget | null
): boolean =>
  operation.target !== null
  && mounted !== null
  && operation.target.targetType === mounted.targetType
  && operation.target.targetId === mounted.targetId
  && operation.target.mountGeneration === mounted.mountGeneration;
