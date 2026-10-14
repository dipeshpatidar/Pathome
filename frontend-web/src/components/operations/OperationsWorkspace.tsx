import React, { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { ArrowRight, CircleAlert, Flag, Home, Inbox, ListChecks, Lock, MapPin, RefreshCw } from 'lucide-react';
import { ApiRequestError } from '../../services/apiError';
import {
  OPERATIONS_WORK_PAGE_SIZE,
  operationsWorkspaceService,
  type OperationsRequestContext
} from '../../services/operationsWorkspaceService';
import {
  captureOperationsSession,
  isOperationsSessionActive,
  isOperationsSessionChangedError,
  readOperationsSession,
  subscribeOperationsSession,
  type OperationsSession
} from '../../utils/operationsSession';
import {
  OwnershipOperationRegistry,
  type OwnershipBlockReason,
  type OwnershipOperation
} from '../../utils/operationsOwnershipRegistry';
import type { OperationsAuthority } from '../../hooks/useOperationsCapability';
import { useNotification } from '../../context/NotificationContext';
import {
  claimCommandFor,
  formatOperationsDateTime,
  linkedRequestsLabel,
  operationsCapabilityScopes,
  operationsDetailFailureState,
  operationsLoadFailureState,
  operationsRecordTitle,
  ownershipFailureKind,
  ownershipOutcomeCopy,
  ownershipRefreshFailureNotice,
  releaseCommandForRequest,
  releaseCommandForSession,
  type ClaimableWorkItem,
  type OperationsLoadFailure,
  type OperationsMyWorkItem,
  type OperationsTargetType,
  type OwnershipOutcomeInput,
  type OwnershipReconciliation,
  type OperationsWorkPage,
  type StaffCapabilities
} from '../../utils/operationsWorkspacePresentation';
import {
  focusWorkspaceTitle,
  PageHeader,
  Pagination,
  ScopeLine,
  SkeletonRows,
  StateMessage,
  StatusBadge,
  TenantConfirmationLabel,
  TypeTag,
  WorkspaceNotice
} from './OperationsWorkspacePrimitives';
import { ClaimableList, OwnedList, workItemKey } from './OperationsWorkLists';
import {
  OperationsWorkDetail,
  type OperationsDetailSnapshot,
  type OperationsDetailState
} from './OperationsWorkDetail';
import './operationsWorkspace.css';

type QueueKey = 'attention' | 'my-work' | 'claimable';
type QueueView = 'home' | QueueKey;
type QueueStatus = 'loading' | 'ready' | OperationsLoadFailure;

interface PageState<T> {
  status: QueueStatus;
  items: T[];
  totalCount: number;
  page: number;
  totalPages: number;
}

const EMPTY_ITEMS: never[] = [];

const initialPageState = <T,>(): PageState<T> => ({
  status: 'loading',
  items: EMPTY_ITEMS,
  totalCount: 0,
  page: 0,
  totalPages: 0
});

interface DetailTarget {
  targetType: OperationsTargetType;
  targetId: number;
  source: 'owned' | 'claimable';
}

const QUEUE_COPY: Record<QueueKey, { title: string; description: string; emptyTitle: string; emptyBody: string }> = {
  attention: {
    title: 'Needs attention',
    description: 'Owned work the server has flagged for your next step.',
    emptyTitle: 'Nothing needs your attention',
    emptyBody: 'When owned work needs a next step, the server lists it here.'
  },
  'my-work': {
    title: 'My work',
    description: 'Request and session roots you own in your teams. Totals and pages come from the server.',
    emptyTitle: 'You don’t own any work yet',
    emptyBody: 'Work you claim appears here. Browse claimable work in your teams to get started.'
  },
  claimable: {
    title: 'Claimable work',
    description: 'Unclaimed standalone requests and sessions in your teams.',
    emptyTitle: 'No claimable work right now',
    emptyBody: 'There are no unclaimed requests or sessions in your teams at the moment.'
  }
};

const ATTENTION_LIMIT = 5;
const CLAIM_LIMIT = 4;

const queueFailureStatus = (cause: unknown): QueueStatus =>
  operationsLoadFailureState(cause instanceof ApiRequestError ? cause.status : undefined);

/**
 * The real outcome of one queue read, or of a whole reconciliation.
 *
 *  - `loaded`  — the authoritative read actually succeeded and was accepted under the initiating
 *                session and component epoch.
 *  - `failed`  — the required read did not succeed (a real transport/HTTP failure).
 *  - `skipped` — the read was not attempted, or its result was invalidated (superseded, aborted,
 *                unmounted, or a stale session/epoch), so it says nothing about the server state.
 *
 * A reconciliation is complete only when every required read is `loaded`; a partial failure is never
 * reported as complete.
 */
type QueueLoadOutcome = 'loaded' | 'failed' | 'skipped';

interface QueuePageLoad<T> {
  key: QueueKey;
  page: number;
  /** The session captured when this logical load began. Every request re-uses it. */
  session: OperationsSession;
  /** The component-local session epoch captured when this logical load began. */
  epoch: number;
  fetch: (page: number, context: OperationsRequestContext) => Promise<OperationsWorkPage<T>>;
  apply: (update: (previous: PageState<T>) => PageState<T>) => void;
  controllers: Record<QueueKey, AbortController | null>;
  isMounted: () => boolean;
  /** True while the component-local session epoch is still the captured one. */
  isEpochCurrent: (epoch: number) => boolean;
  attempt?: number;
}

/**
 * One queue page load. The previous in-flight request for the same queue is aborted, and a result is
 * only committed while the session that issued it is still the signed-in one, so an obsolete or
 * previous-account response can never populate the workspace. It reports what actually happened, so a
 * caller can tell a real read (`loaded`) from a real failure (`failed`) and from work that was never
 * committed (`skipped`) instead of assuming a refresh succeeded because it was permitted.
 */
async function loadQueuePage<T>(load: QueuePageLoad<T>): Promise<QueueLoadOutcome> {
  const { key, page, session, epoch, fetch: fetchPage, apply, controllers, isMounted, isEpochCurrent, attempt = 0 } = load;
  controllers[key]?.abort();
  const controller = new AbortController();
  controllers[key] = controller;
  if (!isOperationsSessionActive(session) || !isEpochCurrent(epoch)) return 'skipped';
  apply((previous) => ({ ...previous, status: 'loading' }));
  try {
    const result = await fetchPage(page, { signal: controller.signal, session });
    if (controller.signal.aborted || !isMounted()) return 'skipped';
    if (!isEpochCurrent(epoch)) return 'skipped';
    if (!isOperationsSessionActive(session)) return 'skipped';
    const settledPage = result.totalPages === 0 ? 0 : Math.min(result.page, result.totalPages - 1);
    if (settledPage !== result.page && attempt < 2) {
      // The recursive correction returns its own final authoritative result.
      return loadQueuePage({ ...load, page: settledPage, attempt: attempt + 1 });
    }
    apply(() => ({
      status: 'ready',
      items: result.items,
      totalCount: result.totalCount,
      page: result.page,
      totalPages: result.totalPages
    }));
    return 'loaded';
  } catch (cause) {
    if (controller.signal.aborted || !isMounted()) return 'skipped';
    if (!isEpochCurrent(epoch)) return 'skipped';
    if (!isOperationsSessionActive(session)) return 'skipped';
    if (isOperationsSessionChangedError(cause)) return 'skipped';
    apply((previous) => ({ ...previous, status: queueFailureStatus(cause) }));
    return 'failed';
  }
}

interface NoticeState {
  id: number;
  text: string;
  /** Identity the notice belongs to, so another session can never inherit it. */
  sessionIdentity: string | null;
}

interface Props {
  authority: OperationsAuthority;
}

export const OperationsWorkspace: React.FC<Props> = ({ authority }) => {
  const { notifySuccess } = useNotification();
  const capabilities: StaffCapabilities | null = authority.capabilities;
  /**
   * The read-admission boundary. Granted authority is not enough on its own: the workspace may only
   * mount protected content and start protected reads once a verification has actually succeeded for
   * the entry that is on screen *and* the exact signed-in credential. `authority.entryAdmitted` is
   * evaluated during render, so the first render after a surface transition already withholds
   * everything - no effect has to run, and no protected request can be issued, in the meantime.
   */
  const accessGranted = authority.state === 'granted' && authority.entryAdmitted;
  const authorityRef = useRef(authority);
  authorityRef.current = authority;

  const [view, setView] = useState<QueueView>('home');
  const [attention, setAttention] = useState<PageState<OperationsMyWorkItem>>(
    initialPageState<OperationsMyWorkItem>
  );
  const [myWork, setMyWork] = useState<PageState<OperationsMyWorkItem>>(
    initialPageState<OperationsMyWorkItem>
  );
  const [claimable, setClaimable] = useState<PageState<ClaimableWorkItem>>(
    initialPageState<ClaimableWorkItem>
  );
  const [detailTarget, setDetailTarget] = useState<DetailTarget | null>(null);
  const [detail, setDetail] = useState<OperationsDetailState>({ status: 'loading' });
  const [busyAction, setBusyAction] = useState<'claim' | 'release' | null>(null);
  const [busyClaimKey, setBusyClaimKey] = useState<string | null>(null);
  const [notice, setNotice] = useState<NoticeState | null>(null);

  const mountedRef = useRef(true);
  const queueControllers = useRef<Record<QueueKey, AbortController | null>>({
    attention: null,
    'my-work': null,
    claimable: null
  });
  const detailController = useRef<AbortController | null>(null);
  /**
   * The single live ownership operation and the lock it owns. Nothing about a claim or a release is
   * tracked outside it, so a stale operation can never clear a newer one's lock or busy state.
   */
  const ownershipRef = useRef<OwnershipOperationRegistry | null>(null);
  if (ownershipRef.current === null) ownershipRef.current = new OwnershipOperationRegistry();
  const ownership = ownershipRef.current;
  const returnToRow = useRef<{ view: QueueView; rowKey: string } | null>(null);
  const savedScroll = useRef<Record<string, number>>({});
  const noticeCounter = useRef(0);
  const noticeRef = useRef<HTMLDivElement | null>(null);

  /**
   * Drops every piece of protected operational state and cancels pending work. Used whenever
   * authority is lost or the signed-in account changes, so no protected detail can survive it and no
   * pending ownership command can still be transmitted.
   *
   * It first advances the component-local session epoch: any continuation still in flight (a queue
   * read, a detail read, an ownership finalizer) can no longer write to the workspace, even before its
   * own cancellation has been observed. `neutralView` is set only for a real account change/logout:
   * that resets the workspace to its neutral entry view, while a same-account loss of authority keeps
   * the current navigation, which is re-authorized and refetched on recovery.
   *
   * Invalidation cleanup is deliberately unconditional: it may never be blocked by a stale operation
   * guard, so genuine logout/account-switch/authority-withdrawal clearing always runs.
   */
  const clearProtectedState = useCallback((
    reason: OwnershipBlockReason = 'session-changed',
    options: { neutralView?: boolean } = {}
  ) => {
    ownership.advanceSessionEpoch();
    Object.values(queueControllers.current).forEach((controller) => controller?.abort());
    detailController.current?.abort();
    // Invalidate the live ownership operation: it is cancelled, its pre-mutation reads are aborted
    // and its lock is released so the next valid operation can start cleanly.
    ownership.cancelActive(reason);
    returnToRow.current = null;
    savedScroll.current = {};
    setAttention(initialPageState<OperationsMyWorkItem>());
    setMyWork(initialPageState<OperationsMyWorkItem>());
    setClaimable(initialPageState<ClaimableWorkItem>());
    setDetailTarget(null);
    setDetail({ status: 'loading' });
    setBusyAction(null);
    setBusyClaimKey(null);
    setNotice(null);
    if (options.neutralView === true) {
      setView('home');
      if (typeof window !== 'undefined') window.scrollTo({ top: 0 });
    }
  }, [ownership]);

  // React layout cleanup closes the commit window synchronously as the workspace is removed: the
  // component-local epoch advances and the operation is invalidated before any pending continuation
  // can observe the unmount, so nothing writes into a tree that is being torn down.
  useLayoutEffect(() => {
    mountedRef.current = true;
    ownership.setMounted(true);
    return () => {
      ownership.advanceSessionEpoch();
      mountedRef.current = false;
      // Unmounting the workspace abandons every pending ownership operation before its command can
      // be transmitted, and aborts the reads that were still in flight.
      ownership.setMounted(false);
      ownership.cancelActive('workspace-closed');
      Object.values(queueControllers.current).forEach((controller) => controller?.abort());
      detailController.current?.abort();
    };
  }, [ownership]);

  // Logout, account switch or cross-tab session replacement cancels pending work immediately and
  // returns the workspace to its neutral entry view for whichever identity comes next.
  useEffect(
    () => subscribeOperationsSession(() => clearProtectedState('session-changed', { neutralView: true })),
    [clearProtectedState]
  );

  // Loss of authority hides protected data, deactivates the workspace surface and blocks mutations.
  useEffect(() => {
    ownership.setWorkspaceActive(accessGranted);
    if (!accessGranted) clearProtectedState('authorization-lost');
  }, [accessGranted, clearProtectedState, ownership]);

  const isMounted = useCallback(() => mountedRef.current, []);
  const isEpochCurrent = useCallback((epoch: number) => ownership.sessionEpoch === epoch, [ownership]);

  const loadAttention = useCallback(
    (page: number, session?: OperationsSession, epoch?: number): Promise<QueueLoadOutcome> => {
      const captured = session ?? captureOperationsSession();
      if (!captured) return Promise.resolve<QueueLoadOutcome>('skipped');
      return loadQueuePage<OperationsMyWorkItem>({
        key: 'attention',
        page,
        session: captured,
        epoch: epoch ?? ownership.sessionEpoch,
        fetch: (nextPage, context) => operationsWorkspaceService.myWork(nextPage, true, context),
        apply: setAttention,
        controllers: queueControllers.current,
        isMounted,
        isEpochCurrent
      });
    },
    [isEpochCurrent, isMounted, ownership]
  );

  const loadMyWork = useCallback(
    (page: number, session?: OperationsSession, epoch?: number): Promise<QueueLoadOutcome> => {
      const captured = session ?? captureOperationsSession();
      if (!captured) return Promise.resolve<QueueLoadOutcome>('skipped');
      return loadQueuePage<OperationsMyWorkItem>({
        key: 'my-work',
        page,
        session: captured,
        epoch: epoch ?? ownership.sessionEpoch,
        fetch: (nextPage, context) => operationsWorkspaceService.myWork(nextPage, false, context),
        apply: setMyWork,
        controllers: queueControllers.current,
        isMounted,
        isEpochCurrent
      });
    },
    [isEpochCurrent, isMounted, ownership]
  );

  const loadClaimable = useCallback(
    (page: number, session?: OperationsSession, epoch?: number): Promise<QueueLoadOutcome> => {
      const captured = session ?? captureOperationsSession();
      if (!captured) return Promise.resolve<QueueLoadOutcome>('skipped');
      return loadQueuePage<ClaimableWorkItem>({
        key: 'claimable',
        page,
        session: captured,
        epoch: epoch ?? ownership.sessionEpoch,
        fetch: (nextPage, context) => operationsWorkspaceService.claimableWork(nextPage, context),
        apply: setClaimable,
        controllers: queueControllers.current,
        isMounted,
        isEpochCurrent
      });
    },
    [isEpochCurrent, isMounted, ownership]
  );

  /**
   * Reloads every authoritative queue under one captured session and reports the real aggregate
   * outcome. It is complete (`loaded`) only when every required read actually succeeded; a partial
   * failure returns `failed`, and an invalidation before anything could be committed returns
   * `skipped`. Callers must never treat "a refresh was permitted" as "a refresh succeeded".
   */
  const refreshAll = useCallback(async (session?: OperationsSession, epoch?: number): Promise<QueueLoadOutcome> => {
    const captured = session ?? captureOperationsSession();
    if (!captured) return 'skipped';
    const capturedEpoch = epoch ?? ownership.sessionEpoch;
    const results = await Promise.all([
      loadAttention(0, captured, capturedEpoch),
      loadMyWork(0, captured, capturedEpoch),
      loadClaimable(0, captured, capturedEpoch)
    ]);
    if (results.every((result) => result === 'loaded')) return 'loaded';
    if (results.some((result) => result === 'failed')) return 'failed';
    return 'skipped';
  }, [loadAttention, loadMyWork, loadClaimable, ownership]);

  // Authority restoration re-authorizes the reads; an old snapshot is never simply redisplayed.
  useEffect(() => {
    if (!accessGranted) return;
    void Promise.all([loadAttention(0), loadMyWork(0), loadClaimable(0)]);
  }, [accessGranted, loadAttention, loadMyWork, loadClaimable]);

  // A queue-wide 403 is resolved by asking the authoritative capability endpoint, never inferred.
  const queuesDenied = [attention.status, myWork.status, claimable.status].includes('denied');
  useEffect(() => {
    if (!accessGranted || !queuesDenied) return;
    authorityRef.current.revalidate();
  }, [accessGranted, queuesDenied]);

  // Returning from a detail restores the scroll position and refocuses the row that was opened.
  useEffect(() => {
    if (detailTarget !== null) return;
    const target = returnToRow.current;
    if (!target) return;
    returnToRow.current = null;
    const top = savedScroll.current[target.view] ?? 0;
    // The callback is delayed by a frame, so it re-checks the component-local epoch: a scroll/focus
    // from a previous account must never run against the new account's rows.
    const epoch = ownership.sessionEpoch;
    window.requestAnimationFrame(() => {
      if (epoch !== ownership.sessionEpoch) return;
      window.scrollTo({ top });
      document.querySelector<HTMLElement>(`[data-row-key="${target.rowKey}"]`)?.focus({ preventScroll: true });
    });
  }, [detailTarget, ownership]);

  // A fresh rejection or conflict is announced, scrolled into view and focused.
  useEffect(() => {
    if (!notice) return;
    const element = noticeRef.current;
    if (!element) return;
    element.focus({ preventScroll: true });
    element.scrollIntoView({ block: 'nearest', behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' });
  }, [notice]);

  /**
   * Records a workspace notice. When the caller passes the operation's captured session, the notice is
   * labelled with the identity that actually started the operation, so an old outcome can never be
   * shown under another account even if the label is read one tick later.
   */
  const showNotice = useCallback((text: string, session?: OperationsSession) => {
    noticeCounter.current += 1;
    const identity = (session ?? readOperationsSession())?.identity ?? null;
    setNotice({ id: noticeCounter.current, text, sessionIdentity: identity });
  }, []);

  /**
   * Opens a record detail. Both an operation-triggered open (which passes the initiating
   * `operation`) and an ordinary user-triggered open validate their context *before* any protected
   * write — selected target, loading state, controller, scroll bookkeeping, focus and notices — and
   * again after every awaited read, checking that this call still owns the detail controller. That is
   * what stops Account A's delayed continuation from aborting or replacing Account B's detail request.
   */
  const openDetail = useCallback(async (
    target: DetailTarget,
    session?: OperationsSession,
    operation?: OwnershipOperation
  ) => {
    const captured = session ?? captureOperationsSession();
    if (!captured) return;
    const epoch = ownership.sessionEpoch;
    const contextHolds = () => (operation
      ? ownership.canCommit(operation, authorityRef.current)
      : isOperationsSessionActive(captured) && ownership.sessionEpoch === epoch);
    if (!contextHolds()) return;
    savedScroll.current[view] = window.scrollY;
    returnToRow.current = { view, rowKey: workItemKey(target) };
    detailController.current?.abort();
    const controller = new AbortController();
    detailController.current = controller;
    setNotice(null);
    setDetailTarget(target);
    setDetail({ status: 'loading' });
    focusWorkspaceTitle();
    try {
      let snapshot: OperationsDetailSnapshot;
      const context: OperationsRequestContext = { signal: controller.signal, session: captured };
      if (target.source === 'claimable') {
        const item = await operationsWorkspaceService.claimableItem(target.targetType, target.targetId, context);
        snapshot = { kind: 'claimable', claimable: item };
      } else if (target.targetType === 'REQUEST') {
        const request = await operationsWorkspaceService.requestDetail(target.targetId, context);
        snapshot = { kind: 'request', request };
      } else {
        const detail = await operationsWorkspaceService.sessionDetail(target.targetId, context);
        snapshot = { kind: 'session', session: detail };
      }
      if (detailController.current !== controller) return;
      if (controller.signal.aborted || !mountedRef.current || !contextHolds()) return;
      setDetail({ status: 'ready', snapshot });
    } catch (cause) {
      if (detailController.current !== controller) return;
      if (controller.signal.aborted || !mountedRef.current || !contextHolds()) return;
      if (isOperationsSessionChangedError(cause)) return;
      // A resource-specific 403/404 clears that resource only; it is never global revocation.
      setDetail({ status: operationsDetailFailureState(cause instanceof ApiRequestError ? cause.status : undefined) });
    }
  }, [view, ownership]);

  const closeDetail = useCallback(() => {
    detailController.current?.abort();
    setDetailTarget(null);
  }, []);

  /**
   * Reports what actually happened to one failed ownership command and, when it is safe, reconciles
   * the authoritative queues first so the message can state the real outcome.
   *
   * Three outcomes are kept apart, because they carry different truths:
   *  - nothing was transmitted → this action provably changed no ownership;
   *  - the backend refused it (HTTP 4xx) → report the real rejection and the recovery;
   *  - it was transmitted and the outcome is unconfirmed → say so, never resend it, and only reload
   *    the authoritative state while the initiating session and authorization are still valid.
   *
   * The copy is told the *real* reconciliation result (`loaded`/`failed`/`skipped`/`not-attempted`),
   * never merely whether a refresh was permitted, so a failed or skipped read is never described as a
   * completed reload. If the operation is invalidated while it reconciles, its notice is suppressed
   * entirely so it can never appear under another account.
   */
  const reportOwnershipFailure = useCallback(async (
    cause: unknown,
    operation: OwnershipOperation,
    targetType: OperationsTargetType,
    targetId: number,
    options: { blockedByAuthorization?: boolean; dropDetailWhenRejected?: boolean } = {}
  ): Promise<void> => {
    const name = operationsRecordTitle(targetType, targetId);
    const apiError = cause instanceof ApiRequestError ? cause : null;
    const input: OwnershipOutcomeInput = {
      dispatched: operation.dispatched,
      status: apiError?.status ?? null,
      message: apiError?.message ?? null,
      blockedByAuthorization: options.blockedByAuthorization === true
    };
    const kind = ownershipFailureKind(input);
    // Reconciliation is a protected read: it only happens while this exact operation may still commit
    // (current session, current epoch, authorization still granted). Its result is the real outcome,
    // not a permission to attempt it.
    let reconciliation: OwnershipReconciliation = 'not-attempted';
    if (kind !== 'pre-dispatch' && ownership.canCommit(operation, authorityRef.current)) {
      reconciliation = await refreshAll(operation.session);
    }
    // The operation may have been invalidated while it reconciled: suppress its notice and every other
    // protected write so nothing lands under a newer account or a newer operation.
    if (!ownership.canCommit(operation, authorityRef.current)) return;

    const copy = ownershipOutcomeCopy(name, { ...input, reconciliation });
    showNotice(copy.message, operation.session);

    if (kind === 'rejected') {
      const status = input.status ?? 0;
      if (options.dropDetailWhenRejected === true && (status === 403 || status === 404 || status === 409)) {
        // Ownership may already be gone on the server: stop showing protected detail.
        detailController.current?.abort();
        setDetailTarget(null);
      }
    }
  }, [refreshAll, showNotice]);

  const claimRoot = useCallback(async (item: ClaimableWorkItem) => {
    // One live ownership operation at a time: a repeated tap while one is running is ignored.
    if (ownership.isLocked()) return;
    const session = captureOperationsSession();
    if (!session) return;
    // The operation is bound to this component, this workspace surface, this exact session and the
    // authorization generation in force now — before anything is awaited.
    const operation = ownership.begin('claim', session, authorityRef.current.authorizationGeneration());
    setBusyClaimKey(workItemKey(item));
    setBusyAction('claim');
    setNotice(null);
    try {
      // Authority for this exact session is re-verified before anything is read or dispatched.
      if (!await authorityRef.current.verifySession(session, { signal: operation.controller.signal })) return;
      // Every await is followed by the full synchronous commit guard before the flow may continue.
      if (!ownership.canCommit(operation, authorityRef.current)) return;
      // A claim always acts on a freshly verified root, never on a stale list version.
      const fresh = await operationsWorkspaceService.claimableItem(item.targetType, item.targetId, {
        session,
        signal: operation.controller.signal
      });
      if (!ownership.canCommit(operation, authorityRef.current)) return;
      const command = claimCommandFor(fresh);
      if (!command) {
        // No version means no POST is possible; reconcile first so the message can be truthful about
        // whether the authoritative queues were really reloaded.
        const reconciliation = await refreshAll(session);
        if (!ownership.canCommit(operation, authorityRef.current)) return;
        const missingName = operationsRecordTitle(fresh.targetType, fresh.targetId);
        showNotice(
          reconciliation === 'loaded'
            ? `${missingName} did not return the version needed to claim it. The queues were refreshed from the server.`
            : `${missingName} did not return the version needed to claim it, and the work lists could not be refreshed just now — refresh them before deciding again.`,
          session
        );
        return;
      }
      // Final synchronous gate: no `await` may sit between it and the transmission below.
      const blocked = ownership.blockReasonForDispatch(operation, authorityRef.current);
      if (blocked !== null || operation.dispatched) {
        // The guard failed, so nothing may leave the client AND nothing may be written to the UI: the
        // owning identity for this workspace is no longer this operation's, so a notice here would
        // either be invisible or would land in another account's workspace.
        return;
      }
      const onDispatch = () => { ownership.markDispatched(operation); };
      if (command.targetType === 'REQUEST') {
        await operationsWorkspaceService.claimRequest(fresh.targetId, command.expectedRequestVersion, {
          session,
          signal: operation.controller.signal,
          onDispatch
        });
      } else {
        await operationsWorkspaceService.claimSession(
          fresh.targetId,
          command.expectedSessionVersion,
          command.expectedRequestVersions,
          { session, signal: operation.controller.signal, onDispatch }
        );
      }
      // The command was transmitted under this session: never announce it to a different one.
      if (!ownership.canCommit(operation, authorityRef.current)) return;
      const title = operationsRecordTitle(fresh.targetType, fresh.targetId);
      // A confirmed 204 is reported as a success; a failure of the follow-up refresh is reported
      // separately, never merged into the command result and never claimed as a current list.
      notifySuccess(`${title} claimed`, 'It now appears in My work.', undefined, 'VISIT_SESSION');
      setView('my-work');
      const reconciliation = await refreshAll(session);
      if (!ownership.canCommit(operation, authorityRef.current)) return;
      await openDetail({ targetType: fresh.targetType, targetId: fresh.targetId, source: 'owned' }, session, operation);
      // `openDetail` awaits its own detail read, so the account, the authorization generation or the
      // component-local epoch can all move while it is in flight — even though the open itself declined
      // to apply its snapshot. Re-validate the same operation/session before the only write left in this
      // continuation, so a stale claim can never write a notice (or any other UI state) into a newer
      // account's workspace. The guard, not a render-time identity filter, is what prevents the write.
      if (!ownership.canCommit(operation, authorityRef.current)) return;
      if (reconciliation !== 'loaded') showNotice(ownershipRefreshFailureNotice(title), session);
    } catch (cause) {
      if (isOperationsSessionChangedError(cause)) return;
      if (!ownership.canCommit(operation, authorityRef.current)) return;
      await reportOwnershipFailure(cause, operation, item.targetType, item.targetId);
    } finally {
      // Only the operation that owns the mutation lock may clear it, so a late older operation can
      // never release a newer one's lock or re-enable a duplicate submission.
      if (ownership.finish(operation)) {
        setBusyClaimKey(null);
        setBusyAction(null);
      }
    }
  }, [notifySuccess, openDetail, ownership, refreshAll, reportOwnershipFailure, showNotice]);

  const releaseRoot = useCallback(async () => {
    if (ownership.isLocked() || detail.status !== 'ready') return;
    const snapshot = detail.snapshot;
    const target = detailTarget;
    if (!target) return;
    const session = captureOperationsSession();
    if (!session) return;
    const operation = ownership.begin('release', session, authorityRef.current.authorizationGeneration());
    setBusyAction('release');
    setNotice(null);
    try {
      // Authority for this exact session is re-verified before the command is built or sent.
      if (!await authorityRef.current.verifySession(session, { signal: operation.controller.signal })) return;
      if (!ownership.canCommit(operation, authorityRef.current)) return;
      const onDispatch = () => { ownership.markDispatched(operation); };
      let transmit: () => Promise<number>;
      if (snapshot.kind === 'request' && snapshot.request) {
        const command = releaseCommandForRequest(snapshot.request);
        if (!command) {
          // No version means no POST is possible; reconcile first so the message is truthful.
          const reconciliation = await refreshAll(session);
          if (!ownership.canCommit(operation, authorityRef.current)) return;
          showNotice(
            reconciliation === 'loaded'
              ? 'This request did not return the version needed to release it. The queues were refreshed from the server.'
              : 'This request did not return the version needed to release it, and the work lists could not be refreshed just now — refresh them before deciding again.',
            session
          );
          return;
        }
        transmit = () => operationsWorkspaceService.releaseRequest(
          snapshot.request!.requestId,
          command.expectedRequestVersion,
          { session, signal: operation.controller.signal, onDispatch }
        );
      } else if (snapshot.kind === 'session' && snapshot.session) {
        const command = releaseCommandForSession(snapshot.session);
        if (!command) {
          const reconciliation = await refreshAll(session);
          if (!ownership.canCommit(operation, authorityRef.current)) return;
          showNotice(
            reconciliation === 'loaded'
              ? 'This session did not return the version needed to release it. The queues were refreshed from the server.'
              : 'This session did not return the version needed to release it, and the work lists could not be refreshed just now — refresh them before deciding again.',
            session
          );
          return;
        }
        transmit = () => operationsWorkspaceService.releaseSession(
          snapshot.session!.sessionId,
          command.expectedSessionVersion,
          command.expectedRequestVersions,
          { session, signal: operation.controller.signal, onDispatch }
        );
      } else {
        return;
      }
      // Final synchronous gate: no `await` may sit between it and the transmission below.
      const blocked = ownership.blockReasonForDispatch(operation, authorityRef.current);
      if (blocked !== null || operation.dispatched) {
        // The guard failed, so neither the command nor any UI write may proceed.
        return;
      }
      await transmit();
      if (!ownership.canCommit(operation, authorityRef.current)) return;
      notifySuccess(
        `${operationsRecordTitle(target.targetType, target.targetId)} released`,
        'It is no longer yours and has returned to your teams’ claimable work.',
        undefined,
        'VISIT_SESSION'
      );
      // Released detail is dropped from the visible workspace immediately.
      detailController.current?.abort();
      setDetailTarget(null);
      setNotice(null);
      const reconciliation = await refreshAll(session);
      if (!ownership.canCommit(operation, authorityRef.current)) return;
      if (reconciliation !== 'loaded') {
        showNotice(ownershipRefreshFailureNotice(operationsRecordTitle(target.targetType, target.targetId)), session);
      }
    } catch (cause) {
      if (isOperationsSessionChangedError(cause)) return;
      if (!ownership.canCommit(operation, authorityRef.current)) return;
      await reportOwnershipFailure(cause, operation, target.targetType, target.targetId, {
        dropDetailWhenRejected: true
      });
    } finally {
      if (ownership.finish(operation)) setBusyAction(null);
    }
  }, [detail, detailTarget, notifySuccess, ownership, refreshAll, reportOwnershipFailure, showNotice]);

  const navigate = useCallback((next: QueueView) => {
    setView(next);
    returnToRow.current = null;
    window.scrollTo({ top: 0 });
    focusWorkspaceTitle();
  }, []);

  /**
   * Authority has not been confirmed *for the entry on screen*: either the account's verdict is still
   * pending, or the account is granted but this entry has not been verified yet. Both are the same
   * admission decision, and in both cases no protected data is fetched or shown. A denial and a
   * transient failure are reported explicitly by the blocks below instead.
   */
  if (!accessGranted && authority.state !== 'restricted' && authority.state !== 'unavailable') {
    return (
      <section className="oe oe--embedded" aria-label="Operations workspace">
        <div className="oe-main">
          <div className="oe-stack" aria-busy="true">
            <h2 id="oe-page-title" tabIndex={-1} className="oe-sr">Verifying your Operations access</h2>
            <StateMessage
              icon={<Lock size={20} />}
              title="Verifying your Operations access"
              body="Confirming your coordination assignment with the server. Work queues stay hidden until it is confirmed."
            />
            <SkeletonRows rows={3} />
          </div>
        </div>
      </section>
    );
  }

  // Explicit denial: protected state was already cleared and every action is withheld.
  if (authority.state === 'restricted') {
    return (
      <section className="oe oe--embedded" aria-label="Operations workspace">
        <div className="oe-main">
          <div className="oe-stack">
            <h2 id="oe-page-title" tabIndex={-1} className="oe-sr">No access to Operations</h2>
            <StateMessage
              icon={<Lock size={20} />}
              title="You don’t have access to Operations"
              body="This workspace needs an active coordination assignment for a team. If you think this is a mistake, contact your administrator."
            />
          </div>
        </div>
      </section>
    );
  }

  // Recoverable state: authority could not be verified, so protected work stays hidden and paused.
  if (authority.state === 'unavailable') {
    return (
      <section className="oe oe--embedded" aria-label="Operations workspace">
        <div className="oe-main">
          <div className="oe-stack">
            <h2 id="oe-page-title" tabIndex={-1} className="oe-sr">
              Operations access could not be verified
            </h2>
            <StateMessage
              icon={<CircleAlert size={20} />}
              tone="warn"
              title="We couldn’t verify your Operations access"
              body="Your coordination assignment could not be confirmed right now, so protected work is hidden and ownership actions are paused. Nothing about your work has changed."
            >
              <button type="button" className="oe-btn oe-btn--primary oe-btn--md" onClick={authority.revalidate}>
                <RefreshCw size={14} aria-hidden="true" />
                Retry
              </button>
            </StateMessage>
          </div>
        </div>
      </section>
    );
  }

  const signedOut = [attention.status, myWork.status, claimable.status].includes('signed-out');
  const accessLost = [attention.status, myWork.status, claimable.status].includes('denied');

  if (signedOut || accessLost) {
    return (
      <section className="oe oe--embedded" aria-label="Operations workspace">
        <div className="oe-main">
          <div className="oe-stack">
            <h2 id="oe-page-title" tabIndex={-1} className="oe-sr">
              {signedOut ? 'Your session has ended' : 'No access to Operations'}
            </h2>
            <StateMessage
              icon={signedOut ? <Lock size={20} /> : <Lock size={20} />}
              title={signedOut ? 'Your session has ended' : 'You don’t have access to Operations'}
              body={signedOut
                ? 'Sign in again to continue to the Operations workspace. Your work has not changed.'
                : 'This workspace needs an active coordination assignment for a team. If you think this is a mistake, contact your administrator.'}
            />
          </div>
        </div>
      </section>
    );
  }

  const scopes = operationsCapabilityScopes(capabilities);
  const noticeVisible = notice !== null
    && notice.sessionIdentity !== null
    && notice.sessionIdentity === (readOperationsSession()?.identity ?? null);
  const navItems: { view: QueueView; label: string; icon: typeof Home; count: number | null; flagged?: boolean }[] = [
    { view: 'home', label: 'Overview', icon: Home, count: null },
    { view: 'attention', label: 'Needs attention', icon: Flag, count: attention.status === 'ready' ? attention.totalCount : null, flagged: true },
    { view: 'my-work', label: 'My work', icon: ListChecks, count: myWork.status === 'ready' ? myWork.totalCount : null },
    { view: 'claimable', label: 'Claimable work', icon: Inbox, count: claimable.status === 'ready' ? claimable.totalCount : null }
  ];

  return (
    <section className="oe oe--embedded" aria-label="Operations workspace">
      <div className="oe-main">
        <div className="oe-stack">
          <nav className="oe-subnav" aria-label="Operations queues">
            {navItems.map((item) => {
              const Icon = item.icon;
              const current = detailTarget === null && view === item.view;
              return (
                <button
                  key={item.view}
                  type="button"
                  className={`oe-subnav-btn${item.flagged ? ' oe-subnav-btn--flag' : ''}`}
                  aria-current={current ? 'page' : undefined}
                  onClick={() => { closeDetail(); navigate(item.view); }}
                >
                  <Icon size={16} aria-hidden="true" />
                  {item.label}
                  {item.count !== null ? <span className="oe-subnav-count">{item.count}</span> : null}
                </button>
              );
            })}
          </nav>

          {noticeVisible && notice ? (
            <WorkspaceNotice
              key={notice.id}
              text={notice.text}
              noticeRef={noticeRef}
              onDismiss={() => setNotice(null)}
            />
          ) : null}

          {detailTarget ? (
            <OperationsWorkDetail
              targetType={detailTarget.targetType}
              targetId={detailTarget.targetId}
              source={detailTarget.source}
              state={detail}
              busyAction={busyAction}
              onBack={closeDetail}
              onRetry={() => void openDetail(detailTarget)}
              onClaim={() => {
                if (detail.status === 'ready' && detail.snapshot.claimable) void claimRoot(detail.snapshot.claimable);
              }}
              onRelease={() => void releaseRoot()}
            />
          ) : view === 'home' ? (
            <OverviewView
              scopes={scopes}
              attention={attention}
              myWork={myWork}
              claimable={claimable}
              onNavigate={navigate}
              onOpen={openDetail}
              onRetryAttention={() => void loadAttention(0)}
              onRetryClaimable={() => void loadClaimable(0)}
            />
          ) : (
            <QueueScreen
              view={view}
              attention={attention}
              myWork={myWork}
              claimable={claimable}
              busyClaimKey={busyClaimKey}
              blocked={busyAction !== null}
              onPage={(page) => {
                if (view === 'attention') void loadAttention(page);
                else if (view === 'my-work') void loadMyWork(page);
                else void loadClaimable(page);
              }}
              onOpen={openDetail}
              onClaim={(item) => void claimRoot(item)}
              onRefresh={() => {
                if (view === 'attention') void loadAttention(attention.page);
                else if (view === 'my-work') void loadMyWork(myWork.page);
                else void loadClaimable(claimable.page);
              }}
              onNavigate={navigate}
            />
          )}
        </div>
      </div>
    </section>
  );
};

const OverviewView: React.FC<{
  scopes: { teamId: number; cityName: string; teamName: string }[];
  attention: PageState<OperationsMyWorkItem>;
  myWork: PageState<OperationsMyWorkItem>;
  claimable: PageState<ClaimableWorkItem>;
  onNavigate: (view: QueueView) => void;
  onOpen: (target: DetailTarget) => void;
  onRetryAttention: () => void;
  onRetryClaimable: () => void;
}> = ({ scopes, attention, myWork, claimable, onNavigate, onOpen, onRetryAttention, onRetryClaimable }) => {
  const queues = [
    { view: 'attention' as QueueView, label: 'Needs attention', hint: 'Flagged for your next step', queue: attention },
    { view: 'my-work' as QueueView, label: 'My work', hint: 'Request and session roots you own', queue: myWork },
    { view: 'claimable' as QueueView, label: 'Claimable work', hint: 'Unclaimed in your teams', queue: claimable }
  ];

  return (
    <div className="oe-stack">
      <PageHeader title="Today’s work" />

      {scopes.length > 0 ? (
        <ul className="oe-scopes" aria-label="Your coordination scope">
          {scopes.map((scope) => (
            <li key={scope.teamId} className="oe-scope-pill">
              <MapPin size={14} aria-hidden="true" />
              <span className="oe-scope-pill-text">
                <span className="oe-scope-pill-team">{scope.teamName}</span>
                <span className="oe-scope-pill-city">{scope.cityName}</span>
              </span>
            </li>
          ))}
        </ul>
      ) : null}

      <section aria-labelledby="oe-queues-title">
        <h3 id="oe-queues-title" className="oe-sr">Work queues</h3>
        <ul className="oe-queues">
          {queues.map((queue) => (
            <li key={queue.view}>
              <button
                type="button"
                className={`oe-queue${queue.view === 'attention' && queue.queue.totalCount > 0 ? ' oe-queue--flag' : ''}`}
                onClick={() => onNavigate(queue.view)}
              >
                <span className="oe-queue-label">{queue.label}</span>
                {queue.queue.status === 'loading' ? (
                  <span className="oe-skel oe-skel--num" />
                ) : queue.queue.status !== 'ready' ? (
                  <span className="oe-queue-unavail">Count unavailable</span>
                ) : (
                  <span className="oe-queue-total oe-num">{queue.queue.totalCount}</span>
                )}
                <span className="oe-queue-hint">{queue.hint}</span>
                <ArrowRight className="oe-queue-arrow" size={16} aria-hidden="true" />
              </button>
            </li>
          ))}
        </ul>
      </section>

      <div className="oe-board-wrap">
        <div className="oe-board">
          <HomePanel
            title="Needs attention"
            count={attention.status === 'ready'
              ? `${Math.min(ATTENTION_LIMIT, attention.items.length)} of ${attention.totalCount}`
              : null}
            state={attention.status}
            emptyText="Nothing in your queue needs attention."
            linkLabel="View all"
            onLink={() => onNavigate('attention')}
            onRetry={onRetryAttention}
          >
            {attention.items.slice(0, ATTENTION_LIMIT).map((item) => (
              <li key={workItemKey(item)}>
                <button
                  type="button"
                  className="oe-mini-row"
                  data-row-key={workItemKey(item)}
                  onClick={() => onOpen({ targetType: item.targetType, targetId: item.targetId, source: 'owned' })}
                >
                  <span className="oe-mini-top">
                    <TypeTag targetType={item.targetType} />
                    <span className="oe-ref">#{item.targetId}</span>
                    <span className="oe-mini-aside">
                      <StatusBadge targetType={item.targetType} status={item.status} />
                    </span>
                  </span>
                  <span className="oe-mini-line">
                    {item.targetType === 'SESSION' ? (
                      <>
                        <span className={item.scheduledAt ? 'oe-mini-strong' : 'oe-quiet'}>
                          {formatOperationsDateTime(item.scheduledAt) ?? 'Not scheduled'}
                        </span>
                        <TenantConfirmationLabel value={item.tenantConfirmationState} />
                      </>
                    ) : (
                      <span className={item.updatedAt ? '' : 'oe-quiet'}>
                        {item.updatedAt ? `Updated ${formatOperationsDateTime(item.updatedAt)}` : 'Update time not recorded'}
                      </span>
                    )}
                  </span>
                  <ScopeLine
                    city={item.cityDisplayName ?? 'City not recorded'}
                    team={item.teamDisplayName ?? 'Team not recorded'}
                  />
                </button>
              </li>
            ))}
          </HomePanel>

          <HomePanel
            title="Available to claim"
            count={claimable.status === 'ready'
              ? `${Math.min(CLAIM_LIMIT, claimable.items.length)} of ${claimable.totalCount}`
              : null}
            state={claimable.status}
            emptyText="No unclaimed work in your teams right now."
            linkLabel="Claimable work"
            onLink={() => onNavigate('claimable')}
            onRetry={onRetryClaimable}
          >
            {claimable.items.slice(0, CLAIM_LIMIT).map((item) => (
              <li key={workItemKey(item)}>
                <button
                  type="button"
                  className="oe-mini-row"
                  data-row-key={workItemKey(item)}
                  onClick={() => onOpen({ targetType: item.targetType, targetId: item.targetId, source: 'claimable' })}
                >
                  <span className="oe-mini-top">
                    <TypeTag targetType={item.targetType} />
                    <span className="oe-ref">#{item.targetId}</span>
                    <span className="oe-mini-aside oe-mini-aside--text">
                      {item.targetType === 'SESSION'
                        ? linkedRequestsLabel(item.linkedRequestVersions.length)
                        : 'Standalone'}
                    </span>
                  </span>
                  <ScopeLine
                    city={item.cityDisplayName ?? 'City not recorded'}
                    team={item.teamDisplayName ?? 'Team not recorded'}
                  />
                </button>
              </li>
            ))}
          </HomePanel>
        </div>
      </div>
    </div>
  );
};

const HomePanel: React.FC<{
  title: string;
  count: string | null;
  state: QueueStatus;
  emptyText: string;
  linkLabel: string;
  onLink: () => void;
  onRetry: () => void;
  children: React.ReactNode;
}> = ({ title, count, state, emptyText, linkLabel, onLink, onRetry, children }) => {
  const hasChildren = React.Children.count(children) > 0;
  return (
    <section className="oe-panel" aria-label={title}>
      <header className="oe-panel-head">
        <h4 className="oe-h2">
          {title}
          {count ? <span className="oe-panel-count oe-num"> · {count}</span> : null}
        </h4>
        <button type="button" className="oe-link" onClick={onLink}>
          {linkLabel}
          <ArrowRight size={14} aria-hidden="true" />
        </button>
      </header>

      {state === 'loading' ? (
        <ul className="oe-mini" aria-busy="true">
          {Array.from({ length: 3 }, (_, index) => (
            <li key={index} className="oe-mini-row oe-mini-row--skeleton">
              <span className="oe-skel" style={{ width: '30%' }} />
              <span className="oe-skel" style={{ width: '70%' }} />
            </li>
          ))}
        </ul>
      ) : state !== 'ready' ? (
        <div className="oe-panel-msg" role="alert">
          <p>This section couldn’t be loaded.</p>
          <button type="button" className="oe-btn oe-btn--secondary oe-btn--sm" onClick={onRetry}>
            <RefreshCw size={14} aria-hidden="true" />
            Try again
          </button>
        </div>
      ) : !hasChildren ? (
        <div className="oe-panel-msg">
          <Inbox size={18} aria-hidden="true" />
          <p>{emptyText}</p>
        </div>
      ) : (
        <ul className="oe-mini">{children}</ul>
      )}
    </section>
  );
};

const QueueScreen: React.FC<{
  view: QueueKey;
  attention: PageState<OperationsMyWorkItem>;
  myWork: PageState<OperationsMyWorkItem>;
  claimable: PageState<ClaimableWorkItem>;
  busyClaimKey: string | null;
  blocked: boolean;
  onPage: (page: number) => void;
  onOpen: (target: DetailTarget) => void;
  onClaim: (item: ClaimableWorkItem) => void;
  onRefresh: () => void;
  onNavigate: (view: QueueView) => void;
}> = ({ view, attention, myWork, claimable, busyClaimKey, blocked, onPage, onOpen, onClaim, onRefresh, onNavigate }) => {
  const copy = QUEUE_COPY[view];
  const queue = view === 'attention' ? attention : view === 'my-work' ? myWork : claimable;

  return (
    <div className="oe-stack">
      <PageHeader
        title={copy.title}
        description={copy.description}
        aside={queue.status === 'ready' ? (
          <p className="oe-total">
            <span className="oe-num">{queue.totalCount}</span> {queue.totalCount === 1 ? 'item' : 'items'}
          </p>
        ) : null}
      />

      {queue.status === 'loading' ? (
        <SkeletonRows />
      ) : queue.status !== 'ready' ? (
        <StateMessage
          icon={<CircleAlert size={20} />}
          tone="warn"
          title={`We couldn’t load ${copy.title.toLowerCase()}`}
          body="Check your connection and try again. Your work has not changed."
        >
          <button type="button" className="oe-btn oe-btn--primary oe-btn--md" onClick={onRefresh}>
            <RefreshCw size={14} aria-hidden="true" />
            Try again
          </button>
        </StateMessage>
      ) : queue.totalCount === 0 ? (
        <StateMessage icon={<Inbox size={20} />} title={copy.emptyTitle} body={copy.emptyBody}>
          {view !== 'claimable' ? (
            <button type="button" className="oe-btn oe-btn--secondary oe-btn--md" onClick={() => onNavigate('claimable')}>
              View claimable work
            </button>
          ) : null}
        </StateMessage>
      ) : view === 'claimable' ? (
        <>
          <ClaimableList
            items={claimable.items}
            label={copy.title}
            busyKey={busyClaimKey}
            onOpen={(item) => onOpen({ targetType: item.targetType, targetId: item.targetId, source: 'claimable' })}
            onClaim={onClaim}
          />
          <Pagination
            page={claimable.page}
            totalPages={claimable.totalPages}
            totalElements={claimable.totalCount}
            pageSize={OPERATIONS_WORK_PAGE_SIZE}
            onPage={onPage}
          />
        </>
      ) : (
        <>
          <OwnedList
            items={view === 'attention' ? attention.items : myWork.items}
            flagged={view === 'attention'}
            label={copy.title}
            onOpen={(item) => onOpen({ targetType: item.targetType, targetId: item.targetId, source: 'owned' })}
          />
          <Pagination
            page={queue.page}
            totalPages={queue.totalPages}
            totalElements={queue.totalCount}
            pageSize={OPERATIONS_WORK_PAGE_SIZE}
            onPage={onPage}
          />
          {blocked ? <p className="oe-sr">An ownership action is in progress.</p> : null}
        </>
      )}
    </div>
  );
};
