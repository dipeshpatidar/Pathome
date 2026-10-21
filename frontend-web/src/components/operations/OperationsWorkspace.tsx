import React, { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { ArrowRight, CircleAlert, Flag, Home, Inbox, ListChecks, Lock, MapPin, RefreshCw, Filter } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { ApiRequestError } from '../../services/apiError';
import { readSchedulingIntent, preserveSchedulingIntent, forgetSchedulingIntent } from '../../utils/operationsSchedulingIntent';
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
  availabilityChoicesFor,
  availabilityEvidenceChanged,
  availabilityEvidenceSnapshot,
  availabilityItemSatisfiesDraft,
  availabilityIntentEqualsAttempt,
  claimCommandFor,
  commandFailureDisposition,
  coordinateAttachCommand,
  coordinateNewSessionCommand,
  createAvailabilityDraft,
  formatOperationsDateTime,
  freshSchedulingSessionIsEligible,
  linkedRequestsLabel,
  operationsAuthorityScopeFingerprint,
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
  sameOperationsRootInstance,
  scheduleSelectionCommand,
  schedulingFailureOutcome,
  sessionListingIds,
  setAvailabilityCommand,
  type ClaimableWorkItem,
  type AvailabilityEvidenceSnapshot,
  type AvailabilityWindowInput,
  type OperationsLoadFailure,
  type OperationsLessorContact,
  type OperationsAvailabilityAttempt,
  type OperationsAvailabilityDraft,
  type OperationsAvailabilityDraftValues,
  type OperationsAvailabilitySaveOutcome,
  type OperationsMyWorkItem,
  type OperationsRecommendationView,
  type OperationsGeAvailabilityView,
  type OperationsRequestDetail,
  type OperationsScope,
  type OperationsSchedulingApproval,
  type OperationsSchedulingSelection,
  type OperationsSessionDetail,
  type OperationsTargetType,
  type OperationsTenantContact,
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
import { ClaimableList, OperationsFilterBar, OwnedList, workItemKey } from './OperationsWorkLists';
import {
  OperationsWorkDetail,
  type OperationsDetailActions,
  type OperationsDetailSnapshot,
  type OperationsDetailState
} from './OperationsWorkDetail';
import type {
  AddStopInput,
  DiscoveryListingOption,
  OperationsAvailabilityEditing
} from './OperationsDraftItinerary';
import type { OperationsOwnedCandidatePage } from './OperationsRequestCoordination';
import { OperationsScheduleFlow, type ScheduleOutcome } from './OperationsScheduleFlow';
import {
  EMPTY_OPERATIONS_FILTERS,
  detailRoute,
  hasOperationsFilters,
  normalizeOperationsFilters,
  operationsRoutesEqual,
  queueRoute,
  readBrowserOperationsRoute,
  returnRouteForDetail,
  serializeOperationsRoute,
  type OperationsQueueFilters,
  type OperationsRoute
} from '../../utils/operationsWorkspaceRoute';
import './operationsWorkspace.css';

type QueueKey = 'attention' | 'my-work' | 'claimable';
type QueueView = 'home' | QueueKey;
type QueueStatus = 'loading' | 'ready' | OperationsLoadFailure;

/**
 * Independent guarded read families. Each is superseded only by a newer request of the same family,
 * so an unrelated read can never cancel the one the user is waiting on.
 */
type GuardedReadFamily = 'owned-candidates' | 'listing-search' | 'ge-ranked' | 'ge-discovery' | 'ge-availability';

interface PageState<T> {
  status: QueueStatus;
  items: T[];
  totalCount: number;
  unfilteredTotalCount: number;
  page: number;
  totalPages: number;
}

const EMPTY_ITEMS: never[] = [];

const initialPageState = <T,>(): PageState<T> => ({
  status: 'loading',
  items: EMPTY_ITEMS,
  totalCount: 0,
  unfilteredTotalCount: 0,
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
    description: 'Visit requests and visit plans you own in your teams. Totals and pages come from the server.',
    emptyTitle: 'You don’t own any work in your current teams.',
    emptyBody: 'Work you claim appears here. Available work shows unclaimed records in your authorized teams.'
  },
  claimable: {
    title: 'Available work',
    description: 'Unclaimed visit requests and visit plans in your authorized teams. Work owned by another coordinator is not shown here as available.',
    emptyTitle: 'No unclaimed work was returned',
    emptyBody: 'The server returned no unclaimed visit requests or visit plans within your current authorized scope. New intake appears only after an authorized Team routes it.'
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
  filters: OperationsQueueFilters;
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
  /** Receives the page the server actually settled on, so the URL can be canonicalised. */
  onSettledPage?: (page: number) => void;
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
  const {
    key, page, session, epoch, fetch: fetchPage, apply, controllers, isMounted, isEpochCurrent,
    onSettledPage, attempt = 0
  } = load;
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
      unfilteredTotalCount: result.unfilteredTotalCount,
      page: result.page,
      totalPages: result.totalPages
    }));
    onSettledPage?.(result.page);
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
  status: 'applied' | 'abandoned' | 'missing-version' | 'preflight-refused' | 'failed';
  applied: Applied | null;
  reconciliation: QueueLoadOutcome;
  failure: GuardedCommandFailure | null;
  preflight: GuardedCommandPreflightRefusal | null;
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
 * A bounded, availability-specific stop after the guarded preflight read. `conflict` keeps the draft
 * for review; `inapplicable` means the current lifecycle or item no longer permits this editor.
 */
interface GuardedCommandPreflightRefusal {
  disposition: 'conflict' | 'inapplicable';
  message: string;
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
  /**
   * Availability's preflight needs to compare the fresh root with the exact snapshot the OE saw
   * before a command may be built. Returning a refusal stops this submission without transmitting.
   */
  preflight?: (fresh: Fresh) => GuardedCommandPreflightRefusal | null;
  /**
   * A caller-owned, synchronous verdict on whether the editor that started this command is still the
   * current one. It is checked after every pre-dispatch await and again in the same tick as transmit.
   * A false verdict is a supersession, not a missing-version or lifecycle refusal: nothing is written
   * and no command leaves the client.
   */
  contextCurrent?: () => boolean;
  onPreflightRefusal?: (
    refusal: GuardedCommandPreflightRefusal,
    fresh: Fresh,
    operation: OwnershipOperation
  ) => void;
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
  /**
   * `page` is the dedicated Operations destination and receives the responsive sidebar/tab shell.
   * `embedded` keeps the compact presentation used inside the Employee and Admin consoles.
   */
  layout?: 'page' | 'embedded';
}

export const OperationsWorkspace: React.FC<Props> = ({ authority, layout = 'page' }) => {
  const { notifySuccess } = useNotification();
  const routerNavigate = useNavigate();
  const isPageLayout = layout === 'page';
  const capabilities: StaffCapabilities | null = authority.capabilities;
  /**
   * The read-admission boundary. Granted authority is not enough on its own: the workspace may only
   * mount protected content and start protected reads once a verification has actually succeeded for
   * the entry that is on screen *and* the exact signed-in credential. `authority.entryAdmitted` is
   * evaluated during render, so the first render after a surface transition already withholds
   * everything - no effect has to run, and no protected request can be issued, in the meantime.
   */
  const accessGranted = authority.state === 'granted' && authority.entryAdmitted;
  /**
   * Effective City/Team authority is part of admission, not just a label. The workspace keeps the
   * scope its protected state was built against and withholds every queue/detail/contact snapshot in
   * the same render that sees a different fingerprint. The layout effect below clears the stale state
   * and refetches from the server under the new scope.
   */
  const effectiveScopeFingerprint = operationsAuthorityScopeFingerprint(capabilities);
  const [admittedScopeFingerprint, setAdmittedScopeFingerprint] = useState<string | null>(null);
  const scopeCurrent = admittedScopeFingerprint === effectiveScopeFingerprint;
  const protectedAccess = accessGranted && scopeCurrent;
  const authorityRef = useRef(authority);
  authorityRef.current = authority;

  const initialRoute = useRef<OperationsRoute | null>(null);
  if (initialRoute.current === null) initialRoute.current = readBrowserOperationsRoute();
  const firstRoute = initialRoute.current;
  const [view, setView] = useState<QueueView>(
    firstRoute.kind === 'detail' ? firstRoute.from : firstRoute.kind === 'queue' ? firstRoute.queue : 'home'
  );
  const [filters, setFilters] = useState<OperationsQueueFilters>(
    firstRoute.kind === 'home' ? EMPTY_OPERATIONS_FILTERS : firstRoute.filters
  );
  const [attention, setAttention] = useState<PageState<OperationsMyWorkItem>>(
    initialPageState<OperationsMyWorkItem>
  );
  const [myWork, setMyWork] = useState<PageState<OperationsMyWorkItem>>(
    initialPageState<OperationsMyWorkItem>
  );
  const [claimable, setClaimable] = useState<PageState<ClaimableWorkItem>>(
    initialPageState<ClaimableWorkItem>
  );
  const [detailTarget, setDetailTarget] = useState<DetailTarget | null>(
    firstRoute.kind === 'detail'
      ? {
          targetType: firstRoute.targetType,
          targetId: firstRoute.targetId,
          source: firstRoute.from === 'claimable' ? 'claimable' : 'owned'
        }
      : null
  );
  const [detail, setDetail] = useState<OperationsDetailState>({ status: 'loading' });
  const [busyAction, setBusyAction] = useState<'claim' | 'release' | null>(null);
  const [busyClaimKey, setBusyClaimKey] = useState<string | null>(null);
  const [notice, setNotice] = useState<NoticeState | null>(null);
  /** The one in-flight Phase 2A command, so only the control that started it shows progress. */
  const [busyCommand, setBusyCommand] = useState<string | null>(null);
  /**
   * The one bounded availability draft, owned here rather than by the itinerary.
   *
   * A detail refresh replaces `session.items`; if the editor owned the draft, that refresh would
   * unmount it and silently revert an unsuccessful save. Keeping the draft at the workspace level
   * also lets the identity/authority invalidation paths clear it in the same tick they drop the
   * protected detail.
   */
  const [availabilityDraft, setAvailabilityDraft] = useState<OperationsAvailabilityDraft | null>(null);
  const routeRef = useRef<OperationsRoute>(firstRoute);
  const filtersRef = useRef<OperationsQueueFilters>(filters);
  filtersRef.current = filters;
  const viewRef = useRef<QueueView>(view);
  viewRef.current = view;

  /**
   * One URL writer for every Operations navigation.
   *
   * The standalone destination goes through React Router so its location, history metadata and
   * Back/Forward handling stay in one model. The embedded console never writes the host URL or
   * host history: its navigation belongs to the Employee/Admin workspace that owns the tab.
   * Only a genuine pushed detail entry is marked as an in-app origin, which is what makes browser
   * Back safe while a direct-loaded detail can still restore its encoded queue route.
   */
  const commitRoute = useCallback((next: OperationsRoute, mode: 'push' | 'replace' = 'push') => {
    routeRef.current = next;
    if (!isPageLayout || typeof window === 'undefined') return;
    const url = serializeOperationsRoute(next);
    const current = `${window.location.pathname}${window.location.search}`;
    if (url === current) return;
    routerNavigate(url, {
      replace: mode === 'replace',
      state: {
        pathomeOperations: true,
        pathomeOperationsDetail: next.kind === 'detail',
        pathomeOperationsPushed: next.kind === 'detail' && mode === 'push'
      }
    });
  }, [isPageLayout, routerNavigate]);

  /**
   * When the server settles a page outside the requested range, replace the current history entry
   * with the last valid page instead of adding a second entry for a page that never existed.
   */
  const canonicalizePage = useCallback((key: QueueKey, page: number) => {
    const route = routeRef.current;
    const publicPage = page + 1;
    if (route.kind === 'queue' && route.queue === key && route.page !== publicPage) {
      commitRoute({ ...route, page: publicPage }, 'replace');
    }
  }, [commitRoute]);
  const availabilityDraftRef = useRef<OperationsAvailabilityDraft | null>(null);
  const availabilityEditorGeneration = useRef(0);
  /**
   * One read-only settlement in flight per editor generation. The map is keyed by the captured
   * identity/epoch/Session/item/editor generation, so a repeated Save joins the existing read while a
   * newer editor gets its own entry. Cleanup below compares the exact promise before removing it:
   * an older settlement can never unlock or erase a newer editor's flight.
   */
  const availabilitySettlementFlights = useRef(
    new Map<string, Promise<OperationsAvailabilitySaveOutcome>>()
  );
  /** The session whose scheduling drawer is open, or null. */
  const [scheduleSessionId, setScheduleSessionId] = useState<number | null>(null);
  const [schedulingOpening, setSchedulingOpening] = useState(false);
  const [schedulingOpenError, setSchedulingOpenError] = useState<string | null>(null);
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
  /** Latest exact-root withdrawal primitive, callable from callbacks declared above it. */
  const withdrawDeniedRootRef = useRef<((source: OwnershipOperation | MountedRootIdentity) => boolean) | null>(null);
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
  const schedulingOpenRef = useRef<{ generation: number; controller: AbortController | null }>({
    generation: 0,
    controller: null
  });
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

  /** Writes the bounded draft through one ref, so an awaited completion never reads stale state. */
  const writeAvailabilityDraft = useCallback((next: OperationsAvailabilityDraft | null) => {
    availabilityDraftRef.current = next;
    setAvailabilityDraft(next);
  }, []);

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
    const session = captureOperationsSession();
    let pending = false;
    try { pending = Boolean(session && readSchedulingIntent(session, sessionId)); } catch { pending = true; }
    setScheduleOutcome(pending ? { kind: 'uncertain', message: 'A previous booking is still unconfirmed. Check its authoritative outcome before scheduling again.' } : null);
    setScheduleSessionId(sessionId);
  }, []);

  const closeScheduleDrawer = useCallback(() => {
    schedulingOpenRef.current.controller?.abort();
    schedulingOpenRef.current = {
      generation: schedulingOpenRef.current.generation + 1,
      controller: null
    };
    setSchedulingOpening(false);
    setSchedulingOpenError(null);
    scheduleDrawerRef.current = {
      sessionId: null,
      generation: scheduleDrawerRef.current.generation + 1
    };
    setScheduleOutcome(null);
    setScheduleSessionId(null);
  }, []);

  /**
   * Opens a new scheduling drawer only after a fresh, context-bound Session read.
   *
   * The read is a UX safety measure, not a replacement for selection-time server checks. It never
   * touches a pending B09/V50 command receipt: the drawer still restores and reconciles that command
   * through the existing scheduling flow. A late answer from a previous account, authority, root or
   * drawer generation is dropped.
   */
  const openSchedulingDrawerFresh = useCallback(async (sessionId: number) => {
    if (schedulingOpenRef.current.controller !== null
      || scheduleDrawerRef.current.sessionId !== null) return;
    const openSession = detail.status === 'ready' ? detail.snapshot.session : undefined;
    if (!openSession || openSession.sessionId !== sessionId || openSession.status !== 'DRAFT') return;
    const captured = captureOperationsSession();
    if (!captured) {
      setSchedulingOpenError('Please sign in again before scheduling this visit.');
      return;
    }
    // Bind every continuation to the exact mounted root instance, captured as an immutable value.
    // A remount of the same Session is a different instance and can never inherit this answer.
    const mountedRoot = openRootRef.current;
    if (mountedRoot === null) return;
    const root: MountedRootIdentity = Object.freeze({ ...mountedRoot });
    const authorityGeneration = authorityRef.current.authorizationGeneration();
    const epoch = ownership.sessionEpoch;
    const generation = schedulingOpenRef.current.generation + 1;
    const controller = new AbortController();
    schedulingOpenRef.current = { generation, controller };
    setSchedulingOpening(true);
    setSchedulingOpenError(null);
    const stillCurrent = (): boolean =>
      schedulingOpenRef.current.generation === generation
      && schedulingOpenRef.current.controller === controller
      && !controller.signal.aborted
      && mountedRef.current
      && isOperationsSessionActive(captured)
      && ownership.sessionEpoch === epoch
      && authorityRef.current.authorizationGeneration() === authorityGeneration
      && openRootRef.current !== null
      && openRootRef.current.targetType === root.targetType
      && openRootRef.current.targetId === root.targetId
      && openRootRef.current.mountGeneration === root.mountGeneration;
    try {
      const verified = await authorityRef.current.verifySession(captured, { signal: controller.signal });
      if (!verified || !stillCurrent()) return;
      const fresh = await operationsWorkspaceService.sessionDetail(sessionId, {
        session: captured,
        signal: controller.signal
      });
      if (!stillCurrent()) return;
      if (!fresh
        || fresh.sessionId !== sessionId
        || !Number.isSafeInteger(fresh.sessionId)
        || fresh.sessionId <= 0) {
        // A wrong-ID or malformed body is never installed as the current detail.
        setSchedulingOpenError('This visit could not be verified. Check your connection and try again.');
        return;
      }
      if (fresh.coordinatorUserId !== captured.userId) {
        // The protected root now belongs to another coordinator. Retire exactly the mounted
        // instance; this is not a global loss of the current OE's authority.
        withdrawDeniedRootRef.current?.(root);
        setSchedulingOpenError('This visit is no longer assigned to you. It has been closed.');
        return;
      }
      if (!freshSchedulingSessionIsEligible(fresh, sessionId, captured.userId)) {
        // Same owned root, but no longer DRAFT: publish the truthful current snapshot and keep a
        // NEW scheduling drawer closed.
        setDetail({ status: 'ready', snapshot: { kind: 'session', session: fresh } });
        setSchedulingOpenError('This visit has already moved past scheduling. The current session is shown, but a new scheduling decision cannot be opened.');
        return;
      }
      setDetail({ status: 'ready', snapshot: { kind: 'session', session: fresh } });
      openScheduleDrawer(sessionId);
    } catch (cause) {
      if (!stillCurrent() || isOperationsSessionChangedError(cause)) return;
      const status = cause instanceof ApiRequestError ? cause.status : undefined;
      if (status === 403 || status === 404) {
        // A fresh, definitive denial for this exact root removes the previously mounted protected
        // detail and any revealed contact in the same tick. Nothing here awaits a queue refresh.
        withdrawDeniedRootRef.current?.(root);
        setSchedulingOpenError('This visit is no longer available to schedule here. It has been closed.');
        return;
      }
      setSchedulingOpenError(status === 401 || status === 403 || status === 404
        ? 'This visit is no longer available to schedule here.'
        : 'The latest visit details could not be loaded, so scheduling was not opened. Check your connection and try again.');
    } finally {
      if (stillCurrent()) {
        schedulingOpenRef.current = { generation, controller: null };
        setSchedulingOpening(false);
      } else if (schedulingOpenRef.current.controller === controller) {
        schedulingOpenRef.current = { generation, controller: null };
        setSchedulingOpening(false);
      }
    }
  }, [authorityRef, detail, openScheduleDrawer, ownership]);

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
    // A protected availability draft is dropped with every other protected surface before its
    // identity/authority can change, so no later render or completion can restore it.
    writeAvailabilityDraft(null);
    setBusyAction(null);
    setBusyClaimKey(null);
    setBusyCommand(null);
    // The drawer is being dropped with everything else, so any outcome an in-flight booking captured
    // for it can no longer be published anywhere: closing it also retires its instance.
    closeScheduleDrawer();
    setNotice(null);
    if (options.neutralView === true) {
      setView('home');
      setFilters(EMPTY_OPERATIONS_FILTERS);
      filtersRef.current = EMPTY_OPERATIONS_FILTERS;
      commitRoute({ kind: 'home' }, 'replace');
      if (typeof window !== 'undefined') window.scrollTo({ top: 0 });
    }
  }, [abortGuardedReads, closeScheduleDrawer, commitRoute, ownership, setDisplayContext, writeAvailabilityDraft]);
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
  // returns the workspace to its neutral entry view for whichever identity comes next. A session
  // expiry is different: the shell captures and owns the sign-in return route, so the workspace must
  // not rewrite the currently displayed detail URL before that capture happens.
  useEffect(
    () => subscribeOperationsSession((reason) => clearProtectedState(
      'session-changed',
      { neutralView: reason !== 'session-expired' }
    )),
    [clearProtectedState]
  );

  // Loss of authority hides protected data, deactivates the workspace surface and blocks mutations.
  useEffect(() => {
    ownership.setWorkspaceActive(accessGranted);
    if (!accessGranted) clearProtectedState('authorization-lost');
  }, [accessGranted, clearProtectedState, ownership]);

  /**
   * A draft is only applicable while its exact session is open, still a DRAFT, and still carries the
   * active editable item. Losing any of those — an authoritative removal, a lifecycle transition to
   * SCHEDULED/STARTED, or a non-editable availability state — invalidates the draft in memory.
   */
  useEffect(() => {
    const draft = availabilityDraftRef.current;
    if (!draft) return;
    if (!accessGranted) {
      writeAvailabilityDraft(null);
      return;
    }
    if (detail.status !== 'ready') return;
    const openSession = detail.snapshot.session;
    if (!openSession
      || openSession.sessionId !== draft.sessionId
      || openSession.status !== 'DRAFT') {
      writeAvailabilityDraft(null);
      return;
    }
    const item = openSession.items.find(
      (candidate) => candidate.itemId === draft.itemId && candidate.removedAt === null
    );
    if (!item || item.confirmationStatus === 'UNAVAILABLE') writeAvailabilityDraft(null);
  }, [accessGranted, detail, writeAvailabilityDraft]);

  const isMounted = useCallback(() => mountedRef.current, []);
  const isEpochCurrent = useCallback((epoch: number) => ownership.sessionEpoch === epoch, [ownership]);

  const loadAttention = useCallback(
    (page: number, session?: OperationsSession, epoch?: number,
      appliedFilters: OperationsQueueFilters = filtersRef.current): Promise<QueueLoadOutcome> => {
      const captured = session ?? captureOperationsSession();
      if (!captured) return Promise.resolve<QueueLoadOutcome>('skipped');
      return loadQueuePage<OperationsMyWorkItem>({
        key: 'attention',
        page,
        filters: appliedFilters,
        session: captured,
        epoch: epoch ?? ownership.sessionEpoch,
        fetch: (nextPage, context) => operationsWorkspaceService.myWork(nextPage, true, context, appliedFilters),
        apply: setAttention,
        controllers: queueControllers.current,
        isMounted,
        isEpochCurrent,
        onSettledPage: (settled) => canonicalizePage('attention', settled)
      });
    },
    [isEpochCurrent, isMounted, ownership]
  );

  const loadMyWork = useCallback(
    (page: number, session?: OperationsSession, epoch?: number,
      appliedFilters: OperationsQueueFilters = filtersRef.current): Promise<QueueLoadOutcome> => {
      const captured = session ?? captureOperationsSession();
      if (!captured) return Promise.resolve<QueueLoadOutcome>('skipped');
      return loadQueuePage<OperationsMyWorkItem>({
        key: 'my-work',
        page,
        filters: appliedFilters,
        session: captured,
        epoch: epoch ?? ownership.sessionEpoch,
        fetch: (nextPage, context) => operationsWorkspaceService.myWork(nextPage, false, context, appliedFilters),
        apply: setMyWork,
        controllers: queueControllers.current,
        isMounted,
        isEpochCurrent,
        onSettledPage: (settled) => canonicalizePage('my-work', settled)
      });
    },
    [isEpochCurrent, isMounted, ownership]
  );

  const loadClaimable = useCallback(
    (page: number, session?: OperationsSession, epoch?: number,
      appliedFilters: OperationsQueueFilters = filtersRef.current): Promise<QueueLoadOutcome> => {
      const captured = session ?? captureOperationsSession();
      if (!captured) return Promise.resolve<QueueLoadOutcome>('skipped');
      return loadQueuePage<ClaimableWorkItem>({
        key: 'claimable',
        page,
        filters: appliedFilters,
        session: captured,
        epoch: epoch ?? ownership.sessionEpoch,
        fetch: (nextPage, context) => operationsWorkspaceService.claimableWork(nextPage, context, appliedFilters),
        apply: setClaimable,
        controllers: queueControllers.current,
        isMounted,
        isEpochCurrent,
        onSettledPage: (settled) => canonicalizePage('claimable', settled)
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
    const restore = (attempt: number) => {
      if (epoch !== ownership.sessionEpoch) return;
      const row = document.querySelector<HTMLElement>(`[data-row-key="${target.rowKey}"]`);
      if (row) {
        window.scrollTo({ top });
        row.focus({ preventScroll: true });
        return;
      }
      // A queue reload renders its skeleton before the authoritative rows. Keep the restore pending
      // for a bounded window so Back never silently loses the operator's place.
      if (attempt < 90) window.requestAnimationFrame(() => restore(attempt + 1));
    };
    window.requestAnimationFrame(() => restore(0));
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
    operation?: OwnershipOperation,
    routeContext?: {
      from: QueueView;
      page: number;
      filters: OperationsQueueFilters;
      writeHistory?: boolean;
    }
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
    const from = routeContext?.from ?? view;
    const page = routeContext?.page
      ?? ((view === 'attention' ? attention.page : view === 'my-work' ? myWork.page : view === 'claimable' ? claimable.page : 0) + 1);
    const appliedFilters = normalizeOperationsFilters(routeContext?.filters ?? filtersRef.current);
    if (routeContext?.writeHistory !== false) {
      commitRoute(detailRoute(target.targetType, target.targetId, from === 'home' ? 'home' : from, page, appliedFilters));
    }
    // Opening a different record is a context change: the one bounded draft belongs to the record it
    // was started from and may never be shown under another one.
    const openDraft = availabilityDraftRef.current;
    if (openDraft
      && (target.targetType !== 'SESSION' || target.targetId !== openDraft.sessionId)) {
      writeAvailabilityDraft(null);
    }
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
      if (snapshot.session) {
        let pending = false;
        try { pending = Boolean(readSchedulingIntent(captured, snapshot.session.sessionId)); } catch { pending = true; }
        if (pending) openScheduleDrawer(snapshot.session.sessionId);
      }
    } catch (cause) {
      if (detailController.current !== controller) return;
      if (controller.signal.aborted || !mountedRef.current || !contextHolds()) return;
      if (isOperationsSessionChangedError(cause)) return;
      // A resource-specific 403/404 clears that resource only; it is never global revocation.
      setDetail({ status: operationsDetailFailureState(cause instanceof ApiRequestError ? cause.status : undefined) });
    }
  }, [attention.page, claimable.page, commitRoute, myWork.page, view, ownership, setDisplayContext, openScheduleDrawer, writeAvailabilityDraft]);

  const closeDetail = useCallback(() => {
    detailController.current?.abort();
    abortGuardedReads();
    closeScheduleDrawer();
    writeAvailabilityDraft(null);
    setDisplayContext(null);
  }, [abortGuardedReads, closeScheduleDrawer, setDisplayContext, writeAvailabilityDraft]);

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
   * Synchronously retires the exact protected root a definitive denial addressed.
   *
   * This is the shared clearing primitive for both mutation failures and read-side settlement. It
   * needs only the exact captured type, id and mount generation, never another command's mutation
   * lock: a newer record or remount is untouched while unrelated ownership remains intact.
   *
   * Mutation callers also record the display change as their own, so their later refusal notice still
   * describes the record they removed. Settlement supplies only the mounted-root identity it captured
   * before its read.
   *
   * This is deliberately *not* global account revocation: it touches only the matching open root's
   * protected state, leaving queues and the authenticated session in place for the next action.
   */
  const withdrawDeniedRoot = useCallback((
    source: OwnershipOperation | MountedRootIdentity
  ): boolean => {
    let operation: OwnershipOperation | null = null;
    let target: MountedRootIdentity | null;
    if ('target' in source) {
      operation = source;
      target = source.target;
    } else {
      target = source;
    }
    const mounted = openRootRef.current;
    if (target === null
      || mounted === null
      || mounted.targetType !== target.targetType
      || mounted.targetId !== target.targetId
      || mounted.mountGeneration !== target.mountGeneration) return false;
    detailController.current?.abort();
    abortGuardedReads();
    writeAvailabilityDraft(null);
    setDisplayContext(null);
    if (operation) ownership.markWithdrewOwnDisplayContext(operation);
    // The drawer leaves the screen with the refused root, and its instance is retired: no outcome an
    // in-flight command captured for it may be published into whatever the OE opens next.
    closeScheduleDrawer();
    return true;
  }, [abortGuardedReads, closeScheduleDrawer, ownership, setDisplayContext, writeAvailabilityDraft]);
  withdrawDeniedRootRef.current = withdrawDeniedRoot;

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
    options: {
      dropDetailWhenRejected?: boolean;
      contextCurrent?: () => boolean;
    } = {}
  ): Promise<GuardedCommandFailure> => {
    const failureContextCurrent = (): boolean =>
      ownership.canCommit(operation, authorityRef.current)
      && options.contextCurrent?.() !== false;
    const apiError = cause instanceof ApiRequestError ? cause : null;
    const input: OwnershipOutcomeInput = {
      dispatched: operation.dispatched,
      status: apiError?.status ?? null,
      message: apiError?.message ?? null
    };
    const disposition = commandFailureDisposition(input, options);
    let withdrewRoot = false;
    /**
     * The root this command acted on is the one its operation captured when it began — never whatever
     * happens to be on screen when the failure arrives. `withdrawDeniedRoot` re-checks that exact
     * mounted instance in the same tick, so a record the OE opened later, or the same record re-opened
     * after navigating away, is never dropped by an older command's denial. The withdrawal is gated on
     * the operation still being allowed to commit, exactly like every other write it makes.
     */
    if (disposition.withdrawRoot && failureContextCurrent()) {
      withdrewRoot = withdrawDeniedRoot(operation);
    }
    /**
     * A successful denial withdrawal necessarily clears the editor predicate's draft. Its own exact
     * display generation is the equivalent publication scope from that point on: the refusal may be
     * reported while that withdrawal is still in force, but a remount, navigation or account change
     * invalidates it. Every non-withdrawal failure continues to use the caller's full editor predicate
     * after its reconciliation.
     */
    const publicationContextCurrent = (): boolean =>
      ownership.canCommit(operation, authorityRef.current)
      && (withdrewRoot
        ? noticeContextHolds(operation)
        : options.contextCurrent?.() !== false);
    let reconciliation: OwnershipReconciliation = 'not-attempted';
    if (disposition.reconcile && publicationContextCurrent()) {
      reconciliation = await refreshAll(operation.session);
    }
    if (!publicationContextCurrent()) {
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
      preflight: null,
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
      if (!ownership.canCommit(operation, authorityRef.current)
        || options.contextCurrent?.() === false) return nothing;
      const fresh = await options.loadFresh({ session, signal: operation.controller.signal });
      if (fresh === null
        || !ownership.canCommit(operation, authorityRef.current)
        || options.contextCurrent?.() === false) return nothing;
      const preflight = options.preflight?.(fresh) ?? null;
      if (preflight !== null) {
        options.onPreflightRefusal?.(preflight, fresh, operation);
        return { ...nothing, status: 'preflight-refused', preflight };
      }
      const transmit = options.buildTransmit(fresh);
      if (transmit === null) {
        // No version means no command: reconcile the reads first so the message can be truthful.
        const reconciliation = await refreshAll(session);
        if (!ownership.canCommit(operation, authorityRef.current)
          || options.contextCurrent?.() === false) return nothing;
        options.onMissingVersion(fresh, reconciliation, operation);
        return { ...nothing, status: 'missing-version', reconciliation };
      }
      const blocked = ownership.blockReasonForDispatch(operation, authorityRef.current);
      if (blocked !== null
        || operation.dispatched
        || options.contextCurrent?.() === false) return nothing;
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
      return {
        status: 'applied',
        applied,
        reconciliation,
        failure: null,
        preflight: null,
        operation,
        published
      };
    } catch (cause) {
      if (isOperationsSessionChangedError(cause)) return nothing;
      if (!ownership.canCommit(operation, authorityRef.current)
        || options.contextCurrent?.() === false) return nothing;
      if (options.onFailure) {
        await options.onFailure(cause, operation);
        return { ...nothing, status: 'failed' };
      }
      const failure = await reportCommandFailure(cause, operation, options.name, {
        dropDetailWhenRejected: options.dropDetailWhenRejected,
        contextCurrent: options.contextCurrent
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

  /** Canonical tenant contact for the open Request, gated exactly like the lessor read. */
  const revealRequestTenantContact = useCallback(async (signal: AbortSignal): Promise<OperationsTenantContact> => {
    const request = detail.status === 'ready' ? detail.snapshot.request : undefined;
    if (!request) throw new ApiRequestError('This request is not open.', 404);
    const session = captureOperationsSession();
    if (!session) throw new ApiRequestError('Please sign in to use the Operations workspace.', 401);
    const epoch = ownership.sessionEpoch;
    const contact = await operationsWorkspaceService.tenantContactForRequest(request.requestId, {
      session,
      signal
    });
    if (!isOperationsSessionActive(session) || ownership.sessionEpoch !== epoch) {
      throw new OperationsSessionChangedError();
    }
    return contact;
  }, [detail, ownership]);

  /** Canonical tenant contact for the open Session. */
  const revealSessionTenantContact = useCallback(async (signal: AbortSignal): Promise<OperationsTenantContact> => {
    const openSession = detail.status === 'ready' ? detail.snapshot.session : undefined;
    if (!openSession) throw new ApiRequestError('This session is not open.', 404);
    const session = captureOperationsSession();
    if (!session) throw new ApiRequestError('Please sign in to use the Operations workspace.', 401);
    const epoch = ownership.sessionEpoch;
    const contact = await operationsWorkspaceService.tenantContactForSession(openSession.sessionId, {
      session,
      signal
    });
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

  /** Removes exactly the draft this completion belongs to; a newer editor is never touched. */
  const clearAvailabilityDraftIfGeneration = useCallback((editorGeneration: number): boolean => {
    const current = availabilityDraftRef.current;
    if (!current || current.editorGeneration !== editorGeneration) return false;
    writeAvailabilityDraft(null);
    return true;
  }, [writeAvailabilityDraft]);

  /**
   * Opens the one availability editor for an item of the visible DRAFT session. The draft captures
   * the exact recorded instants, zone and source it opens with, so a later refresh can be compared
   * against them instead of silently rewriting their meaning.
   */
  const beginAvailabilityDraft = useCallback((itemId: number) => {
    const openSession = detail.status === 'ready' ? detail.snapshot.session : undefined;
    if (!openSession || openSession.status !== 'DRAFT' || !accessGranted) return;
    const item = openSession.items.find(
      (candidate) => candidate.itemId === itemId && candidate.removedAt === null
    );
    if (!item) return;
    const session = captureOperationsSession();
    if (!session) return;
    availabilityEditorGeneration.current += 1;
    writeAvailabilityDraft(createAvailabilityDraft({
      sessionId: openSession.sessionId,
      sessionVersion: openSession.version,
      item,
      identity: session.identity,
      authorizationGeneration: authorityRef.current.authorizationGeneration(),
      editorGeneration: availabilityEditorGeneration.current
    }));
  }, [accessGranted, detail, writeAvailabilityDraft]);

  /**
   * Applies one user edit to the current draft. Identity, authority and the editor generation are
   * re-checked before the write, so an input event from a previous session or editor can never
   * mutate the draft that replaced it.
   */
  const changeAvailabilityDraft = useCallback((
    editorGeneration: number,
    values: OperationsAvailabilityDraftValues
  ) => {
    const current = availabilityDraftRef.current;
    if (!current || current.editorGeneration !== editorGeneration) return;
    const session = captureOperationsSession();
    if (!session
      || session.identity !== current.identity
      || !isOperationsSessionActive(session)
      || current.authorizationGeneration !== authorityRef.current.authorizationGeneration()) {
      clearAvailabilityDraftIfGeneration(editorGeneration);
      return;
    }
    const keepOutcome = current.status === 'saving'
      || current.status === 'reconciling'
      || current.status === 'uncertain';
    writeAvailabilityDraft({
      ...current,
      choice: values.choice,
      window: values.window,
      fieldProvenance: values.fieldProvenance,
      status: keepOutcome ? current.status : 'editing',
      // An unconfirmed outcome stays stated until a read or a new save resolves it.
      failureMessage: current.status === 'editing' || current.status === 'refused'
        ? null
        : current.failureMessage
    });
  }, [clearAvailabilityDraftIfGeneration, writeAvailabilityDraft]);

  /** Explicit cancellation discards the draft; a late completion may never recreate it. */
  const discardAvailabilityDraft = useCallback((editorGeneration: number) => {
    clearAvailabilityDraftIfGeneration(editorGeneration);
  }, [clearAvailabilityDraftIfGeneration]);

  /**
   * Records the lessor-stated availability for one DRAFT item.
   *
   * The draft is never closed before the guarded command's outcome is known. A confirmed success
   * clears exactly its own generation; a refusal, a pre-dispatch stop or an unconfirmed transmission
   * keeps the raw input and provenance recoverable. An unconfirmed transmission is reconciled with
   * one authoritative detail read and is never automatically resent.
   */
  const saveAvailability = useCallback(async (
    editorGeneration: number,
    window: AvailabilityWindowInput
  ): Promise<OperationsAvailabilitySaveOutcome> => {
    const draft = availabilityDraftRef.current;
    if (!draft || draft.editorGeneration !== editorGeneration) {
      return { status: 'superseded' };
    }
    const captured = captureOperationsSession();
    if (!captured
      || captured.identity !== draft.identity
      || !isOperationsSessionActive(captured)
      || draft.authorizationGeneration !== authorityRef.current.authorizationGeneration()
      || !accessGranted) {
      clearAvailabilityDraftIfGeneration(editorGeneration);
      return { status: 'superseded' };
    }
    if (draft.status === 'saving') {
      return {
        status: 'unconfirmed',
        message: 'A save is still in progress. Your changes are kept until its outcome is known.'
      };
    }
    const sessionId = draft.sessionId;
    const itemId = draft.itemId;
    const choice = draft.choice;
    const name = operationsRecordTitle('SESSION', sessionId);
    const epoch = ownership.sessionEpoch;
    /**
     * The exact availability facts rendered when the OE explicitly submitted. A preflight read may
     * refresh an unrelated Session version, but a different window/status must first be published
     * and reviewed rather than silently adopted as permission to overwrite it.
     */
    const presentedSession = detail.status === 'ready' ? detail.snapshot.session : null;
    const presentedItem = presentedSession?.sessionId === sessionId
      && presentedSession.status === 'DRAFT'
      ? presentedSession.items.find(
          (candidate) => candidate.itemId === draft.itemId && candidate.removedAt === null
        ) ?? null
      : null;
    if (presentedItem === null) return { status: 'superseded' };
    const presentedEvidence: AvailabilityEvidenceSnapshot = availabilityEvidenceSnapshot(presentedItem);
    const draftStillCurrent = (): boolean => {
      const current = availabilityDraftRef.current;
      return ownership.sessionEpoch === epoch
        && isOperationsSessionActive(captured)
        && current !== null
        && current.identity === draft.identity
        && current.authorizationGeneration === draft.authorizationGeneration
        && draft.authorizationGeneration === authorityRef.current.authorizationGeneration()
        && current.sessionId === sessionId
        && current.itemId === itemId
        && current.editorGeneration === editorGeneration;
    };
    const draftValuesChanged = (): boolean => {
      const current = availabilityDraftRef.current;
      if (!current) return false;
      return current.choice !== draft.choice
        || current.window.availabilityStartAt !== draft.window.availabilityStartAt
        || current.window.availabilityEndAt !== draft.window.availabilityEndAt
        || current.window.availabilityZoneId !== draft.window.availabilityZoneId
        || current.window.availabilitySource !== draft.window.availabilitySource
        || current.fieldProvenance.start !== draft.fieldProvenance.start
        || current.fieldProvenance.end !== draft.fieldProvenance.end;
    };

    /**
     * Publishes a lifecycle/item result only while the same editor and display context are current.
     */
    const publishFresh = (fresh: OperationsSessionDetail): boolean => {
      if (!draftStillCurrent()) return false;
      if (fresh.sessionId !== sessionId) return false;
      if (openRootRef.current?.targetType === 'SESSION'
        && openRootRef.current.targetId === sessionId) {
        setDetail({ status: 'ready', snapshot: { kind: 'session', session: fresh } });
      }
      return true;
    };

    const lifecycleRefusalMessage = (
      fresh: OperationsSessionDetail,
      earlierRequestMayHaveBeenSent: boolean
    ): string => {
      if (fresh.status !== 'DRAFT') {
        return earlierRequestMayHaveBeenSent
          ? `This session is now ${fresh.status}, so availability cannot be edited here. `
            + 'The earlier request’s outcome remains unconfirmed; nothing new was sent.'
          : `This session is now ${fresh.status}, so availability can no longer be edited here. `
            + 'Nothing was sent, and the current session state is shown.';
      }
      return earlierRequestMayHaveBeenSent
        ? 'That stop is no longer editable in this session. The earlier request’s outcome remains '
          + 'unconfirmed; nothing new was sent.'
        : 'That stop is no longer editable in this session. Nothing was sent, and the current '
          + 'itinerary is shown.';
    };

    /**
     * Settles one transmitted attempt whose response was lost. Current state can prove that the
     * desired state is present, but a mismatch never proves the original command was not applied.
     * A reliably read later version fences a still-late full-window command; anything else remains
     * unknown and is never resent automatically.
     */
    const settleUnknownAttempt = async (
      attempt: OperationsAvailabilityAttempt | null
    ): Promise<OperationsAvailabilitySaveOutcome> => {
      let fresh: OperationsSessionDetail | null = null;
      const mountedAtSettlementStart = mountedTargetFor({
        targetType: 'SESSION',
        targetId: sessionId
      });
      try {
        fresh = await operationsWorkspaceService.sessionDetail(sessionId, { session: captured });
      } catch (cause) {
        if (isOperationsSessionChangedError(cause)) return { status: 'superseded' };
        if (cause instanceof ApiRequestError && (cause.status === 403 || cause.status === 404)) {
          /**
           * A definitive denial of this exact root is acted on synchronously, before any later read
           * or publication. The captured mounted-root identity means a denial for editor A cannot
           * withdraw editor B, a remounted root or a newer account.
           */
          const openAtSettlementDenial = openRootRef.current;
          if (!draftStillCurrent()
            || mountedAtSettlementStart === null
            || openAtSettlementDenial === null
            || openAtSettlementDenial.targetType !== mountedAtSettlementStart.targetType
            || openAtSettlementDenial.targetId !== mountedAtSettlementStart.targetId
            || openAtSettlementDenial.mountGeneration !== mountedAtSettlementStart.mountGeneration) {
            return { status: 'superseded' };
          }
          // A definitive denial of this exact mounted root needs its protected state retired, not the
          // mutation lock held by another command. Clearing synchronously leaves that operation's
          // ownership untouched and retires the display context before any refresh can publish it.
          withdrawDeniedRoot(mountedAtSettlementStart);
          return { status: 'superseded' };
        }
        fresh = null;
      }
      if (!draftStillCurrent()) {
        return availabilityDraftRef.current === null
          ? { status: 'superseded' }
          : {
              status: 'unconfirmed',
              message: 'The save’s outcome is still being checked. Your changes are kept.'
            };
      }
      if (fresh !== null
        && (!Number.isSafeInteger(fresh.sessionId) || !Array.isArray(fresh.items))) {
        // A malformed read proves nothing about the command. It remains an unreadable transient
        // outcome, never a lifecycle refusal or a reason to withdraw protected data.
        fresh = null;
      }
      if (fresh === null) {
        const message = 'The earlier save is not confirmed. Its outcome could not be read, so '
          + 'nothing new was sent. Your changes are kept; check this session before saving again.';
        const current = availabilityDraftRef.current;
        if (current && current.editorGeneration === editorGeneration) {
          writeAvailabilityDraft({ ...current, status: 'uncertain', failureMessage: message });
        }
        return { status: 'unconfirmed', message };
      }

      if (fresh.sessionId !== sessionId) {
        clearAvailabilityDraftIfGeneration(editorGeneration);
        return {
          status: 'superseded',
          message: 'The server returned a different session, so this editor was closed.'
        };
      }
      publishFresh(fresh);
      const item = fresh.items.find(
        (candidate) => candidate.itemId === itemId && candidate.removedAt === null
      );
      if (!item) {
        const message = lifecycleRefusalMessage(fresh, attempt !== null);
        clearAvailabilityDraftIfGeneration(editorGeneration);
        showNotice(message, captured);
        return { status: 'superseded', message };
      }

      if (attempt !== null
        && availabilityItemSatisfiesDraft(item, attempt.choice, attempt.window)) {
        if (!draftValuesChanged() && availabilityIntentEqualsAttempt(choice, window, attempt)) {
          clearAvailabilityDraftIfGeneration(editorGeneration);
          announceSaved(true);
          return {
            status: 'saved',
            message: 'The server now shows the availability you intended; there is nothing left to retry.'
          };
        }
        const message = 'The server currently shows the availability from your earlier attempt. '
          + 'That request’s outcome remains unconfirmed. Your newer edits have not been saved; '
          + 'nothing new was sent.';
        const current = availabilityDraftRef.current;
        if (current && current.editorGeneration === editorGeneration) {
          writeAvailabilityDraft({
            ...current,
            attempt: null,
            status: 'refused',
            failureMessage: message
          });
        }
        return { status: 'not-saved', message };
      }

      if (fresh.status !== 'DRAFT' || !availabilityChoicesFor(item).includes(choice)) {
        const message = lifecycleRefusalMessage(fresh, attempt !== null);
        clearAvailabilityDraftIfGeneration(editorGeneration);
        showNotice(message, captured);
        return { status: 'superseded', message };
      }

      if (attempt === null) {
        const message = 'The earlier save has no dispatch record, so its outcome cannot be verified '
          + 'safely. Your changes are kept; check this session before saving again.';
        const current = availabilityDraftRef.current;
        if (current && current.editorGeneration === editorGeneration) {
          writeAvailabilityDraft({ ...current, status: 'uncertain', failureMessage: message });
        }
        return { status: 'unconfirmed', message };
      }

      if (fresh.version !== null && fresh.version > attempt.expectedSessionVersion) {
        const message = 'The availability changed after the earlier save, whose outcome is not '
          + 'confirmed. Nothing new was sent. Review the current window shown above, then save your '
          + 'changes again if needed.';
        const current = availabilityDraftRef.current;
        if (current && current.editorGeneration === editorGeneration) {
          writeAvailabilityDraft({
            ...current,
            attempt: null,
            status: 'refused',
            failureMessage: message
          });
        }
        return { status: 'not-saved', message };
      }

      const message = 'The earlier save is not confirmed. The server may still record it, so '
        + 'nothing new was sent. Your changes are kept; check this session before saving again.';
      const current = availabilityDraftRef.current;
      if (current && current.editorGeneration === editorGeneration) {
        writeAvailabilityDraft({ ...current, status: 'uncertain', failureMessage: message });
      }
      return { status: 'unconfirmed', message };
    };

    const announceSaved = (reconciled: boolean): void => {
      notifySuccess(
        'Property availability recorded',
        reconciled
          ? 'The server’s current state shows the availability you intended, so there is nothing left to retry.'
          : choice === 'unavailable'
            ? 'The stop is recorded as unavailable for this visit.'
            : choice === 'confirm-only'
              ? 'The confirmation was saved. Scheduling stays blocked until a complete window is recorded.'
              : 'The complete window was saved for this stop.',
        undefined,
        'VISIT_SESSION'
      );
    };

    const settlementKey = [
      draft.identity,
      epoch,
      sessionId,
      itemId,
      editorGeneration
    ].join('|');
    const settleUnknownAttemptOnce = (
      attempt: OperationsAvailabilityAttempt | null
    ): Promise<OperationsAvailabilitySaveOutcome> => {
      const flights = availabilitySettlementFlights.current;
      const existing = flights.get(settlementKey);
      if (existing) return existing;
      const ownedFlight: { current: Promise<OperationsAvailabilitySaveOutcome> | null } = {
        current: null
      };
      const flight = (async () => {
        try {
          return await settleUnknownAttempt(attempt);
        } finally {
          if (flights.get(settlementKey) === ownedFlight.current) flights.delete(settlementKey);
        }
      })();
      ownedFlight.current = flight;
      flights.set(settlementKey, flight);
      return flight;
    };

    // A previous attempt was transmitted without a confirmed outcome. Establish what the server
    // holds before building another command; if it already holds this intent, nothing is resent.
    if (draft.status === 'reconciling') {
      return settleUnknownAttemptOnce(draft.attempt);
    }
    if (draft.status === 'uncertain') {
      writeAvailabilityDraft({ ...draft, status: 'reconciling' });
      return settleUnknownAttemptOnce(draft.attempt);
    }

    const currentDraft = availabilityDraftRef.current;
    if (!currentDraft || currentDraft.editorGeneration !== editorGeneration) {
      return { status: 'superseded' };
    }
    writeAvailabilityDraft({
      ...currentDraft,
      attempt: null,
      status: 'saving',
      failureMessage: null
    });
    let preflightHandledForGeneration = false;
    const outcome = await runGuardedCommand<OperationsSessionDetail, OperationsSessionDetail>({
      kind: 'availability',
      busyKey: 'availability',
      name,
      contextCurrent: draftStillCurrent,
      loadFresh: (context) => operationsWorkspaceService.sessionDetail(sessionId, context),
      preflight: (fresh) => {
        if (fresh.sessionId !== sessionId) {
          return {
            disposition: 'inapplicable',
            message: 'The server returned a different session, so this editor was closed. Nothing was sent.'
          };
        }
        if (fresh.status !== 'DRAFT') {
          return { disposition: 'inapplicable', message: lifecycleRefusalMessage(fresh, false) };
        }
        const freshItem = fresh.items.find(
          (candidate) => candidate.itemId === itemId && candidate.removedAt === null
        );
        if (!freshItem || !availabilityChoicesFor(freshItem).includes(choice)) {
          return { disposition: 'inapplicable', message: lifecycleRefusalMessage(fresh, false) };
        }
        if (availabilityEvidenceChanged(presentedEvidence, freshItem)) {
          return {
            disposition: 'conflict',
            message: 'The recorded availability changed on the server before your save was sent. '
              + 'Nothing was sent. Review the current recorded window shown above, then save again '
              + 'if you still want to replace it.'
          };
        }
        return null;
      },
      onPreflightRefusal: (refusal, fresh) => {
        if (!draftStillCurrent()) return;
        if (fresh.sessionId === sessionId
          && openRootRef.current?.targetType === 'SESSION'
          && openRootRef.current.targetId === sessionId) {
          setDetail({ status: 'ready', snapshot: { kind: 'session', session: fresh } });
        }
        const current = availabilityDraftRef.current;
        if (!current || current.editorGeneration !== editorGeneration) return;
        preflightHandledForGeneration = true;
        if (refusal.disposition === 'inapplicable') {
          clearAvailabilityDraftIfGeneration(editorGeneration);
          showNotice(refusal.message, captured);
          return;
        }
        writeAvailabilityDraft({
          ...current,
          attempt: null,
          status: 'refused',
          failureMessage: refusal.message
        });
      },
      buildTransmit: (fresh) => {
        const validation = setAvailabilityCommand(fresh, itemId, choice, window);
        const command = validation.command;
        if (command === null) return null;
        const attempt: OperationsAvailabilityAttempt = {
          choice,
          itemId: command.itemId,
          expectedSessionVersion: command.expectedSessionVersion,
          window: {
            availabilityStartAt: command.availabilityStartAt,
            availabilityEndAt: command.availabilityEndAt,
            availabilityZoneId: command.availabilityZoneId,
            availabilitySource: command.availabilitySource
          }
        };
        return (context) => operationsWorkspaceService.setSessionItemAvailability(
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
          {
            ...context,
            onDispatch: () => {
              context.onDispatch?.();
              const current = availabilityDraftRef.current;
              if (!current
                || current.editorGeneration !== editorGeneration
                || current.identity !== draft.identity
                || current.authorizationGeneration !== draft.authorizationGeneration
                || ownership.sessionEpoch !== epoch) return;
              writeAvailabilityDraft({ ...current, attempt });
            }
          }
        );
      },
      applyResult: (_fresh, session, operation) => applyAuthoritativeSession(session, operation),
      onMissingVersion: (_fresh, reconciliation, operation) => missingVersionNotice(name, 'record that availability', reconciliation, operation)
    });

    if (outcome.status === 'preflight-refused' && outcome.preflight !== null) {
      if (!preflightHandledForGeneration) return { status: 'superseded' };
      return {
        status: 'not-saved',
        message: outcome.preflight.message
      };
    }

    if (outcome.status === 'applied') {
      const sessionNow = captureOperationsSession();
      if (!sessionNow
        || sessionNow.identity !== draft.identity
        || !isOperationsSessionActive(sessionNow)
        || ownership.sessionEpoch !== epoch) {
        return { status: 'superseded' };
      }
      // The business success is independent of the queue refresh that follows it: the draft is cleared
      // at this completion boundary even when reconciliation failed, and the refresh error is reported
      // separately rather than misclassifying the save.
      const cleared = clearAvailabilityDraftIfGeneration(editorGeneration);
      if (!cleared && availabilityDraftRef.current !== null) {
        // The command succeeded, but this editor was explicitly cancelled or replaced while it was in
        // flight. Its completion may never publish into the newer editing context.
        return { status: 'superseded' };
      }
      announceSaved(false);
      if (outcome.reconciliation !== 'loaded' && commandContextHolds(outcome)) {
        showNotice(ownershipRefreshFailureNotice(name));
      }
      return { status: 'saved', message: null };
    }

    const afterCommand = availabilityDraftRef.current;
    if (!afterCommand || afterCommand.editorGeneration !== editorGeneration) {
      return { status: 'superseded' };
    }
    if (outcome.status === 'abandoned') {
      const sessionNow = captureOperationsSession();
      if (!sessionNow
        || sessionNow.identity !== draft.identity
        || !isOperationsSessionActive(sessionNow)
        || ownership.sessionEpoch !== epoch) {
        clearAvailabilityDraftIfGeneration(editorGeneration);
        return { status: 'superseded' };
      }
      // A repeated tap never queues a second command. If another command still owns the lock, this
      // editor must not claim a failure or re-enable Save ahead of that command's own completion.
      if (ownership.isLocked()) {
        return {
          status: 'unconfirmed',
          message: 'A save is still in progress. Your changes are kept until its outcome is known.'
        };
      }
      writeAvailabilityDraft({ ...afterCommand, status: 'editing', failureMessage: null });
      return { status: 'not-saved', message: 'The save did not start. Your changes are kept.' };
    }
    if (outcome.status === 'missing-version') {
      const message = 'The session didn’t return the version needed to save availability, so nothing '
        + 'was sent. Your changes are kept; refresh this session before trying again.';
      writeAvailabilityDraft({
        ...afterCommand,
        attempt: null,
        status: 'refused',
        failureMessage: message
      });
      return { status: 'not-saved', message };
    }

    const failure = outcome.failure;
    if (failure === null || !failure.published) {
      if (!draftStillCurrent()) {
        return availabilityDraftRef.current === null
          ? { status: 'superseded' }
          : {
              status: 'unconfirmed',
              message: 'The save’s outcome is still being checked. Your changes are kept.'
            };
      }
      writeAvailabilityDraft({ ...afterCommand, status: 'editing', failureMessage: null });
      return { status: 'not-saved', message: 'The save was cancelled. Your changes are kept.' };
    }
    if (failure.kind === 'uncertain') {
      const current = availabilityDraftRef.current;
      if (!current || current.editorGeneration !== editorGeneration) {
        return { status: 'superseded' };
      }
      writeAvailabilityDraft({
        ...current,
        status: 'reconciling',
        failureMessage: failure.message
      });
      return settleUnknownAttemptOnce(current.attempt);
    }
    // A definitive refusal (or a pre-dispatch stop) leaves the raw input and provenance recoverable
    // wherever the item is still applicable and the OE is still authorized to edit it.
    writeAvailabilityDraft({
      ...afterCommand,
      attempt: null,
      status: 'refused',
      failureMessage: failure.message
    });
    return { status: 'not-saved', message: failure.message };
  }, [
    accessGranted,
    applyAuthoritativeSession,
    clearAvailabilityDraftIfGeneration,
    commandContextHolds,
    detail,
    missingVersionNotice,
    mountedTargetFor,
    notifySuccess,
    ownership,
    runGuardedCommand,
    showNotice,
    withdrawDeniedRoot,
    writeAvailabilityDraft
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
   * Restricted, plan-specific GE availability information. Every page is a fresh evaluation bound to
   * the exact Session version, and the read is abandoned when the capturing identity, authority or
   * mounted context changes.
   */
  const loadGeAvailability = useCallback(async (
    input: { q: string | null; cursor: string | null; size?: number },
    signal: AbortSignal
  ): Promise<OperationsGeAvailabilityView | null> => {
    const openSession = detail.status === 'ready' ? detail.snapshot.session : undefined;
    if (!openSession || openSession.version === null) return null;
    const session = captureOperationsSession();
    if (!session) return null;
    const epoch = ownership.sessionEpoch;
    const authorizationGeneration = authorityRef.current.authorizationGeneration();
    /*
     * The exact mounted Session instance this read belongs to, captured before the first await. A
     * denial is definitive only for this root instance: navigating to another protected root (or
     * re-opening the same one) must never inherit the earlier denial, and a delayed response from
     * this read must never touch whatever is on screen when it finally settles.
     */
    const mountedAtStart: MountedRootIdentity | null = openRootRef.current === null
      ? null
      : { ...openRootRef.current };
    const guarded = beginGuardedRead('ge-availability', signal);
    try {
      const view = await operationsWorkspaceService.geAvailability(
        openSession.sessionId,
        openSession.version,
        { q: input.q, cursor: input.cursor, size: input.size },
        { session, signal: guarded }
      );
      if (guarded.aborted || !isOperationsSessionActive(session) || ownership.sessionEpoch !== epoch) return null;
      if (mountedAtStart !== null && !sameOperationsRootInstance(openRootRef.current, mountedAtStart)) return null;
      return view;
    } catch (cause) {
      const status = cause instanceof ApiRequestError ? cause.status : null;
      const current = !guarded.aborted
        && isOperationsSessionActive(session)
        && ownership.sessionEpoch === epoch
        && authorityRef.current.authorizationGeneration() === authorizationGeneration
        && mountedAtStart !== null
        && mountedAtStart.targetType === 'SESSION'
        && mountedAtStart.targetId === openSession.sessionId
        && sameOperationsRootInstance(openRootRef.current, mountedAtStart);
      /*
       * A 403/404 for the root still on screen is a definitive authorization/root denial. The
       * withdrawal is synchronous and exact: it removes the protected detail, aborts sibling
       * protected reads and closes the drawer before this error is rethrown to the informational
       * view. A 403 also asks the authoritative capability endpoint for a fresh verdict; a 404
       * never invents a Team revocation, and neither path logs out an otherwise valid session.
       */
      if (current && mountedAtStart !== null && (status === 403 || status === 404)) {
        withdrawDeniedRootRef.current?.(mountedAtStart);
        if (status === 403) authorityRef.current.revalidate();
      }
      throw cause;
    }
  }, [beginGuardedRead, detail, ownership]);

  /**
   * Books the exact selected Ground Executive and evaluated slot.
   *
   * The outcome is published as drawer state rather than inferred: a `200` applies the returned
   * Session, a refusal keeps the selection and asks for a refresh, and an unconfirmed transmission is
   * reported as unknown until explicitly reconciled or retried with its saved command identity.
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
    if (ownership.isLocked()) return;
    const captured = captureOperationsSession();
    if (!captured) return;
    try {
      if (readSchedulingIntent(captured, sessionId)) {
        setScheduleOutcome({ kind: 'uncertain', message: 'A previous booking is still unconfirmed. Check the session before scheduling again.' });
        return;
      }
    } catch {
      setScheduleOutcome({ kind: 'uncertain', message: 'The pending booking record could not be read. Check the session before scheduling again.' });
      return;
    }
    setScheduleOutcome(null);
    const outcome = await runGuardedCommand<OperationsSessionDetail, OperationsSchedulingApproval & { currentSession: OperationsSessionDetail }>({
      kind: 'schedule',
      busyKey: 'schedule',
      name,
      loadFresh: (context) => operationsWorkspaceService.sessionDetail(sessionId, context),
      buildTransmit: (fresh) => {
        const built = scheduleSelectionCommand(fresh, selection, view, reason);
        const command = built.command;
        return command === null
          ? null
          : async (context) => {
            const body = {
              expectedSessionVersion: command.expectedSessionVersion,
              groundExecutiveUserId: command.groundExecutiveUserId,
              scheduledAt: command.scheduledAt,
              zoneId: command.zoneId,
              overrideReason: command.overrideReason
            };
            const intent = preserveSchedulingIntent(captured, sessionId, body);
            const controller = new AbortController();
            const abort = () => controller.abort();
            if (context.signal?.aborted) abort();
            context.signal?.addEventListener('abort', abort, { once: true });
            const timer = setTimeout(abort, 15000);
            try {
              const approval = await operationsWorkspaceService.selectGroundExecutive(
                sessionId, { ...intent.body, commandId: intent.commandId }, { ...context, signal: controller.signal });
              // A replay deliberately returns the original approval. Read current truth as well:
              // another authorized command may have changed the Session since that approval.
              const current = await operationsWorkspaceService.sessionDetail(sessionId,
                { session: context.session, signal: controller.signal });
              return { ...approval, currentSession: current };
            } finally { clearTimeout(timer); context.signal?.removeEventListener('abort', abort); }
          };
      },
      applyResult: (_fresh, approval, operation) => {
        const intent = readSchedulingIntent(operation.session, sessionId);
        if (intent) forgetSchedulingIntent(operation.session, sessionId, intent.commandId);
        // A confirmed booking always carries the authoritative Session it committed.
        const applied = applyAuthoritativeSession(approval.currentSession, operation);
        // The drawer outcome is bound to the initiating drawer instance as well, so it is published
        // only while that exact drawer is the one on screen.
        if (applied && scheduleOutcomeContextHolds(operation, drawer)) {
          setScheduleOutcome({ kind: 'scheduled', approval, currentSession: approval.currentSession, refreshFailed: false });
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
        if (operation.dispatched && !(cause instanceof ApiRequestError && [401, 403, 404].includes(cause.status ?? 0))) {
          let message = 'The request was sent, but its result is unconfirmed. Check the session to verify the booking.';
          if (cause instanceof ApiRequestError && [400, 409, 422].includes(cause.status ?? 0)) {
            try {
              const intent = readSchedulingIntent(operation.session, sessionId);
              const receipt = intent && await operationsWorkspaceService.schedulingCommand(sessionId, intent,
                { session: operation.session, signal: operation.controller.signal });
              if (!ownership.canCommit(operation, authorityRef.current)) return;
              if (intent && receipt === null) {
                forgetSchedulingIntent(operation.session, sessionId, intent.commandId);
                if (scheduleOutcomeContextHolds(operation, drawer)) setScheduleOutcome({ kind: 'conflict', status: cause.status ?? 0,
                  message: 'The server rejected this command and no completed receipt was recorded.', detail: cause.message });
                return;
              }
            } catch { message = 'The request result could not be verified. Check again when the connection is available.'; }
          }
          if (ownership.canCommit(operation, authorityRef.current) && scheduleOutcomeContextHolds(operation, drawer))
            setScheduleOutcome({ kind: 'uncertain', message });
          return;
        }
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
          currentSession: outcome.applied.currentSession,
          refreshFailed: outcome.reconciliation !== 'loaded'
        });
      }
      const scheduled = outcome.applied.currentSession;
      notifySuccess(
        scheduled?.status === 'SCHEDULED' ? `${name} scheduled` : `${name} booking accepted`,
        scheduled?.status !== 'SCHEDULED'
          ? `The booking command was accepted. Current Session status: ${scheduled.status}. Review its current details.`
          : scheduled?.representativeUserId === null || !scheduled
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

  const reconcileScheduling = useCallback(async (retry = false) => {
    if (ownership.isLocked() || detail.status !== 'ready' || !detail.snapshot.session) return;
    const sessionId = detail.snapshot.session.sessionId;
    const session = captureOperationsSession();
    if (!session) return;
    const drawer = { ...scheduleDrawerRef.current };
    const operation = ownership.begin('schedule', session, authorityRef.current.authorizationGeneration(), openRootRef.current);
    setBusyCommand('schedule');
    const verification = new AbortController();
    const abortVerification = () => verification.abort();
    operation.controller.signal.addEventListener('abort', abortVerification, { once: true });
    const timer = setTimeout(abortVerification, 15000);
    const context = { session, signal: verification.signal };
    // Acceptance, the last verified Session and read freshness are independent facts.
    // Keep this bounded to the existing authenticated drawer lifecycle; never persist its data.
    let accepted = scheduleOutcome?.kind === 'scheduled' && scheduleOutcome.approval.session.sessionId === sessionId
      ? scheduleOutcome : null;
    try {
      const intent = accepted ? null : readSchedulingIntent(session, sessionId);
      const knownApproval = accepted?.approval ?? null;
      if (!intent && !knownApproval) throw Error('No pending command receipt is available. Review the authoritative session details.');
      if (retry && (!intent || scheduleOutcome?.kind !== 'uncertain' || scheduleOutcome.retryCommandId !== intent.commandId)) return;
      let receipt = intent ? await operationsWorkspaceService.schedulingCommand(sessionId, intent, context) : knownApproval;
      if (!ownership.canCommit(operation, authorityRef.current) || !scheduleOutcomeContextHolds(operation, drawer)) return;
      if (receipt) {
        accepted = { kind: 'scheduled', approval: receipt, currentSession: accepted?.currentSession ?? null,
          refreshFailed: accepted?.refreshFailed ?? false, verificationFailed: true };
        if (intent) forgetSchedulingIntent(session, sessionId, intent.commandId);
      }
      let fresh = await operationsWorkspaceService.sessionDetail(sessionId, context);
      if (!ownership.canCommit(operation, authorityRef.current) || !scheduleOutcomeContextHolds(operation, drawer)) return;
      if (!receipt && intent) {
        // The receipt read enforces current OE scope/ownership server-side. NOT_RECORDED is only
        // an observation: the original transaction can still be running. Only the exact saved
        // command may be retried, and the backend remains the final concurrency authority.
        const saved = readSchedulingIntent(session, sessionId);
        if (JSON.stringify(saved) !== JSON.stringify(intent)) throw Error('Pending command changed.');
        const eligible = fresh.sessionId === sessionId && fresh.status === 'DRAFT'
          && fresh.version === intent.body.expectedSessionVersion && fresh.coordinatorUserId === session.userId;
        if (!eligible) {
          applyAuthoritativeSession(fresh, operation);
          setScheduleOutcome({ kind: 'uncertain', message: `Current Session status: ${fresh.status}, version ${fresh.version ?? 'unknown'}. Its state or ownership changed. The original command remains unconfirmed; retry is unavailable. Check the session again to reconcile.` });
          return;
        }
        if (!retry) {
          applyAuthoritativeSession(fresh, operation);
          setScheduleOutcome({ kind: 'uncertain', retryCommandId: intent.commandId,
            message: 'No completed command is recorded. This does not prove failure. You still own the unchanged DRAFT Session. Retry scheduling will recheck access and resend only the original booking command.' });
          return;
        }
        receipt = await operationsWorkspaceService.selectGroundExecutive(sessionId,
          { ...intent.body, commandId: intent.commandId }, { ...context, onDispatch: () => ownership.markDispatched(operation) });
        if (!ownership.canCommit(operation, authorityRef.current) || !scheduleOutcomeContextHolds(operation, drawer)) return;
        accepted = { kind: 'scheduled', approval: receipt, currentSession: null, refreshFailed: false, verificationFailed: true };
        forgetSchedulingIntent(session, sessionId, intent.commandId);
        fresh = await operationsWorkspaceService.sessionDetail(sessionId, context);
        if (!ownership.canCommit(operation, authorityRef.current) || !scheduleOutcomeContextHolds(operation, drawer)) return;
      }
      if (!receipt) return;
      applyAuthoritativeSession(fresh, operation);
      accepted = { kind: 'scheduled', approval: receipt, currentSession: fresh, refreshFailed: false, verificationFailed: false };
      setScheduleOutcome(accepted);
      const refreshed = await refreshAll(session);
      if (ownership.canCommit(operation, authorityRef.current) && scheduleOutcomeContextHolds(operation, drawer))
        setScheduleOutcome({ ...accepted, refreshFailed: refreshed !== 'loaded' });
    } catch (cause) {
      if (isOperationsSessionChangedError(cause) || !ownership.canCommit(operation, authorityRef.current)) return;
      if (cause instanceof ApiRequestError && [401, 403, 404].includes(cause.status ?? 0)) {
        await reportCommandFailure(cause, operation, operationsRecordTitle('SESSION', sessionId));
      } else if (scheduleOutcomeContextHolds(operation, drawer)) {
        setScheduleOutcome(accepted ? { ...accepted, verificationFailed: true }
          : { kind: 'uncertain', message: 'The booking could not be verified. Check again when the connection is available.' });
      }
    } finally {
      clearTimeout(timer);
      operation.controller.signal.removeEventListener('abort', abortVerification);
      if (ownership.finish(operation)) setBusyCommand(null);
    }
  }, [ownership, detail, scheduleOutcome, scheduleOutcomeContextHolds, applyAuthoritativeSession, refreshAll, reportCommandFailure]);

  const navigate = useCallback((next: QueueView) => {
    const nextFilters = EMPTY_OPERATIONS_FILTERS;
    setView(next);
    setFilters(nextFilters);
    filtersRef.current = nextFilters;
    commitRoute(next === 'home' ? { kind: 'home' } : queueRoute(next, 1, nextFilters));
    if (next === 'attention') void loadAttention(0, undefined, undefined, nextFilters);
    else if (next === 'my-work') void loadMyWork(0, undefined, undefined, nextFilters);
    else if (next === 'claimable') void loadClaimable(0, undefined, undefined, nextFilters);
    returnToRow.current = null;
    window.scrollTo({ top: 0 });
    focusWorkspaceTitle();
  }, [commitRoute, loadAttention, loadClaimable, loadMyWork]);

  /**
   * Applies one parsed location to the workspace. History writing is separate so a popstate can
   * restore a route without pushing a duplicate entry.
   */
  const applyOperationsRoute = useCallback((
    route: OperationsRoute,
    options: { writeHistory?: boolean } = {}
  ) => {
    routeRef.current = route;
    if (route.kind === 'home') {
      closeDetail();
      setView('home');
      setFilters(EMPTY_OPERATIONS_FILTERS);
      filtersRef.current = EMPTY_OPERATIONS_FILTERS;
    } else if (route.kind === 'queue') {
      closeDetail();
      setView(route.queue);
      setFilters(route.filters);
      filtersRef.current = route.filters;
      const pageIndex = Math.max(0, route.page - 1);
      if (route.queue === 'attention') void loadAttention(pageIndex, undefined, undefined, route.filters);
      else if (route.queue === 'my-work') void loadMyWork(pageIndex, undefined, undefined, route.filters);
      else void loadClaimable(pageIndex, undefined, undefined, route.filters);
    } else {
      const from = route.from === 'home' ? 'home' : route.from;
      setView(from);
      setFilters(route.filters);
      filtersRef.current = route.filters;
      void openDetail(
        { targetType: route.targetType, targetId: route.targetId, source: from === 'claimable' ? 'claimable' : 'owned' },
        undefined,
        undefined,
        { from, page: route.page, filters: route.filters, writeHistory: false }
      );
    }
    if (options.writeHistory === true) commitRoute(route, 'replace');
  }, [closeDetail, commitRoute, loadAttention, loadClaimable, loadMyWork, openDetail]);

  const routeAdmissionRef = useRef(false);
  useEffect(() => {
    if (!isPageLayout) {
      routeAdmissionRef.current = false;
      return;
    }
    if (!accessGranted) {
      routeAdmissionRef.current = false;
      return;
    }
    if (routeAdmissionRef.current) return;
    routeAdmissionRef.current = true;
    const route = readBrowserOperationsRoute();
    if (route.kind === 'home') {
      // An unknown Operations sub-path or malformed query is safely canonicalised to the overview.
      commitRoute({ kind: 'home' }, 'replace');
      return;
    }
    if (route.kind === 'queue' && route.page === 1 && !hasOperationsFilters(route.filters)) return;
    applyOperationsRoute(route, { writeHistory: true });
  }, [accessGranted, applyOperationsRoute, commitRoute, isPageLayout]);

  useEffect(() => {
    if (!isPageLayout) return undefined;
    const onPopState = () => {
      const route = readBrowserOperationsRoute();
      if (operationsRoutesEqual(route, routeRef.current)) return;
      applyOperationsRoute(route);
      focusWorkspaceTitle();
    };
    window.addEventListener('popstate', onPopState);
    return () => window.removeEventListener('popstate', onPopState);
  }, [applyOperationsRoute, isPageLayout]);

  const applyFilters = useCallback((next: OperationsQueueFilters) => {
    if (view === 'home') return;
    const canonical = normalizeOperationsFilters(next);
    setFilters(canonical);
    filtersRef.current = canonical;
    commitRoute(queueRoute(view, 1, canonical));
    if (view === 'attention') void loadAttention(0, undefined, undefined, canonical);
    else if (view === 'my-work') void loadMyWork(0, undefined, undefined, canonical);
    else void loadClaimable(0, undefined, undefined, canonical);
    focusWorkspaceTitle();
  }, [commitRoute, loadAttention, loadClaimable, loadMyWork, view]);

  const backToQueue = useCallback(() => {
    const historyState = typeof window === 'undefined'
      ? null
      : (window.history.state as { usr?: { pathomeOperationsPushed?: boolean } } | null)?.usr;
    if (isPageLayout && historyState?.pathomeOperationsPushed === true) {
      routerNavigate(-1);
      return;
    }
    const current = routeRef.current;
    const target = current.kind === 'detail'
      ? returnRouteForDetail(current)
      : current.kind === 'queue'
        ? queueRoute(current.queue, 1, current.filters)
        : { kind: 'home' as const };
    applyOperationsRoute(target, { writeHistory: true });
    focusWorkspaceTitle();
  }, [applyOperationsRoute, isPageLayout, routerNavigate]);

  /**
   * Reconciles the visible context after the effective City/Team authority changed.
   *
   * Everything protected is dropped first, then only a still-authorized route is rebuilt. Filters
   * that no longer name an authorized City/Team are removed before the server read, while independent
   * type/status filters survive when they remain valid.
   */
  const reconcileScopeChange = useCallback((scopes: OperationsScope[]) => {
    const current = routeRef.current;
    const activeFilters = current.kind === 'home' ? EMPTY_OPERATIONS_FILTERS : current.filters;
    const nextFilters = normalizeOperationsFilters({
      ...activeFilters,
      cityId: activeFilters.cityId !== null
        && scopes.some((scope) => scope.cityId === activeFilters.cityId)
        ? activeFilters.cityId : null,
      teamId: activeFilters.teamId !== null
        && scopes.some((scope) => scope.teamId === activeFilters.teamId)
        ? activeFilters.teamId : null
    });
    const hadProtectedWork = current.kind === 'detail'
      || availabilityDraftRef.current !== null
      || busyCommand !== null;
    clearProtectedState('authorization-lost');
    const next: OperationsRoute = current.kind === 'detail'
      ? (current.from === 'home' ? { kind: 'home' } : queueRoute(current.from, 1, nextFilters))
      : current.kind === 'queue'
        ? queueRoute(current.queue, 1, nextFilters)
        : { kind: 'home' };
    applyOperationsRoute(next, { writeHistory: true });
    void refreshAll();
    if (hadProtectedWork) {
      showNotice('Your City or Team access changed. Work tied to the previous scope was cleared and reloaded from your current access.');
    }
  }, [applyOperationsRoute, busyCommand, clearProtectedState, refreshAll, showNotice]);

  useLayoutEffect(() => {
    if (!accessGranted) {
      if (admittedScopeFingerprint !== null) setAdmittedScopeFingerprint(null);
      return;
    }
    if (admittedScopeFingerprint === null) {
      setAdmittedScopeFingerprint(effectiveScopeFingerprint);
      return;
    }
    if (admittedScopeFingerprint === effectiveScopeFingerprint) return;
    setAdmittedScopeFingerprint(effectiveScopeFingerprint);
    reconcileScopeChange(operationsCapabilityScopes(capabilities));
  }, [
    accessGranted,
    admittedScopeFingerprint,
    capabilities,
    effectiveScopeFingerprint,
    reconcileScopeChange
  ]);

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
    return `${identity}|${detailTarget.source}|${detailTarget.targetType}-${detailTarget.targetId}`
      + `|v${version}|a${authorityRef.current.authorizationGeneration()}`;
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
      void openSchedulingDrawerFresh(detail.snapshot.session.sessionId);
    },
    onReorderItems: (itemIds) => void reorderItems(itemIds),
    onRemoveItem: (itemId, reason) => void removeItem(itemId, reason),
    onAddStop: (input) => void addStop(input),
    revealRequestContact,
    revealItemContact,
    revealRequestTenantContact,
    revealSessionTenantContact,
    searchListings,
    loadOwnedCandidates
  };

  /**
   * The draft may only reach the detail while its exact Session is the open, editable root and its
   * item is still active and editable. This render-time guard means a refresh that removed the item
   * or moved the Session out of DRAFT can never briefly re-open an editor against stale context.
   */
  const openSessionForDraft = detail.status === 'ready' ? detail.snapshot.session : undefined;
  const visibleAvailabilityDraft = availabilityDraft
    && protectedAccess
    && openSessionForDraft?.sessionId === availabilityDraft.sessionId
    && openSessionForDraft.status === 'DRAFT'
    && openSessionForDraft.items.some(
      (item) => item.itemId === availabilityDraft.itemId
        && item.removedAt === null
        && item.confirmationStatus !== 'UNAVAILABLE'
    )
    ? availabilityDraft
    : null;
  const availabilityEditing: OperationsAvailabilityEditing = {
    draft: visibleAvailabilityDraft,
    begin: beginAvailabilityDraft,
    change: changeAvailabilityDraft,
    discard: discardAvailabilityDraft,
    save: saveAvailability
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

  /**
   * The authority is granted, but its effective City/Team scope changed. Nothing built for the
   * previous scope may remain on screen for even one paint; the layout effect clears and reloads it.
   */
  if (accessGranted && !scopeCurrent) {
    return (
      <section className="oe oe--embedded" aria-label="Operations workspace">
        <div className="oe-main">
          <div className="oe-stack" aria-busy="true">
            <h2 id="oe-page-title" tabIndex={-1} className="oe-sr">Updating your team access</h2>
            <StateMessage
              icon={<Lock size={20} />}
              title="Updating your team access"
              body="Your City or Team assignment changed. Work from the previous scope is hidden while the queues reload from your current access."
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
    { view: 'attention', label: 'Needs attention', icon: Flag, count: attention.status === 'ready' ? attention.unfilteredTotalCount : null, flagged: true },
    { view: 'my-work', label: 'My work', icon: ListChecks, count: myWork.status === 'ready' ? myWork.unfilteredTotalCount : null },
    { view: 'claimable', label: 'Available work', icon: Inbox, count: claimable.status === 'ready' ? claimable.unfilteredTotalCount : null }
  ];
  const navButton = (
    item: (typeof navItems)[number],
    variant: 'sidebar' | 'tabs'
  ) => {
    const Icon = item.icon;
    const current = detailTarget === null && view === item.view;
    return (
      <button
        key={`${variant}-${item.view}`}
        type="button"
        className={`${variant === 'sidebar' ? 'oe-sidebar-btn' : 'oe-tab-btn'}${item.flagged ? ' oe-nav-btn--flag' : ''}`}
        aria-current={current ? 'page' : undefined}
        onClick={() => { closeDetail(); navigate(item.view); }}
      >
        <Icon size={variant === 'sidebar' ? 18 : 20} aria-hidden="true" />
        <span className="oe-nav-label">{item.label}</span>
        {item.count !== null ? <span className="oe-nav-count">{item.count}</span> : null}
      </button>
    );
  };

  return (
    <section className={isPageLayout ? 'oe oe--page' : 'oe oe--embedded'} aria-label="Operations workspace">
      {isPageLayout ? <a className="oe-skip" href="#oe-main-content">Skip to Operations content</a> : null}
      <div className={isPageLayout ? 'oe-shell' : undefined}>
        {isPageLayout ? (
          <aside className="oe-sidebar" aria-label="Operations navigation">
            <p className="oe-sidebar-brand">Operations</p>
            <nav className="oe-sidebar-nav" aria-label="Operations destinations">
              {navItems.map((item) => navButton(item, 'sidebar'))}
            </nav>
          </aside>
        ) : null}
        <div className="oe-main" id={isPageLayout ? 'oe-main-content' : undefined} tabIndex={isPageLayout ? -1 : undefined}>
          <div className="oe-stack">
          {!isPageLayout ? (
            <nav className="oe-subnav" aria-label="Operations queues">
              {navItems.map((item) => (
                <button
                  key={item.view}
                  type="button"
                  className={`oe-subnav-btn${item.flagged ? ' oe-subnav-btn--flag' : ''}`}
                  aria-current={detailTarget === null && view === item.view ? 'page' : undefined}
                  onClick={() => { closeDetail(); navigate(item.view); }}
                >
                  {React.createElement(item.icon, { size: 16, 'aria-hidden': true })}
                  {item.label}
                  {item.count !== null ? <span className="oe-subnav-count">{item.count}</span> : null}
                </button>
              ))}
            </nav>
          ) : null}

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
                schedulingOpening={schedulingOpening}
                schedulingOpenError={schedulingOpenError}
                contactContextKey={contactContextKey}
                actions={detailActions}
                availability={availabilityEditing}
                onBack={backToQueue}
                onRetry={() => void openDetail(detailTarget, undefined, undefined, {
                  from: view,
                  page: (view === 'attention' ? attention.page : view === 'my-work' ? myWork.page : view === 'claimable' ? claimable.page : 0) + 1,
                  filters,
                  writeHistory: false
                })}
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
                    onRetryScheduling={() => void reconcileScheduling(true)}
                    loadRanked={loadRankedRecommendations}
                    loadDiscovery={loadGeDiscovery}
                    loadAvailability={loadGeAvailability}
                    onRefreshSession={() => {
                      if (scheduleOutcome?.kind === 'uncertain' || scheduleOutcome?.kind === 'scheduled') void reconcileScheduling();
                      else if (detailTarget) void openDetail(detailTarget, undefined, undefined, {
                        from: view,
                        page: (view === 'attention' ? attention.page : view === 'my-work' ? myWork.page : view === 'claimable' ? claimable.page : 0) + 1,
                        filters,
                        writeHistory: false
                      });
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
                commitRoute(queueRoute(view, page + 1, filters));
                if (view === 'attention') void loadAttention(page, undefined, undefined, filters);
                else if (view === 'my-work') void loadMyWork(page, undefined, undefined, filters);
                else if (view === 'claimable') void loadClaimable(page, undefined, undefined, filters);
                focusWorkspaceTitle();
              }}
              onOpen={openDetail}
              onClaim={(item) => void claimRoot(item)}
              onRefresh={() => {
                if (view === 'attention') void loadAttention(attention.page, undefined, undefined, filters);
                else if (view === 'my-work') void loadMyWork(myWork.page, undefined, undefined, filters);
                else if (view === 'claimable') void loadClaimable(claimable.page, undefined, undefined, filters);
              }}
              onNavigate={navigate}
              filters={filters}
              scopes={scopes}
              onFiltersChange={applyFilters}
            />
          )}
          </div>
        </div>
        {isPageLayout ? (
          <nav className="oe-tabbar" aria-label="Operations destinations">
            {navItems.map((item) => navButton(item, 'tabs'))}
          </nav>
        ) : null}
      </div>
    </section>
  );
};

const OverviewView: React.FC<{
  scopes: OperationsScope[];
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
    { view: 'claimable' as QueueView, label: 'Available work', hint: 'Unclaimed in your teams', queue: claimable }
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
            linkLabel="Available work"
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
  filters: OperationsQueueFilters;
  scopes: OperationsScope[];
  onFiltersChange: (filters: OperationsQueueFilters) => void;
}> = ({
  view, attention, myWork, claimable, busyClaimKey, blocked, onPage, onOpen, onClaim, onRefresh, onNavigate,
  filters, scopes, onFiltersChange
}) => {
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

      <OperationsFilterBar
        filters={filters}
        scopes={scopes}
        totalCount={queue.totalCount}
        unfilteredTotalCount={queue.unfilteredTotalCount}
        loading={queue.status === 'loading'}
        onChange={onFiltersChange}
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
      ) : queue.totalCount === 0 && hasOperationsFilters(filters) ? (
        <StateMessage
          icon={<Filter size={20} />}
          title="No records match these filters"
          body={`Your authorized scope has ${queue.unfilteredTotalCount} ${queue.unfilteredTotalCount === 1 ? 'record' : 'records'}, but none match the selected filters. City and Team filters can hide records that are still authorized.`}
        >
          <button type="button" className="oe-btn oe-btn--secondary oe-btn--md" onClick={() => onFiltersChange(EMPTY_OPERATIONS_FILTERS)}>
            Clear filters
          </button>
        </StateMessage>
      ) : queue.totalCount === 0 ? (
        <StateMessage icon={<Inbox size={20} />} title={copy.emptyTitle} body={copy.emptyBody}>
          {view !== 'claimable' ? (
            <button type="button" className="oe-btn oe-btn--secondary oe-btn--md" onClick={() => onNavigate('claimable')}>
              View available work
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
