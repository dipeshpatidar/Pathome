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
 *   - the authorization generation captured before anything was awaited.
 *
 * The mutation lock belongs to the operation that acquired it: `finish` releases it only for the
 * owner, so a stale operation can never unlock, cancel or duplicate a newer one.
 *
 * Every method here is deliberately synchronous. {@link OwnershipOperationRegistry.blockReasonForDispatch}
 * must be evaluated in the same tick as the transmission, because no check can survive an `await`
 * between the verdict and the POST.
 */

export type OwnershipOperationKind = 'claim' | 'release';

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
   */
  begin(
    kind: OwnershipOperationKind,
    session: OperationsSession,
    authorizationGeneration: number
  ): OwnershipOperation {
    this.cancelActive('operation-superseded');
    const operation: OwnershipOperation = {
      id: (this.counter += 1),
      kind,
      session,
      authorizationGeneration,
      sessionEpoch: this.epoch,
      controller: new AbortController(),
      dispatched: false,
      cancelled: false,
      blockReason: null
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
