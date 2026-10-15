import React, { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { ArrowRight, CircleAlert, Flag, Home, Inbox, ListChecks, Lock, MapPin, RefreshCw } from 'lucide-react';
import { ApiRequestError } from '../../services/apiError';
import {
  OPERATIONS_WORK_PAGE_SIZE,
  operationsWorkspaceService,
  type OperationsRequestContext
} from '../../services/operationsWorkspaceService';
import {
  OperationsSessionChangedError,
  captureOperationsSession,
  isOperationsSessionActive,
  isOperationsSessionChangedError,
  readOperationsSession,
  subscribeOperationsSession,
  type OperationsSession
} from '../../utils/operationsSession';
import {
  OwnershipOperationRegistry,
  operationTargetIsMounted,
  type OwnershipBlockReason,
  type OwnershipOperation,
  type OwnershipOperationKind
} from '../../utils/operationsOwnershipRegistry';
import type { OperationsAuthority } from '../../hooks/useOperationsCapability';
import { useNotification } from '../../context/NotificationContext';
import { propertyService } from '../../services/propertyService';
import {
  addSessionItemCommand,
  claimCommandFor,
  commandFailureDisposition,
  coordinateAttachCommand,
  coordinateNewSessionCommand,
  formatOperationsDateTime,
  linkedRequestsLabel,
  operationsCapabilityScopes,
  operationsDetailFailureState,
  operationsLoadFailureState,
  operationsRecordTitle,
  ownershipOutcomeCopy,
  ownershipRefreshFailureNotice,
  releaseCommandForRequest,
  releaseCommandForSession,
  removeSessionItemCommand,
  reorderSessionItemsCommand,
  requestUnavailableCommand,
  scheduleSelectionCommand,
  schedulingFailureOutcome,
  sessionListingIds,
  setAvailabilityCommand,
  type ClaimableWorkItem,
  type OperationsLoadFailure,
  type OperationsLessorContact,
  type OperationsAvailabilityChoice,
  type OperationsMyWorkItem,
  type OperationsRecommendationView,
  type OperationsRequestDetail,
  type OperationsSchedulingApproval,
  type OperationsSchedulingSelection,
  type OperationsSessionDetail,
  type OperationsTargetType,
  type OwnershipFailureKind,
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
  type OperationsAvailabilityWindowInput,
  type OperationsDetailActions,
  type OperationsDetailSnapshot,
  type OperationsDetailState
} from './OperationsWorkDetail';
import type { AddStopInput, DiscoveryListingOption } from './OperationsDraftItinerary';
import type { OperationsOwnedCandidatePage } from './OperationsRequestCoordination';
import { OperationsScheduleFlow, type ScheduleOutcome } from './OperationsScheduleFlow';
import './operationsWorkspace.css';

type QueueKey = 'attention' | 'my-work' | 'claimable';
type QueueView = 'home' | QueueKey;
type QueueStatus = 'loading' | 'ready' | OperationsLoadFailure;

/**
 * Independent guarded read families. Each is superseded only by a newer request of the same family,
 * so an unrelated read can never cancel the one the user is waiting on.
 */
type GuardedReadFamily = 'owned-candidates' | 'listing-search' | 'ge-ranked' | 'ge-discovery';

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

/**
 * The identity of one protected root, independent of the queue it was opened from. Two `DetailTarget`s
 * describe the same root exactly when their type and id match.
 */
type RootIdentity = Pick<DetailTarget, 'targetType' | 'targetId'>;

/**
 * One mounted instance of a protected root: the root identity plus the generation of the detail that
 * is on screen. Closing a root and opening it again is a *new* instance — new reads, a new revealed
 * contact — so a denial captured against the previous generation never addresses the new one.
 */
interface MountedRootIdentity extends RootIdentity {
  mountGeneration: number;
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

/** The three real endings of one guarded Phase 2A command, plus the two "nothing happened" cases. */
interface GuardedCommandFailure {
  kind: OwnershipFailureKind;
  status: number | null;
  message: string;
  /** The server's own explanation, when it sent one. */
  detail: string | null;
  /**
   * True only when the outcome was actually announced. A refusal whose record was replaced is
   * withheld, so a caller must never publish its drawer outcome either.
   */
  published: boolean;
}

interface GuardedCommandOutcome<Applied> {
  /** `abandoned` covers "the flow stopped before dispatch" and "the operation lost the right to commit". */
  status: 'applied' | 'abandoned' | 'missing-version' | 'failed';
  applied: Applied | null;
  reconciliation: QueueLoadOutcome;
  failure: GuardedCommandFailure | null;
  /**
   * The operation that ran this command, so a caller reporting its outcome can re-evaluate the display
   * verdict in the same tick as its own post-reconciliation write. `null` only when no operation ever
   * began (the lock was already held, or no session could be captured).
   *
   * Only the read-only display verdict may be consulted on it: by the time a caller sees this, the
   * command's `finally` has already released the mutation lock, so the operation is deliberately no
   * longer "current" and no commit guard applies.
   */
  operation: OwnershipOperation | null;
  /**
   * True only when the confirmed result was actually published to the display context this command
   * began in. False means the business success is real but its local publication was withheld because
   * the OE had already moved to another context.
   */
  published: boolean;
}

/**
 * One Phase 2A command, described as the pieces the shared lifecycle needs.
 *
 * `loadFresh` re-reads the authoritative root immediately before the command is built, so the version
 * sent is the one the server just returned; `buildTransmit` returns null when that read carried no
 * version, which means no command may be transmitted at all.
 */
interface GuardedCommandOptions<Fresh, Applied> {
  kind: OwnershipOperationKind;
  /** Key of the control that started this command, so only it shows progress. */
  busyKey: string;
  /** Human name used in the truthful outcome notice. */
  name: string;
  loadFresh: (context: OperationsRequestContext) => Promise<Fresh | null>;
  buildTransmit: (
    fresh: Fresh
  ) => ((context: OperationsRequestContext) => Promise<Applied>) | null;
  /**
   * Publishes the authoritative result the server returned.
   *
   * It must verify the display context in the same tick as each of its own writes and report whether
   * it could publish: a confirmed success whose display context was replaced while the command was in
   * flight stays a success (the caller still reports it), but nothing of it may replace what the OE is
   * looking at now.
   */
  applyResult: (fresh: Fresh, applied: Applied, operation: OwnershipOperation) => boolean;
  /**
   * The fresh read carried no version, so no command could be sent. It receives the initiating
   * operation as well, so a caller that publishes a drawer outcome can bind it to the display context
   * the command was started in — checked after the await that always precedes this call.
   */
  onMissingVersion: (fresh: Fresh, reconciliation: QueueLoadOutcome, operation: OwnershipOperation) => void;
  /** Custom failure handling; without it the standard truthful notice is announced. */
  onFailure?: (cause: unknown, operation: OwnershipOperation) => Promise<void>;
  dropDetailWhenRejected?: boolean;
}

/**
 * The identity of one scheduling drawer instance: the Session it belongs to, plus the generation that
 * moves on every open, close or invalidation. Captured before a booking starts, it lets the confirmed
 * outcome be published only into the exact drawer the command was started from.
 */
interface ScheduleDrawerIdentity {
  sessionId: number | null;
  generation: number;
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
  /** The one in-flight Phase 2A command, so only the control that started it shows progress. */
  const [busyCommand, setBusyCommand] = useState<string | null>(null);
  /** The session whose scheduling drawer is open, or null. */
  const [scheduleSessionId, setScheduleSessionId] = useState<number | null>(null);
  /**
   * The authoritative outcome of the last scheduling attempt. It lives here because the server's
   * returned Session — not the drawer — decides what is true about the visit.
   */
  const [scheduleOutcome, setScheduleOutcome] = useState<ScheduleOutcome | null>(null);
  /**
   * The protected root currently on screen, read synchronously, together with the generation of the
   * mounted instance it belongs to.
   *
   * A denial has to decide *in the same tick* whether the root the server refused is still the one
   * that is mounted — and whether it is still the same *instance* of it the command was started
   * against. That decision cannot be taken from a render-time closure or from this ref at the time
   * the failure arrives: by then the value may belong to a newer record, or to a newer mount of the
   * same record after the OE navigated away and back. The generation disambiguates the two, and the
   * ref always carries the latest committed root.
   */
  const openRootRef = useRef<MountedRootIdentity | null>(null);
  /**
   * The scheduling drawer the workspace currently holds, read synchronously: which Session it belongs
   * to and how many times it has been opened or closed.
   *
   * A confirmed booking publishes its drawer outcome, and that outcome describes one Session in one
   * drawer instance. The Session id alone is not enough to prove it still belongs there: the OE can
   * close the drawer and open it again for the same Session while the booking is in flight, and the
   * outcome of the earlier instance must not appear in the newer one. The generation moves on every
   * open, close or invalidation, so a captured pair identifies the exact instance a command was
   * started from — the same reason the mounted-root ref carries one.
   */
  const scheduleDrawerRef = useRef<ScheduleDrawerIdentity>({ sessionId: null, generation: 0 });
  const mountedDetailRef = useRef<DetailTarget | null>(null);
  const detailMountGenerationRef = useRef(0);
  if (mountedDetailRef.current !== detailTarget) {
    // A different detail is being mounted, or the workspace left the detail entirely. The generation
    // moves forward once per new instance, so an operation bound to a previous instance can never
    // address this one; leaving the detail retires the ref until a new instance is mounted.
    mountedDetailRef.current = detailTarget;
    if (detailTarget !== null) detailMountGenerationRef.current += 1;
    openRootRef.current = detailTarget === null
      ? null
      : {
          targetType: detailTarget.targetType,
          targetId: detailTarget.targetId,
          mountGeneration: detailMountGenerationRef.current
        };
  }

  const mountedRef = useRef(true);
  const queueControllers = useRef<Record<QueueKey, AbortController | null>>({
    attention: null,
    'my-work': null,
    claimable: null
  });
  const detailController = useRef<AbortController | null>(null);
  /**
   * One controller per independent read family, keyed by family.
   *
   * These reads are genuinely independent — the owned-candidate list, the public listing search and
   * the two Ground Executive reads — so they must not share a slot: starting one read would otherwise
   * abort another that the user is still waiting for. Repeating a read within the *same* family still
   * supersedes that family's own previous request, and invalidation aborts every family at once.
   */
  const guardedReadControllers = useRef<Record<string, AbortController | null>>({});
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
   * The scheduling drawer is opened and closed through these helpers — leaving the detail, withdrawing
   * a denied root and an account change all close it the same way — so its state and the identity a
   * command is bound to can never drift apart. Both of them move the generation on: a closed drawer and
   * a re-opened one are different instances, and an outcome captured for the first must never be
   * published into the second.
   */
  const openScheduleDrawer = useCallback((sessionId: number) => {
    scheduleDrawerRef.current = {
      sessionId,
      generation: scheduleDrawerRef.current.generation + 1
    };
    setScheduleOutcome(null);
    setScheduleSessionId(sessionId);
  }, []);

  const closeScheduleDrawer = useCallback(() => {
    scheduleDrawerRef.current = {
      sessionId: null,
      generation: scheduleDrawerRef.current.generation + 1
    };
    setScheduleOutcome(null);
    setScheduleSessionId(null);
  }, []);

  /**
   * True only while the drawer the OE is looking at is still the exact instance a booking was started
   * from — same Session, same open/close generation — and the display context is still the command's
   * own. Evaluated in the same tick as the drawer write it guards, after every awaited reconciliation.
   */
  const scheduleOutcomeContextHolds = useCallback((
    operation: OwnershipOperation | null,
    drawer: ScheduleDrawerIdentity
  ): boolean =>
    operation !== null
    && ownership.displayContextIsOwned(operation)
    && scheduleDrawerRef.current.sessionId === drawer.sessionId
    && scheduleDrawerRef.current.generation === drawer.generation,
    [ownership]);

  /**
   * Forwards a caller's cancellation onto a controller the workspace can also abort, so a read dies
   * immediately when the workspace loses its identity or authority — not whenever its consumer happens
   * to unmount.
   *
   * `family` scopes the supersede rule: a new request aborts only its own family's previous request,
   * never an unrelated read that is still being awaited.
   */
  const beginGuardedRead = useCallback((
    family: GuardedReadFamily,
    signal: AbortSignal
  ): AbortSignal => {
    const controllers = guardedReadControllers.current;
    controllers[family]?.abort();
    const controller = new AbortController();
    controllers[family] = controller;
    if (signal.aborted) controller.abort();
    else signal.addEventListener('abort', () => controller.abort(), { once: true });
    return controller.signal;
  }, []);

  /** Aborts every in-flight guarded read: identity, authority or the whole surface was lost. */
  const abortGuardedReads = useCallback(() => {
    Object.values(guardedReadControllers.current).forEach((controller) => controller?.abort());
  }, []);

  /**
   * Moves the workspace's display onto `next` — another protected root, or out of the detail
   * altogether — and returns the generation of the display context that is now being shown.
   *
   * Every display change goes through here, because the generation is the only thing that can tell the
   * one change a security withdrawal performs *itself* (which must still be announced by the operation
   * that learned the denial) apart from the OE opening another record, or this one again, while a
   * command's failure was still being reconciled (which must never inherit that refusal). The
   * mounted-root identity alone cannot separate the two: both leave the refused instance unmounted.
   *
   * When the change is the *operation's own* authorised transition — a coordination installing the
   * Session the server returned, a claim opening the root it just claimed, a release dropping the root
   * it released — the operation is passed in and records the generation its own publication produced.
   * That keeps the rest of its flow publishable without letting a later user-initiated navigation or
   * remount inherit it.
   */
  const setDisplayContext = useCallback((next: DetailTarget | null, operation?: OwnershipOperation): number => {
    const generation = ownership.advanceDisplayContext();
    setDetailTarget(next);
    if (operation) ownership.markAcceptedDisplayContext(operation);
    return generation;
  }, [ownership]);

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
    abortGuardedReads();
    // Invalidate the live ownership operation: it is cancelled, its pre-mutation reads are aborted
    // and its lock is released so the next valid operation can start cleanly.
    ownership.cancelActive(reason);
    returnToRow.current = null;
    savedScroll.current = {};
    setAttention(initialPageState<OperationsMyWorkItem>());
    setMyWork(initialPageState<OperationsMyWorkItem>());
    setClaimable(initialPageState<ClaimableWorkItem>());
    setDisplayContext(null);
    setDetail({ status: 'loading' });
    setBusyAction(null);
    setBusyClaimKey(null);
    setBusyCommand(null);
    // The drawer is being dropped with everything else, so any outcome an in-flight booking captured
    // for it can no longer be published anywhere: closing it also retires its instance.
    closeScheduleDrawer();
    setNotice(null);
    if (options.neutralView === true) {
      setView('home');
      if (typeof window !== 'undefined') window.scrollTo({ top: 0 });
    }
  }, [abortGuardedReads, closeScheduleDrawer, ownership, setDisplayContext]);

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
      abortGuardedReads();
    };
  }, [abortGuardedReads, ownership]);

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
    /**
     * An operation-triggered open is a *publication* like any other: it may only move the workspace
     * while the display context is still the one that operation began in. A claim whose response
     * arrives after the OE opened another record must not navigate over that record, and must not
     * replace the detail it is showing — so the display verdict is part of the context this call
     * validates, both before the protected write below and again in the `contextHolds` re-check after
     * the awaited read.
     */
    const contextHolds = () => (operation
      ? ownership.canCommit(operation, authorityRef.current) && ownership.displayContextIsOwned(operation)
      : isOperationsSessionActive(captured) && ownership.sessionEpoch === epoch);
    if (!contextHolds()) return;
    savedScroll.current[view] = window.scrollY;
    returnToRow.current = { view, rowKey: workItemKey(target) };
    detailController.current?.abort();
    const controller = new AbortController();
    detailController.current = controller;
    setNotice(null);
    // A fresh object identity marks a new mounted instance of the root: even re-opening a record is a
    // new context, distinct from any operation started against the previous mount of it.
    setDisplayContext(
      { targetType: target.targetType, targetId: target.targetId, source: target.source },
      operation
    );
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
  }, [view, ownership, setDisplayContext]);

  const closeDetail = useCallback(() => {
    detailController.current?.abort();
    abortGuardedReads();
    closeScheduleDrawer();
    setDisplayContext(null);
  }, [abortGuardedReads, closeScheduleDrawer, setDisplayContext]);

  /**
   * The exact mounted instance a command acts on, captured the moment its operation begins.
   *
   * The command's own root identity decides the target — the item being claimed, the detail being
   * released, the root a guarded command re-reads — and it is bound to the displayed instance only
   * when that instance *is* the command's root. A command whose root is not on screen (a claim
   * started from the list) has no mounted detail of its own, so `null` is recorded and nothing may
   * ever be withdrawn for it.
   */
  const mountedTargetFor = useCallback((root: RootIdentity): MountedRootIdentity | null => {
    const open = openRootRef.current;
    return open !== null && open.targetType === root.targetType && open.targetId === root.targetId
      ? open
      : null;
  }, []);

  /**
   * Withdraws the exact protected root instance a failed command acted on, after the server has
   * definitively denied access to it.
   *
   * A learned denial must not leave the previously revealed detail — including a revealed lessor
   * name and number — mounted behind a now-refused root. Aborting the detail transport and the read
   * families invalidates their responses, and clearing the target unmounts the detail (and with it
   * the contact panel, whose own cleanup invalidates its reveal generation).
   *
   * Every action here is synchronous, so callers decide and apply the withdrawal in one tick,
   * *before* awaiting any reconciliation. Only the instance the operation itself was started against
   * may be withdrawn: a record the OE opened later — or the same record re-opened in a new mount
   * context — is never cleared by an older command's denial.
   *
   * Clearing the target is also the one display change that belongs to this operation, so it is
   * recorded on the operation: a refusal whose own record it correctly unmounted still describes the
   * context on screen, while any later navigation past that withdrawal does not.
   *
   * This is deliberately *not* global account revocation: it touches only the open root's protected
   * state, leaving the queues and a still-valid authenticated session in place for the next action.
   */
  const withdrawDeniedRoot = useCallback((operation: OwnershipOperation) => {
    if (!operationTargetIsMounted(operation, openRootRef.current)) return;
    detailController.current?.abort();
    abortGuardedReads();
    setDisplayContext(null);
    ownership.markWithdrewOwnDisplayContext(operation);
    // The drawer leaves the screen with the refused root, and its instance is retired: no outcome an
    // in-flight command captured for it may be published into whatever the OE opens next.
    closeScheduleDrawer();
  }, [abortGuardedReads, closeScheduleDrawer, ownership, setDisplayContext]);

  /**
   * True while a refused operation's own record is still the display context the refusal describes.
   *
   * The workspace is shared: while one command is in flight the OE can open another record — or leave
   * and re-open the same one — so a denial learned afterwards describes a context that was replaced.
   * Two states keep a refusal truthful, and nothing else does:
   *   - the exact record instance the operation was started against is still mounted (nothing about
   *     the display has changed since it began); or
   *   - the only display change since it began is the withdrawal the operation performed *itself*, so
   *     the refused root is gone because this denial took it away and the OE has not navigated since.
   * A command with no mounted detail of its own (a claim started from the list) addresses the queues,
   * so it keeps its unconditional announcement.
   *
   * This must be evaluated *after* every awaited reconciliation and immediately before the notice is
   * written: a verdict taken before the await describes the context that existed then, which is how an
   * older refusal used to land in a newer record after a held refresh resolved.
   */
  const noticeContextHolds = useCallback((operation: OwnershipOperation): boolean =>
    operation.target === null
    || operationTargetIsMounted(operation, openRootRef.current)
    || ownership.withdrewOwnDisplayContext(operation), [ownership]);

  /**
   * Reports what actually happened to one failed ownership command and, when it is safe, reconciles
   * the authoritative queues so the message can state the real outcome.
   *
   * Three outcomes are kept apart, because they carry different truths:
   *  - nothing was transmitted → this action provably changed no ownership;
   *  - the backend refused it (HTTP 4xx) → report the real rejection and the recovery;
   *  - it was transmitted and the outcome is unconfirmed → say so, never resend it, and only reload
   *    the authoritative state while the initiating session and authorization are still valid.
   *
   * Anything that must stop showing this root — a definitive target denial (`403`/`404`), or the
   * release conflict whose owner asked for it — withdraws it *before* the reconciliation is awaited,
   * so a refused record and any lessor contact revealed inside it cannot stay mounted for as long as
   * the queue reads take. The reconciliation is a read: it follows the withdrawal and never gates it.
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
    const disposition = commandFailureDisposition(input, options);
    // The denial is acted on synchronously, while the operation may still commit and the refused root
    // is still the one mounted. Nothing of a newer record or a newer account is ever touched: the
    // withdrawal addresses only the instance this operation was started against.
    if (disposition.withdrawRoot && ownership.canCommit(operation, authorityRef.current)) {
      withdrawDeniedRoot(operation);
    }
    // Reconciliation is a protected read: it only happens while this exact operation may still commit
    // (current session, current epoch, authorization still granted). Its result is the real outcome,
    // not a permission to attempt it.
    let reconciliation: OwnershipReconciliation = 'not-attempted';
    if (disposition.reconcile && ownership.canCommit(operation, authorityRef.current)) {
      reconciliation = await refreshAll(operation.session);
    }
    // The operation may have been invalidated while it reconciled: suppress its notice and every other
    // protected write so nothing lands under a newer account or a newer operation.
    if (!ownership.canCommit(operation, authorityRef.current)) return;
    /**
     * A definitive refusal is announced only while it still describes the display context on screen.
     * The verdict is deliberately taken *here* — after the awaited reconciliation, immediately before
     * the notice is written — and never from the state that existed when the failure was classified:
     * the OE can open another record, or re-open this one, while that read is still in flight, and a
     * verdict captured earlier would then publish the refusal into a context it does not describe.
     * `noticeContextHolds` still honours the withdrawal this operation performed itself, so a denial
     * that correctly unmounted its own refused root keeps its truthful notice. Every other outcome (a
     * conflict, an unconfirmed transmission, a stopped action) keeps its existing announcement: the OE
     * still needs to know what happened, and those copies name the record they are about.
     */
    if (disposition.targetDenied && !noticeContextHolds(operation)) return;

    const copy = ownershipOutcomeCopy(name, { ...input, reconciliation });
    showNotice(copy.message, operation.session);
  }, [noticeContextHolds, ownership, refreshAll, showNotice, withdrawDeniedRoot]);

  const claimRoot = useCallback(async (item: ClaimableWorkItem) => {
    // One live ownership operation at a time: a repeated tap while one is running is ignored.
    if (ownership.isLocked()) return;
    const session = captureOperationsSession();
    if (!session) return;
    // The operation is bound to this component, this workspace surface, this exact session and the
    // authorization generation in force now — and to the exact mounted instance of the item's root,
    // when that instance is on screen — before anything is awaited.
    const operation = ownership.begin(
      'claim',
      session,
      authorityRef.current.authorizationGeneration(),
      mountedTargetFor({ targetType: item.targetType, targetId: item.targetId })
    );
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
      /**
       * The navigation a confirmed claim carries — moving to My work, and then opening the root it just
       * claimed — is a local publication like any other, so it belongs to the display context the claim
       * began in. A claim whose `204` arrives after the OE opened another record must neither move the
       * queue underneath that record nor replace the detail it is showing; the success itself is still
       * reported above, truthfully, and the command is never resent.
       */
      if (ownership.displayContextIsOwned(operation)) setView('my-work');
      const reconciliation = await refreshAll(session);
      if (!ownership.canCommit(operation, authorityRef.current)) return;
      await openDetail({ targetType: fresh.targetType, targetId: fresh.targetId, source: 'owned' }, session, operation);
      // `openDetail` awaits its own detail read, so the account, the authorization generation or the
      // component-local epoch can all move while it is in flight — even though the open itself declined
      // to apply its snapshot. Re-validate the same operation/session before the only write left in this
      // continuation, so a stale claim can never write a notice (or any other UI state) into a newer
      // account's workspace. The guard, not a render-time identity filter, is what prevents the write.
      if (!ownership.canCommit(operation, authorityRef.current)) return;
      if (reconciliation !== 'loaded' && ownership.displayContextIsOwned(operation)) {
        showNotice(ownershipRefreshFailureNotice(title), session);
      }
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
  }, [mountedTargetFor, notifySuccess, openDetail, ownership, refreshAll, reportOwnershipFailure, showNotice]);

  const releaseRoot = useCallback(async () => {
    if (ownership.isLocked() || detail.status !== 'ready') return;
    const snapshot = detail.snapshot;
    const target = detailTarget;
    if (!target) return;
    const session = captureOperationsSession();
    if (!session) return;
    // The release acts on the detail on screen: the target is the exact mounted instance of it,
    // captured before anything is awaited.
    const operation = ownership.begin(
      'release',
      session,
      authorityRef.current.authorizationGeneration(),
      mountedTargetFor(target)
    );
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
      /**
       * Dropping the released root is this operation's own display transition, so it is published only
       * while the released root is still the display context the release began in: a `204` that arrives
       * after the OE opened another record must not clear that newer record or its revealed contact.
       * The transition is recorded on the operation as its own, so the notice that follows the
       * reconciliation is still published when it does happen.
       */
      if (ownership.displayContextIsOwned(operation)) {
        // Released detail is dropped from the visible workspace immediately.
        detailController.current?.abort();
        setDisplayContext(null, operation);
        setNotice(null);
      }
      const reconciliation = await refreshAll(session);
      if (!ownership.canCommit(operation, authorityRef.current)) return;
      if (reconciliation !== 'loaded' && ownership.displayContextIsOwned(operation)) {
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
  }, [detail, detailTarget, mountedTargetFor, notifySuccess, ownership, refreshAll, reportOwnershipFailure, setDisplayContext, showNotice]);

  /* ---------------------------------------------------------------- *
   * Phase 2A guarded command lifecycle
   * ---------------------------------------------------------------- */

  /**
   * Reports the real outcome of a failed Phase 2A command: nothing was transmitted, the backend
   * refused it, or it was transmitted and the outcome is unknown. The copy comes from the same
   * classifier the ownership commands use, so no command can ever claim an unproven result, and an
   * unconfirmed command is never resubmitted.
   *
   * The root this command acted on is captured synchronously and withdrawn *before* the
   * reconciliation is awaited, so a definitive `403`/`404` — from the fresh pre-command read or from
   * the mutation itself — takes the protected detail and any revealed lessor contact off the screen in
   * the same tick the refusal is classified, whether or not the queue reads that follow ever answer.
   */
  const reportCommandFailure = useCallback(async (
    cause: unknown,
    operation: OwnershipOperation,
    name: string,
    options: { dropDetailWhenRejected?: boolean } = {}
  ): Promise<GuardedCommandFailure> => {
    const apiError = cause instanceof ApiRequestError ? cause : null;
    const input: OwnershipOutcomeInput = {
      dispatched: operation.dispatched,
      status: apiError?.status ?? null,
      message: apiError?.message ?? null
    };
    const disposition = commandFailureDisposition(input, options);
    /**
     * The root this command acted on is the one its operation captured when it began — never whatever
     * happens to be on screen when the failure arrives. `withdrawDeniedRoot` re-checks that exact
     * mounted instance in the same tick, so a record the OE opened later, or the same record re-opened
     * after navigating away, is never dropped by an older command's denial. The withdrawal is gated on
     * the operation still being allowed to commit, exactly like every other write it makes.
     */
    if (disposition.withdrawRoot && ownership.canCommit(operation, authorityRef.current)) {
      withdrawDeniedRoot(operation);
    }
    let reconciliation: OwnershipReconciliation = 'not-attempted';
    if (disposition.reconcile && ownership.canCommit(operation, authorityRef.current)) {
      reconciliation = await refreshAll(operation.session);
    }
    if (!ownership.canCommit(operation, authorityRef.current)) {
      return { kind: disposition.kind, status: apiError?.status ?? null, message: '', detail: null, published: false };
    }
    /**
     * A definitive refusal is announced, and an outcome reported, only while it still describes the
     * display context on screen. The verdict is taken *here* — after the awaited reconciliation and
     * immediately before the notice — never from the state that existed when the failure was
     * classified, because the OE can open another record, or re-open this one, while that read is
     * still in flight. A refusal whose record was replaced belongs to the context that is gone, and
     * both its announcement and its caller's outcome are withheld (`published: false`) rather than
     * applied to whatever is displayed; every other outcome keeps its existing truthful announcement.
     */
    if (disposition.targetDenied && !noticeContextHolds(operation)) {
      return { kind: disposition.kind, status: apiError?.status ?? null, message: '', detail: null, published: false };
    }
    const copy = ownershipOutcomeCopy(name, {
      ...input,
      /**
       * A `403`/`404` answered before dispatch is still the server refusing *this* root during the
       * fresh pre-command read, so it is reported as a refusal rather than as "stopped before
       * anything left the app".
       */
      dispatched: input.dispatched || disposition.targetDenied,
      reconciliation
    });
    showNotice(copy.message, operation.session);
    return {
      kind: disposition.kind,
      status: apiError?.status ?? null,
      message: copy.message,
      detail: apiError?.details ?? apiError?.message ?? null,
      published: true
    };
  }, [noticeContextHolds, ownership, refreshAll, showNotice, withdrawDeniedRoot]);

  /**
   * Points the workspace at the authoritative Session the server returned.
   *
   * This is a local publication, so it is gated on the display context in the same tick as the write:
   * a `200` that arrives after the OE opened another record — or re-opened this one in a newer mount —
   * must not replace that record's detail with the root it carries. The transition it performs is
   * recorded as the operation's own, so the rest of that same flow stays publishable.
   * Returns false when nothing was published.
   */
  const applyAuthoritativeSession = useCallback((
    session: OperationsSessionDetail,
    operation: OwnershipOperation
  ): boolean => {
    if (!ownership.displayContextIsOwned(operation)) return false;
    setDisplayContext({ targetType: 'SESSION', targetId: session.sessionId, source: 'owned' }, operation);
    setDetail({ status: 'ready', snapshot: { kind: 'session', session } });
    return true;
  }, [ownership, setDisplayContext]);

  /** Points the workspace at the authoritative Request the server returned. Gated exactly like a Session. */
  const applyAuthoritativeRequest = useCallback((
    request: OperationsRequestDetail,
    operation: OwnershipOperation
  ): boolean => {
    if (!ownership.displayContextIsOwned(operation)) return false;
    setDisplayContext({ targetType: 'REQUEST', targetId: request.requestId, source: 'owned' }, operation);
    setDetail({ status: 'ready', snapshot: { kind: 'request', request } });
    return true;
  }, [ownership, setDisplayContext]);

  /**
   * One guarded Phase 2A command.
   *
   * The order is the same as a claim or a release, and it is the whole point of this helper: bind the
   * operation to this component, this surface, this credential and this authorization generation;
   * re-verify authority for that exact session; re-read the authoritative root; refuse to transmit
   * without a version; make the final synchronous dispatch decision in the same tick as the POST; and
   * apply the returned result only while the operation may still commit.
   */
  const runGuardedCommand = useCallback(async <Fresh, Applied,>(
    options: GuardedCommandOptions<Fresh, Applied>
  ): Promise<GuardedCommandOutcome<Applied>> => {
    const nothing: GuardedCommandOutcome<Applied> = {
      status: 'abandoned',
      applied: null,
      reconciliation: 'skipped',
      failure: null,
      operation: null,
      published: false
    };
    // A repeated tap while one command owns the lock is ignored, never queued.
    if (ownership.isLocked()) return nothing;
    const session = captureOperationsSession();
    if (!session) return nothing;
    // Every Phase 2A command re-reads and mutates the root that is on screen, so the operation is
    // bound to that exact mounted instance before anything is awaited.
    const operation = ownership.begin(
      options.kind,
      session,
      authorityRef.current.authorizationGeneration(),
      openRootRef.current
    );
    setBusyCommand(options.busyKey);
    setNotice(null);
    try {
      if (!await authorityRef.current.verifySession(session, { signal: operation.controller.signal })) {
        return nothing;
      }
      if (!ownership.canCommit(operation, authorityRef.current)) return nothing;
      const fresh = await options.loadFresh({ session, signal: operation.controller.signal });
      if (fresh === null || !ownership.canCommit(operation, authorityRef.current)) return nothing;
      const transmit = options.buildTransmit(fresh);
      if (transmit === null) {
        // No version means no command: reconcile the reads first so the message can be truthful.
        const reconciliation = await refreshAll(session);
        if (!ownership.canCommit(operation, authorityRef.current)) return nothing;
        options.onMissingVersion(fresh, reconciliation, operation);
        return { ...nothing, status: 'missing-version', reconciliation };
      }
      const blocked = ownership.blockReasonForDispatch(operation, authorityRef.current);
      if (blocked !== null || operation.dispatched) return nothing;
      const onDispatch = () => { ownership.markDispatched(operation); };
      const applied = await transmit({ session, signal: operation.controller.signal, onDispatch });
      if (!ownership.canCommit(operation, authorityRef.current)) return nothing;
      /**
       * A validated server success is a success whether or not this display still belongs to the
       * command (`A. authoritative business completion`). What may only happen while the display
       * context the command began in — or one it published itself — is still the one in force is the
       * *local publication* that carries it (`B. local UI publication`): installing the returned root,
       * navigating to it, or publishing a drawer outcome. Each apply helper takes that verdict in the
       * same tick as its own write; a caller's own post-reconciliation write re-takes it in its tick.
       * The command is never retransmitted and a suppressed publication is never reported as an
       * uncertain outcome.
       */
      const published = options.applyResult(fresh, applied, operation);
      const reconciliation = await refreshAll(session);
      if (!ownership.canCommit(operation, authorityRef.current)) return nothing;
      return { status: 'applied', applied, reconciliation, failure: null, operation, published };
    } catch (cause) {
      if (isOperationsSessionChangedError(cause)) return nothing;
      if (!ownership.canCommit(operation, authorityRef.current)) return nothing;
      if (options.onFailure) {
        await options.onFailure(cause, operation);
        return { ...nothing, status: 'failed' };
      }
      const failure = await reportCommandFailure(cause, operation, options.name, {
        dropDetailWhenRejected: options.dropDetailWhenRejected
      });
      return { ...nothing, status: 'failed', failure };
    } finally {
      // Only the operation that owns the lock clears it and re-enables the controls.
      if (ownership.finish(operation)) setBusyCommand(null);
    }
  }, [ownership, refreshAll, reportCommandFailure]);

  /**
   * True while the display context a confirmed command ran in — or one it published itself — is still
   * the one on screen, for a caller's own post-reconciliation write.
   *
   * A command's `finally` has already released the mutation lock when a caller resumes, so the commit
   * guard deliberately no longer applies; this verdict asks only about the *display*, which is what a
   * record-specific notice and a drawer outcome depend on. It is read in the same tick as the write it
   * guards, after the awaited reconciliation inside the command, so a completion that belongs to a
   * context the OE has left is never published into the context that replaced it.
   */
  const commandContextHolds = useCallback(
    (outcome: GuardedCommandOutcome<unknown>): boolean =>
      outcome.operation !== null && ownership.displayContextIsOwned(outcome.operation),
    [ownership]
  );

  /**
   * One server page of the caller's owned roots, used to offer draft sessions and requests as
   * candidates. The page is discarded unless the identity and component epoch are still the ones that
   * asked for it, and the server's own page count is reported so "load more" is truthful.
   */
  const loadOwnedCandidates = useCallback(async (
    page: number,
    signal: AbortSignal
  ): Promise<OperationsOwnedCandidatePage<OperationsMyWorkItem> | null> => {
    const session = captureOperationsSession();
    if (!session) return null;
    const epoch = ownership.sessionEpoch;
    const guarded = beginGuardedRead('owned-candidates', signal);
    const result = await operationsWorkspaceService.myWork(page, false, { session, signal: guarded });
    if (guarded.aborted || !mountedRef.current || !isOperationsSessionActive(session)) return null;
    if (ownership.sessionEpoch !== epoch) return null;
    return {
      items: result.items,
      page: result.page,
      totalPages: result.totalPages,
      totalCount: result.totalCount
    };
  }, [beginGuardedRead, ownership]);

  /**
   * Public property discovery for choosing a derived stop's listing. This is the existing published
   * catalogue, not an operational-eligibility lookup: the server still decides whether the chosen
   * listing may join the session.
   */
  const searchListings = useCallback(async (
    query: string,
    signal: AbortSignal
  ): Promise<DiscoveryListingOption[]> => {
    const session = captureOperationsSession();
    if (!session) return [];
    const epoch = ownership.sessionEpoch;
    const city = detail.status === 'ready' ? detail.snapshot.session?.city ?? undefined : undefined;
    const guarded = beginGuardedRead('listing-search', signal);
    // The published rental catalogue is searched by city and query; the server still decides whether
    // a chosen listing may actually join the session.
    const page = await propertyService.fetchDiscoveryPage(0, undefined, city, guarded, {
      q: query,
      rentalOnly: true
    });
    if (guarded.aborted || !isOperationsSessionActive(session)) return [];
    if (ownership.sessionEpoch !== epoch) return [];
    return page.properties.slice(0, 8).map((property) => ({
      listingId: property.id,
      title: property.title,
      sector: property.sector ?? null,
      address: null,
      city: property.city ?? null
    }));
  }, [beginGuardedRead, detail, ownership]);

  /**
   * Canonical lessor contact for the open Request. It is read on demand through the session-captured
   * transport, and the answer is refused if the identity or component epoch moved while it was in
   * flight, so a previous account's contact can never be rendered. The caller's signal lets the panel
   * cancel the read the moment the contact context changes.
   */
  const revealRequestContact = useCallback(async (signal: AbortSignal): Promise<OperationsLessorContact> => {
    const request = detail.status === 'ready' ? detail.snapshot.request : undefined;
    if (!request) throw new ApiRequestError('This request is not open.', 404);
    const session = captureOperationsSession();
    if (!session) throw new ApiRequestError('Please sign in to use the Operations workspace.', 401);
    const epoch = ownership.sessionEpoch;
    const contact = await operationsWorkspaceService.lessorContactForRequest(request.requestId, {
      session,
      signal
    });
    if (!isOperationsSessionActive(session) || ownership.sessionEpoch !== epoch) {
      throw new OperationsSessionChangedError();
    }
    return contact;
  }, [detail, ownership]);

  /** Canonical lessor contact for one item of the open Session. */
  const revealItemContact = useCallback(async (
    itemId: number,
    signal: AbortSignal
  ): Promise<OperationsLessorContact> => {
    const openSession = detail.status === 'ready' ? detail.snapshot.session : undefined;
    if (!openSession) throw new ApiRequestError('This session is not open.', 404);
    const session = captureOperationsSession();
    if (!session) throw new ApiRequestError('Please sign in to use the Operations workspace.', 401);
    const epoch = ownership.sessionEpoch;
    const contact = await operationsWorkspaceService.lessorContactForSessionItem(
      openSession.sessionId,
      itemId,
      { session, signal }
    );
    if (!isOperationsSessionActive(session) || ownership.sessionEpoch !== epoch) {
      throw new OperationsSessionChangedError();
    }
    return contact;
  }, [detail, ownership]);

  /**
   * Truthful notice for a fresh read that carried no version, so no command could be sent.
   *
   * It names the record the stopped command was about, so it is published only while that command's
   * display context is still the one on screen: the OE can leave the record while the pre-command read
   * is in flight, and a message about a record that is no longer open belongs to the context that is
   * gone. The verdict is taken here, in the same tick as the write, after the awaited reconciliation.
   */
  const missingVersionNotice = useCallback((
    name: string,
    action: string,
    reconciliation: QueueLoadOutcome,
    operation: OwnershipOperation
  ) => {
    if (!ownership.displayContextIsOwned(operation)) return;
    showNotice(
      reconciliation === 'loaded'
        ? `${name} didn’t return the version needed to ${action}, so nothing was sent. The work lists were refreshed from the server.`
        : `${name} didn’t return the version needed to ${action}, so nothing was sent, and the work lists could not be refreshed just now — refresh them before deciding again.`
    );
  }, [ownership, showNotice]);

  /**
   * Coordinates the open standalone Request into a brand-new DRAFT session. The request is re-read
   * first, and the session the server returns replaces what is on screen — the linked request is then
   * managed from the session instead of appearing as a second root.
   */
  const coordinateNewSession = useCallback(async () => {
    const requestId = detail.status === 'ready' ? detail.snapshot.request?.requestId ?? null : null;
    if (requestId === null) return;
    const name = operationsRecordTitle('REQUEST', requestId);
    const outcome = await runGuardedCommand<OperationsRequestDetail, OperationsSessionDetail>({
      kind: 'coordinate',
      busyKey: 'coordinate',
      name,
      loadFresh: (context) => operationsWorkspaceService.requestDetail(requestId, context),
      buildTransmit: (fresh) => {
        const command = coordinateNewSessionCommand(fresh);
        return command === null
          ? null
          : (context) => operationsWorkspaceService.coordinateRequest(
              fresh.requestId,
              {
                expectedRequestVersion: command.expectedRequestVersion,
                sessionId: null,
                expectedSessionVersion: null
              },
              context
            );
      },
      applyResult: (_fresh, session, operation) => applyAuthoritativeSession(session, operation),
      onMissingVersion: (_fresh, reconciliation, operation) => missingVersionNotice(name, 'coordinate it', reconciliation, operation)
    });
    if (outcome.status === 'applied' && outcome.applied) {
      notifySuccess(
        `${operationsRecordTitle('SESSION', outcome.applied.sessionId)} created`,
        'The request is now its first stop and is managed from the session.',
        undefined,
        'VISIT_SESSION'
      );
      /**
       * The refresh notice names this record, so it belongs to the display context the command ran
       * in — re-taken here, after the awaited reconciliation, and only for a notice the OE can still
       * connect to what is on screen. The confirmed coordination above keeps its own report: it is a
       * fact about the record, not a claim about the display.
       */
      if (outcome.reconciliation !== 'loaded' && commandContextHolds(outcome)) {
        showNotice(ownershipRefreshFailureNotice(name));
      }
    }
  }, [
    applyAuthoritativeSession,
    detail,
    missingVersionNotice,
    notifySuccess,
    commandContextHolds,
    runGuardedCommand,
    showNotice
  ]);

  /**
   * Attaches the open Request to an owned DRAFT session. Both roots are re-read immediately before the
   * command is built, and the server decides whether they may be combined.
   */
  const attachToOwnedDraft = useCallback(async (targetSessionId: number, targetSessionVersion: number) => {
    const requestId = detail.status === 'ready' ? detail.snapshot.request?.requestId ?? null : null;
    if (requestId === null) return;
    const name = operationsRecordTitle('REQUEST', requestId);
    const targetName = operationsRecordTitle('SESSION', targetSessionId);
    const outcome = await runGuardedCommand<OperationsRequestDetail, OperationsSessionDetail>({
      kind: 'coordinate',
      busyKey: 'coordinate',
      name,
      loadFresh: (context) => operationsWorkspaceService.requestDetail(requestId, context),
      buildTransmit: (fresh) => {
        const command = coordinateAttachCommand(fresh, targetSessionId, targetSessionVersion);
        return command === null
          ? null
          : (context) => operationsWorkspaceService.coordinateRequest(
              fresh.requestId,
              {
                expectedRequestVersion: command.expectedRequestVersion,
                sessionId: command.sessionId,
                expectedSessionVersion: command.expectedSessionVersion
              },
              context
            );
      },
      applyResult: (_fresh, session, operation) => applyAuthoritativeSession(session, operation),
      onMissingVersion: (_fresh, reconciliation, operation) => missingVersionNotice(name, 'attach it', reconciliation, operation)
    });
    if (outcome.status === 'applied') {
      notifySuccess(
        `${name} added to ${targetName}`,
        'It is now a stop on that session and no longer appears as its own request.',
        undefined,
        'VISIT_SESSION'
      );
      if (outcome.reconciliation !== 'loaded' && commandContextHolds(outcome)) {
        showNotice(ownershipRefreshFailureNotice(name));
      }
    }
  }, [
    applyAuthoritativeSession,
    detail,
    missingVersionNotice,
    notifySuccess,
    commandContextHolds,
    runGuardedCommand,
    showNotice
  ]);

  /** Records confirmed, factual unavailability for the open standalone Request. */
  const markConfirmedUnavailable = useCallback(async () => {
    const requestId = detail.status === 'ready' ? detail.snapshot.request?.requestId ?? null : null;
    if (requestId === null) return;
    const name = operationsRecordTitle('REQUEST', requestId);
    const outcome = await runGuardedCommand<OperationsRequestDetail, OperationsRequestDetail>({
      kind: 'unavailable',
      busyKey: 'unavailable',
      name,
      loadFresh: (context) => operationsWorkspaceService.requestDetail(requestId, context),
      buildTransmit: (fresh) => {
        const command = requestUnavailableCommand(fresh);
        return command === null
          ? null
          : (context) => operationsWorkspaceService.markRequestUnavailable(
              fresh.requestId,
              command.expectedRequestVersion,
              context
            );
      },
      applyResult: (_fresh, request, operation) => applyAuthoritativeRequest(request, operation),
      onMissingVersion: (_fresh, reconciliation, operation) => missingVersionNotice(name, 'mark it unavailable', reconciliation, operation)
    });
    if (outcome.status === 'applied') {
      notifySuccess(
        `${name} marked unavailable`,
        'The property was confirmed unavailable. The request is kept as read-only history.',
        undefined,
        'VISIT_SESSION'
      );
      if (outcome.reconciliation !== 'loaded' && commandContextHolds(outcome)) {
        showNotice(ownershipRefreshFailureNotice(name));
      }
    }
  }, [
    applyAuthoritativeRequest,
    detail,
    missingVersionNotice,
    notifySuccess,
    commandContextHolds,
    runGuardedCommand,
    showNotice
  ]);

  /** Saves the exact active itinerary order for the open Session. */
  const reorderItems = useCallback(async (itemIds: number[]) => {
    const sessionId = detail.status === 'ready' ? detail.snapshot.session?.sessionId ?? null : null;
    if (sessionId === null) return;
    const name = operationsRecordTitle('SESSION', sessionId);
    const outcome = await runGuardedCommand<OperationsSessionDetail, OperationsSessionDetail>({
      kind: 'reorder',
      busyKey: 'reorder',
      name,
      loadFresh: (context) => operationsWorkspaceService.sessionDetail(sessionId, context),
      buildTransmit: (fresh) => {
        const command = reorderSessionItemsCommand(fresh, itemIds);
        return command === null
          ? null
          : (context) => operationsWorkspaceService.reorderSessionItems(
              sessionId,
              { expectedSessionVersion: command.expectedSessionVersion, itemIds: command.itemIds },
              context
            );
      },
      applyResult: (_fresh, session, operation) => applyAuthoritativeSession(session, operation),
      onMissingVersion: (_fresh, reconciliation, operation) => missingVersionNotice(name, 'save its order', reconciliation, operation)
    });
    if (outcome.status === 'applied') {
      notifySuccess('Itinerary order saved', 'The session now stores the order you set.', undefined, 'VISIT_SESSION');
      if (outcome.reconciliation !== 'loaded' && commandContextHolds(outcome)) {
        showNotice(ownershipRefreshFailureNotice(name));
      }
    }
  }, [
    applyAuthoritativeSession,
    detail,
    missingVersionNotice,
    notifySuccess,
    commandContextHolds,
    runGuardedCommand,
    showNotice
  ]);

  /** Soft-removes one itinerary item, keeping it in the session's history. */
  const removeItem = useCallback(async (itemId: number, reason: string | null) => {
    const sessionId = detail.status === 'ready' ? detail.snapshot.session?.sessionId ?? null : null;
    if (sessionId === null) return;
    const name = operationsRecordTitle('SESSION', sessionId);
    const outcome = await runGuardedCommand<OperationsSessionDetail, OperationsSessionDetail>({
      kind: 'remove-item',
      busyKey: 'remove-item',
      name,
      loadFresh: (context) => operationsWorkspaceService.sessionDetail(sessionId, context),
      buildTransmit: (fresh) => {
        const command = removeSessionItemCommand(fresh, itemId, reason);
        return command === null
          ? null
          : (context) => operationsWorkspaceService.removeSessionItem(
              sessionId,
              command.itemId,
              { expectedSessionVersion: command.expectedSessionVersion, reason: command.reason },
              context
            );
      },
      applyResult: (_fresh, session, operation) => applyAuthoritativeSession(session, operation),
      onMissingVersion: (_fresh, reconciliation, operation) => missingVersionNotice(name, 'remove that stop', reconciliation, operation)
    });
    if (outcome.status === 'applied') {
      notifySuccess('Stop removed', 'It stays in the session history and is not visited.', undefined, 'VISIT_SESSION');
      if (outcome.reconciliation !== 'loaded' && commandContextHolds(outcome)) {
        showNotice(ownershipRefreshFailureNotice(name));
      }
    }
  }, [
    applyAuthoritativeSession,
    detail,
    missingVersionNotice,
    notifySuccess,
    commandContextHolds,
    runGuardedCommand,
    showNotice
  ]);

  /** Adds one derived property to the open DRAFT itinerary. */
  const addStop = useCallback(async (input: AddStopInput) => {
    const sessionId = detail.status === 'ready' ? detail.snapshot.session?.sessionId ?? null : null;
    if (sessionId === null) return;
    const name = operationsRecordTitle('SESSION', sessionId);
    const outcome = await runGuardedCommand<OperationsSessionDetail, OperationsSessionDetail>({
      kind: 'add-item',
      busyKey: 'add-item',
      name,
      loadFresh: (context) => operationsWorkspaceService.sessionDetail(sessionId, context),
      buildTransmit: (fresh) => {
        const command = addSessionItemCommand(fresh, {
          listingId: input.listingId,
          originatingRequestId: input.originatingRequestId,
          origin: input.origin
        });
        return command === null
          ? null
          : (context) => operationsWorkspaceService.addSessionItem(
              sessionId,
              {
                expectedSessionVersion: command.expectedSessionVersion,
                listingId: command.listingId,
                originatingRequestId: command.originatingRequestId,
                origin: command.origin
              },
              context
            );
      },
      applyResult: (_fresh, session, operation) => applyAuthoritativeSession(session, operation),
      onMissingVersion: (_fresh, reconciliation, operation) => missingVersionNotice(name, 'add that stop', reconciliation, operation)
    });
    if (outcome.status === 'applied') {
      notifySuccess(
        'Stop added',
        'The new stop starts with availability pending, so scheduling stays blocked until it is confirmed.',
        undefined,
        'VISIT_SESSION'
      );
      /**
       * Both notices describe one session's own itinerary, so they are published only while that
       * session's display context is still the command's: the OE can leave the record while the stop
       * command and its reconciliation are in flight, and neither message belongs to what replaced it.
       */
      if (commandContextHolds(outcome)) {
        if (outcome.reconciliation !== 'loaded') showNotice(ownershipRefreshFailureNotice(name));
        const added = sessionListingIds(outcome.applied?.items ?? []).includes(input.listingId);
        if (!added && outcome.applied) {
          showNotice('The server accepted the stop, but it is not in the returned itinerary, so refresh this session before continuing.');
        }
      }
    }
  }, [
    applyAuthoritativeSession,
    detail,
    missingVersionNotice,
    notifySuccess,
    commandContextHolds,
    runGuardedCommand,
    showNotice
  ]);

  /** Records the lessor-stated availability for one DRAFT item. */
  const saveAvailability = useCallback(async (
    itemId: number,
    choice: OperationsAvailabilityChoice,
    window: OperationsAvailabilityWindowInput
  ) => {
    const sessionId = detail.status === 'ready' ? detail.snapshot.session?.sessionId ?? null : null;
    if (sessionId === null) return;
    const name = operationsRecordTitle('SESSION', sessionId);
    const outcome = await runGuardedCommand<OperationsSessionDetail, OperationsSessionDetail>({
      kind: 'availability',
      busyKey: 'availability',
      name,
      loadFresh: (context) => operationsWorkspaceService.sessionDetail(sessionId, context),
      buildTransmit: (fresh) => {
        const validation = setAvailabilityCommand(fresh, itemId, choice, window);
        const command = validation.command;
        return command === null
          ? null
          : (context) => operationsWorkspaceService.setSessionItemAvailability(
              sessionId,
              command.itemId,
              {
                expectedSessionVersion: command.expectedSessionVersion,
                status: command.status,
                availabilityStartAt: command.availabilityStartAt,
                availabilityEndAt: command.availabilityEndAt,
                availabilityZoneId: command.availabilityZoneId,
                availabilitySource: command.availabilitySource
              },
              context
            );
      },
      applyResult: (_fresh, session, operation) => applyAuthoritativeSession(session, operation),
      onMissingVersion: (_fresh, reconciliation, operation) => missingVersionNotice(name, 'record that availability', reconciliation, operation)
    });
    if (outcome.status === 'applied') {
      notifySuccess(
        'Property availability recorded',
        choice === 'unavailable'
          ? 'The stop is recorded as unavailable for this visit.'
          : choice === 'confirm-only'
            ? 'The confirmation was saved. Scheduling stays blocked until a complete window is recorded.'
            : 'The complete window was saved for this stop.',
        undefined,
        'VISIT_SESSION'
      );
      if (outcome.reconciliation !== 'loaded' && commandContextHolds(outcome)) {
        showNotice(ownershipRefreshFailureNotice(name));
      }
    }
  }, [
    applyAuthoritativeSession,
    detail,
    missingVersionNotice,
    notifySuccess,
    commandContextHolds,
    runGuardedCommand,
    showNotice
  ]);

  /**
   * The bounded ranked recommendations for the scheduling drawer. This is an evaluation read, but it
   * still refuses to answer once the capturing session or component epoch has moved.
   */
  const loadRankedRecommendations = useCallback(async (
    signal: AbortSignal
  ): Promise<OperationsRecommendationView | null> => {
    const openSession = detail.status === 'ready' ? detail.snapshot.session : undefined;
    if (!openSession || openSession.version === null) return null;
    const session = captureOperationsSession();
    if (!session) return null;
    const epoch = ownership.sessionEpoch;
    const guarded = beginGuardedRead('ge-ranked', signal);
    const view = await operationsWorkspaceService.rankedRecommendations(
      openSession.sessionId,
      openSession.version,
      { session, signal: guarded }
    );
    if (guarded.aborted || !isOperationsSessionActive(session) || ownership.sessionEpoch !== epoch) return null;
    return view;
  }, [beginGuardedRead, detail, ownership]);

  /** Cursor-paged eligible-Ground-Executive discovery, by browse (`q` omitted) or by search. */
  const loadGeDiscovery = useCallback(async (
    input: { q: string | null; cursor: string | null },
    signal: AbortSignal
  ): Promise<OperationsRecommendationView | null> => {
    const openSession = detail.status === 'ready' ? detail.snapshot.session : undefined;
    if (!openSession || openSession.version === null) return null;
    const session = captureOperationsSession();
    if (!session) return null;
    const epoch = ownership.sessionEpoch;
    const guarded = beginGuardedRead('ge-discovery', signal);
    const view = await operationsWorkspaceService.discoverGroundExecutives(
      openSession.sessionId,
      openSession.version,
      { q: input.q, cursor: input.cursor },
      { session, signal: guarded }
    );
    if (guarded.aborted || !isOperationsSessionActive(session) || ownership.sessionEpoch !== epoch) return null;
    return view;
  }, [beginGuardedRead, detail, ownership]);

  /**
   * Books the exact selected Ground Executive and evaluated slot.
   *
   * The outcome is published as drawer state rather than inferred: a `200` applies the returned
   * Session, a refusal keeps the selection and asks for a refresh, and an unconfirmed transmission is
   * reported as unknown and never resent.
   */
  const confirmSelection = useCallback(async (
    selection: OperationsSchedulingSelection,
    view: OperationsRecommendationView | null,
    reason: string
  ) => {
    const openSession = detail.status === 'ready' ? detail.snapshot.session : undefined;
    if (!openSession) return;
    const sessionId = openSession.sessionId;
    const name = operationsRecordTitle('SESSION', sessionId);
    /**
     * The drawer instance this booking is started from, captured with the operation and before the
     * first `await`: the outcome of a confirmed booking belongs to this Session *in this drawer*, so a
     * drawer the OE closes — and opens again for the same Session — while the command is in flight
     * never inherits it.
     */
    const drawer: ScheduleDrawerIdentity = { ...scheduleDrawerRef.current };
    setScheduleOutcome(null);
    const outcome = await runGuardedCommand<OperationsSessionDetail, OperationsSchedulingApproval>({
      kind: 'schedule',
      busyKey: 'schedule',
      name,
      loadFresh: (context) => operationsWorkspaceService.sessionDetail(sessionId, context),
      buildTransmit: (fresh) => {
        const built = scheduleSelectionCommand(fresh, selection, view, reason);
        const command = built.command;
        return command === null
          ? null
          : (context) => operationsWorkspaceService.selectGroundExecutive(
              sessionId,
              {
                expectedSessionVersion: command.expectedSessionVersion,
                groundExecutiveUserId: command.groundExecutiveUserId,
                scheduledAt: command.scheduledAt,
                zoneId: command.zoneId,
                overrideReason: command.overrideReason
              },
              context
            );
      },
      applyResult: (_fresh, approval, operation) => {
        // A confirmed booking always carries the authoritative Session it committed.
        const applied = applyAuthoritativeSession(approval.session, operation);
        // The drawer outcome is bound to the initiating drawer instance as well, so it is published
        // only while that exact drawer is the one on screen.
        if (applied && scheduleOutcomeContextHolds(operation, drawer)) {
          setScheduleOutcome({ kind: 'scheduled', approval, refreshFailed: false });
        }
        return applied;
      },
      onMissingVersion: (fresh, _reconciliation, operation) => {
        /**
         * The outcome describes the operation's own session, so it may only be published while that
         * session is still the display context the command was started in: the OE can leave the record
         * — and open another drawer — while the pre-command read is in flight, and a stopped command
         * must not write its outcome into the drawer that replaced its own.
         */
        if (!noticeContextHolds(operation)) return;
        // …and it must still be the drawer instance the command was started from.
        if (!scheduleOutcomeContextHolds(operation, drawer)) return;
        /**
         * The command is only withheld here. Distinguish the two reasons truthfully: a Session that
         * moved past the reviewed evaluation (the OE must inspect the current itinerary, reload the
         * candidates and review again) is not the same as a read that carried no version at all.
         */
        const reviewed = selection.evaluated;
        if (fresh.sessionId !== reviewed.sessionId || fresh.version !== reviewed.sessionVersion) {
          setScheduleOutcome({
            kind: 'review-required',
            message: `These candidates were evaluated at version ${reviewed.sessionVersion ?? 'unknown'} of this session, but the server now reports version ${fresh.version ?? 'unknown'}, so nothing was booked. Reload the session to see the current itinerary, then evaluate and review again.`
          });
          return;
        }
        setScheduleOutcome({
          kind: 'not-sent',
          message: 'This session did not return the version needed to book it, so nothing was sent.'
        });
      },
      onFailure: async (cause, operation) => {
        const failure = await reportCommandFailure(cause, operation, name);
        /**
         * Re-check the whole synchronous commit guard after the awaited failure reporting: the
         * operation may have been abandoned (account switch, lost authority, a newer command) while
         * it reconciled, and an abandoned operation must never write an outcome, close a drawer or
         * raise a notice in the context that replaced it.
         */
        if (!ownership.canCommit(operation, authorityRef.current)) return;
        /**
         * The drawer outcome describes the session this operation targeted. A withheld refusal — one
         * whose record was replaced by another record or by a newer mount of this one — must not be
         * published into a drawer that no longer belongs to it either.
         */
        if (!failure.published) return;
        /**
         * The outcome is bound to the display context the command was started in, re-checked here
         * after the awaited failure reporting: a conflict or an unconfirmed transmission is still
         * announced in the workspace, but it must not become this drawer's outcome once the OE has
         * left the record and that drawer now belongs to another session.
         */
        if (!noticeContextHolds(operation)) return;
        /**
         * …and the drawer it would be written into must still be the exact instance this booking was
         * started from. A drawer the OE closed and re-opened for the same Session is a different
         * instance: the outcome of the earlier one describes a drawer that is gone.
         */
        if (!scheduleOutcomeContextHolds(operation, drawer)) return;
        /**
         * Only now is the outcome published, and from the classified failure alone. A `403`/`404` has
         * already withdrawn the protected root inside `reportCommandFailure`, so no stale drawer or
         * detail write happens here either.
         */
        setScheduleOutcome(schedulingFailureOutcome(failure));
      }
    });
    if (outcome.status === 'applied' && outcome.applied) {
      /**
       * The booking is confirmed; only the follow-up queue refresh is still in question. The verdict is
       * re-taken here — after the awaited reconciliation and in the same tick as this write — so the
       * drawer outcome is published only while the drawer the booking was started from is still the
       * one on screen. The confirmation below is a fact about the booking and is always reported.
       */
      if (scheduleOutcomeContextHolds(outcome.operation, drawer)) {
        setScheduleOutcome({
          kind: 'scheduled',
          approval: outcome.applied,
          refreshFailed: outcome.reconciliation !== 'loaded'
        });
      }
      const scheduled = outcome.applied.session;
      notifySuccess(
        `${name} scheduled`,
        scheduled?.representativeUserId === null || !scheduled
          ? 'The server accepted the booking.'
          : `Ground Executive user ${scheduled.representativeUserId} is assigned and the tenant confirmation step is recorded separately.`,
        undefined,
        'VISIT_SESSION'
      );
    }
  }, [
    applyAuthoritativeSession,
    detail,
    noticeContextHolds,
    notifySuccess,
    ownership,
    reportCommandFailure,
    runGuardedCommand,
    scheduleOutcomeContextHolds
  ]);

  const navigate = useCallback((next: QueueView) => {
    setView(next);
    returnToRow.current = null;
    window.scrollTo({ top: 0 });
    focusWorkspaceTitle();
  }, []);

  /**
   * Identity of the visible root, its version and the signed-in account. Revealed lessor contact
   * lives only as long as this value: a new version, another record or another account clears it
   * before any previously revealed value could be read.
   */
  const contactContextKey = ((): string => {
    if (detailTarget === null || detail.status !== 'ready') return 'no-open-record';
    const identity = readOperationsSession()?.identity ?? 'no-session';
    const snapshot = detail.snapshot;
    const version = snapshot.request?.version
      ?? snapshot.session?.version
      ?? snapshot.claimable?.expectedVersion
      ?? 'unrecorded';
    return `${identity}|${detailTarget.source}|${detailTarget.targetType}-${detailTarget.targetId}|v${version}`;
  })();

  /**
   * Every action the detail and drawer may invoke. Each entry is one guarded workspace command, so no
   * component can transmit anything on its own or decide what an outcome means.
   */
  const detailActions: OperationsDetailActions = {
    onRelease: () => void releaseRoot(),
    onCoordinateNewSession: () => void coordinateNewSession(),
    onAttachToOwnedDraft: (sessionId, sessionVersion) => void attachToOwnedDraft(sessionId, sessionVersion),
    onMarkConfirmedUnavailable: () => void markConfirmedUnavailable(),
    onOpenScheduling: () => {
      if (detail.status !== 'ready' || !detail.snapshot.session) return;
      openScheduleDrawer(detail.snapshot.session.sessionId);
    },
    onReorderItems: (itemIds) => void reorderItems(itemIds),
    onRemoveItem: (itemId, reason) => void removeItem(itemId, reason),
    onAddStop: (input) => void addStop(input),
    revealRequestContact,
    revealItemContact,
    searchListings,
    loadOwnedCandidates
  };

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
            <>
              <OperationsWorkDetail
                targetType={detailTarget.targetType}
                targetId={detailTarget.targetId}
                source={detailTarget.source}
                state={detail}
                busyAction={busyAction}
                busyCommand={busyCommand}
                contactContextKey={contactContextKey}
                actions={detailActions}
                onSaveAvailability={(itemId, choice, window) => void saveAvailability(itemId, choice, window)}
                onBack={closeDetail}
                onRetry={() => void openDetail(detailTarget)}
                onClaim={() => {
                  if (detail.status === 'ready' && detail.snapshot.claimable) void claimRoot(detail.snapshot.claimable);
                }}
              />
              {scheduleSessionId !== null
                && detail.status === 'ready'
                && detail.snapshot.session?.sessionId === scheduleSessionId ? (
                  <OperationsScheduleFlow
                    session={detail.snapshot.session}
                    blocked={busyCommand === 'schedule'}
                    outcome={scheduleOutcome}
                    onClose={closeScheduleDrawer}
                    onAcknowledgeOutcome={() => setScheduleOutcome(null)}
                    onConfirm={(selection, view, reason) => void confirmSelection(selection, view, reason)}
                    loadRanked={loadRankedRecommendations}
                    loadDiscovery={loadGeDiscovery}
                    onRefreshSession={() => {
                      if (detailTarget) void openDetail(detailTarget);
                    }}
                  />
                ) : null}
            </>
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
