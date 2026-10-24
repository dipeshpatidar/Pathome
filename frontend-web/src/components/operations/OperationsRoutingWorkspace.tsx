import React, {
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState
} from 'react';
import {
  ArrowLeft,
  CheckCircle2,
  CircleAlert,
  Clock3,
  MapPin,
  RefreshCw,
  Route,
  ShieldCheck,
  Users
} from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import {
  resolveOperationsAuthorityView,
  type OperationsAuthority
} from '../../hooks/useOperationsCapability';
import { ApiRequestError } from '../../services/apiError';
import {
  operationsRoutingService,
  type RoutingCandidateSet,
  type RoutingCoverage,
  type RoutingCoverageOptions,
  type RoutingTask,
  type RoutingUnclaimedWork
} from '../../services/operationsRoutingService';
import {
  hasOperationsWorkspaceAccess,
  routingAuthorityScopeFingerprint,
  routingCapabilityScopes
} from '../../utils/operationsWorkspacePresentation';
import { reconcileRoutingPage } from '../../utils/operationsRoutingPagination';
import {
  captureOperationsSession,
  isOperationsSessionActive,
  isOperationsSessionChangedError,
  subscribeOperationsSession,
  type OperationsSession
} from '../../utils/operationsSession';
import './operationsRouting.css';

type LoadState = 'loading' | 'ready' | 'error';
const PAGE_SIZE = 20;

interface RoutingOperation {
  id: number;
  label: string;
  session: OperationsSession;
  authorityGeneration: number;
  controller: AbortController;
  signal: AbortSignal;
}

const pageLabel = (page: number, totalPages: number): string =>
  totalPages === 0 ? 'Page 1 of 1' : `Page ${page + 1} of ${totalPages}`;

const PageControls: React.FC<{
  page: number;
  totalPages: number;
  label: string;
  disabled?: boolean;
  onPage: (page: number) => void;
}> = ({ page, totalPages, label, disabled = false, onPage }) => (
  <nav className="routing-page-controls" aria-label={`${label} pages`}>
    <button type="button" className="routing-button routing-button--quiet"
      disabled={disabled || page <= 0} onClick={() => onPage(page - 1)}>
      Previous
    </button>
    <span aria-live="polite">{pageLabel(page, totalPages)}</span>
    <button type="button" className="routing-button routing-button--quiet"
      disabled={disabled || totalPages === 0 || page >= totalPages - 1}
      onClick={() => onPage(page + 1)}>
      Next
    </button>
  </nav>
);

const stateLabel = (state: string): string => {
  if (state === 'NEEDS_REVIEW') return 'Needs a Team';
  if (state === 'RETRY') return 'Retry scheduled';
  if (state === 'PENDING') return 'Waiting to route';
  if (state === 'DONE') return 'Team assigned';
  if (state === 'SKIPPED') return 'Already handled';
  return 'Routing status unavailable';
};

const reasonLabel = (reason: string | null): string => {
  switch (reason) {
    case 'NO_ACTIVE_TEAM_COVERAGE': return 'No active Team covers this locality.';
    case 'OVERLAPPING_ACTIVE_COVERAGE': return 'More than one active Team covers this locality.';
    case 'CANONICAL_CITY_UNRESOLVED': return 'The listing does not have a verified operational City.';
    case 'CANONICAL_CITY_CONFLICT': return 'The listing and its locality point to different Cities.';
    case 'CANONICAL_SCOPE_NOT_READY': return 'The Request does not have a verified operational City yet.';
    case 'STALE_REQUEST_VERSION': return 'The Request changed before routing finished.';
    case 'STALE_COVERAGE': return 'Coverage changed before routing finished.';
    case 'ROUTING_PROTOCOL_UNSUPPORTED': return 'This application version does not support the routing protocol.';
    case 'ROUTING_LOCK_OR_TIMEOUT': return 'The routing service was busy. Retry will be automatic.';
    case 'ROUTING_TECHNICAL_FAILURE': return 'A temporary technical problem delayed routing.';
    case 'INACTIVE_CITY': return 'The Request City is not operational.';
    case 'INACTIVE_TEAM': return 'The covered Team is no longer active.';
    default: return reason ? 'Routing could not choose a Team safely.' : 'Waiting for the next routing check.';
  }
};

const formatDate = (value: string | null): string => {
  if (!value) return 'Time not recorded';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return 'Time not recorded';
  return new Intl.DateTimeFormat('en-IN', {
    day: 'numeric',
    month: 'short',
    hour: 'numeric',
    minute: '2-digit'
  }).format(date);
};

export const OperationsRoutingWorkspace: React.FC<{ authority: OperationsAuthority }> = ({ authority }) => {
  const navigate = useNavigate();
  const capabilities = authority.capabilities;
  const scopes = useMemo(() => routingCapabilityScopes(capabilities), [capabilities]);
  const authorityFingerprint = routingAuthorityScopeFingerprint(capabilities);
  const [tasks, setTasks] = useState<RoutingTask[]>([]);
  const [tasksState, setTasksState] = useState<LoadState>('loading');
  const [taskTotalCount, setTaskTotalCount] = useState(0);
  const [tasksPage, setTasksPage] = useState(0);
  const [taskTotalPages, setTaskTotalPages] = useState(0);
  const [tasksError, setTasksError] = useState('');
  const [selectedTaskId, setSelectedTaskId] = useState<number | null>(null);
  const [candidates, setCandidates] = useState<RoutingCandidateSet | null>(null);
  const [candidateState, setCandidateState] = useState<LoadState>('loading');
  const [selectedTeamId, setSelectedTeamId] = useState<number | null>(null);
  const [routeReview, setRouteReview] = useState(false);
  const [coverage, setCoverage] = useState<RoutingCoverage[]>([]);
  const [coverageState, setCoverageState] = useState<LoadState>('loading');
  const [coverageTotalCount, setCoverageTotalCount] = useState(0);
  const [coveragePage, setCoveragePage] = useState(0);
  const [coverageTotalPages, setCoverageTotalPages] = useState(0);
  const [coverageOptions, setCoverageOptions] = useState<RoutingCoverageOptions | null>(null);
  const [cityOptions, setCityOptions] = useState<Array<{ id: number; displayName: string }>>([]);
  const [unclaimed, setUnclaimed] = useState<RoutingUnclaimedWork[]>([]);
  const [unclaimedTotalCount, setUnclaimedTotalCount] = useState(0);
  const [unclaimedPage, setUnclaimedPage] = useState(0);
  const [unclaimedTotalPages, setUnclaimedTotalPages] = useState(0);
  const [unclaimedState, setUnclaimedState] = useState<LoadState>('loading');
  const [selectedCityId, setSelectedCityId] = useState<number | null>(scopes[0]?.cityId ?? null);
  const [selectedLocalityId, setSelectedLocalityId] = useState<number | null>(null);
  const [selectedCoverageTeamId, setSelectedCoverageTeamId] = useState<number | null>(null);
  const [coverageReview, setCoverageReview] = useState(false);
  const [localityCoverage, setLocalityCoverage] = useState<RoutingCoverage[]>([]);
  const [localityCoverageState, setLocalityCoverageState] = useState<LoadState>('loading');
  const [busy, setBusy] = useState<string | null>(null);
  const [message, setMessage] = useState('');
  const [messageTone, setMessageTone] = useState<'info' | 'success' | 'error'>('info');
  const requestGeneration = useRef(0);
  const reloadController = useRef<AbortController | null>(null);
  const taskController = useRef<AbortController | null>(null);
  const coverageController = useRef<AbortController | null>(null);
  const unclaimedController = useRef<AbortController | null>(null);
  const localityCoverageController = useRef<AbortController | null>(null);
  const actionController = useRef<AbortController | null>(null);
  const taskPageGeneration = useRef(0);
  const coveragePageGeneration = useRef(0);
  const unclaimedPageGeneration = useRef(0);
  const localityCoverageGeneration = useRef(0);
  const coverageFormGeneration = useRef(0);
  const tasksPageRef = useRef(0);
  const coveragePageRef = useRef(0);
  const unclaimedPageRef = useRef(0);
  const selectedCityIdRef = useRef(selectedCityId);
  const busyRef = useRef(false);
  const operationSequence = useRef(0);
  const activeRoutingOperation = useRef<RoutingOperation | null>(null);
  const previousAuthority = useRef(authorityFingerprint);

  const selectedTask = tasks.find((task) => task.taskId === selectedTaskId) ?? null;
  const canCoordinate = hasOperationsWorkspaceAccess(capabilities);
  const activeCoverageOverlaps = localityCoverage.filter((entry) =>
    entry.active
    && entry.localityId === selectedLocalityId
    && entry.teamId !== selectedCoverageTeamId);

  const announce = (text: string, tone: 'info' | 'success' | 'error' = 'info') => {
    setMessage(text);
    setMessageTone(tone);
  };

  useEffect(() => {
    selectedCityIdRef.current = selectedCityId;
  }, [selectedCityId]);

  const loadTasks = useCallback(async (
    requestedPage: number,
    session: OperationsSession,
    signal: AbortSignal
  ): Promise<RoutingTask[]> => {
    const generation = ++taskPageGeneration.current;
    setTasksState('loading');
    setTasksError('');
    const fetchPage = async (pageNumber: number): Promise<RoutingTask[]> => {
      const page = await operationsRoutingService.tasks(pageNumber, { session, signal });
      if (signal.aborted || generation !== taskPageGeneration.current
          || !isOperationsSessionActive(session)) return [];
      const reconciled = reconcileRoutingPage(page.page, page.totalPages);
      if (reconciled.reload) {
        tasksPageRef.current = reconciled.page;
        setTasksPage(reconciled.page);
        return fetchPage(reconciled.page);
      }
      setTasks(page.items);
      setTaskTotalCount(page.totalCount);
      setTaskTotalPages(page.totalPages);
      setTasksPage(reconciled.page);
      tasksPageRef.current = reconciled.page;
      setTasksState('ready');
      setSelectedCityId((current) => current
        ?? page.items.find((task) => task.cityId !== null)?.cityId
        ?? null);
      setSelectedTaskId((current) =>
        current !== null && page.items.some((task) => task.taskId === current)
          ? current
          : page.items[0]?.taskId ?? null);
      return page.items;
    };
    try {
      return await fetchPage(Math.max(0, requestedPage));
    } catch (cause) {
      if (signal.aborted || generation !== taskPageGeneration.current
          || isOperationsSessionChangedError(cause)) return [];
      setTasks([]);
      setTaskTotalCount(0);
      setTaskTotalPages(0);
      setTasksState('error');
      setTasksError(cause instanceof ApiRequestError && cause.status === 403
        ? 'Your City routing permission is no longer active.'
        : 'The routing queue could not be read. No routing result is being claimed.');
      return [];
    }
  }, []);

  const loadCoverage = useCallback(async (
    cityId: number | null,
    requestedPage: number,
    session: OperationsSession,
    signal: AbortSignal
  ) => {
    const generation = ++coveragePageGeneration.current;
    setCoverageState('loading');
    const fetchPage = async (pageNumber: number): Promise<void> => {
      const page = await operationsRoutingService.coverage(
        cityId, pageNumber, { session, signal }
      );
      if (signal.aborted || generation !== coveragePageGeneration.current
          || !isOperationsSessionActive(session)) return;
      const reconciled = reconcileRoutingPage(page.page, page.totalPages);
      if (reconciled.reload) {
        coveragePageRef.current = reconciled.page;
        setCoveragePage(reconciled.page);
        await fetchPage(reconciled.page);
        return;
      }
      setCoverage(page.items);
      setCoverageTotalCount(page.totalCount);
      setCoverageTotalPages(page.totalPages);
      setCoveragePage(reconciled.page);
      coveragePageRef.current = reconciled.page;
      setCoverageState('ready');
    };
    try {
      await fetchPage(Math.max(0, requestedPage));
    } catch (cause) {
      if (signal.aborted || generation !== coveragePageGeneration.current
          || isOperationsSessionChangedError(cause)) return;
      setCoverage([]);
      setCoverageTotalCount(0);
      setCoverageTotalPages(0);
      setCoverageState('error');
    }
  }, []);

  const loadUnclaimed = useCallback(async (
    requestedPage: number,
    session: OperationsSession,
    signal: AbortSignal
  ) => {
    const generation = ++unclaimedPageGeneration.current;
    setUnclaimedState('loading');
    const fetchPage = async (pageNumber: number): Promise<void> => {
      const page = await operationsRoutingService.unclaimed(pageNumber, { session, signal });
      if (signal.aborted || generation !== unclaimedPageGeneration.current
          || !isOperationsSessionActive(session)) return;
      const reconciled = reconcileRoutingPage(page.page, page.totalPages);
      if (reconciled.reload) {
        unclaimedPageRef.current = reconciled.page;
        setUnclaimedPage(reconciled.page);
        await fetchPage(reconciled.page);
        return;
      }
      setUnclaimed(page.items);
      setUnclaimedTotalCount(page.totalCount);
      setUnclaimedTotalPages(page.totalPages);
      setUnclaimedPage(reconciled.page);
      unclaimedPageRef.current = reconciled.page;
      setUnclaimedState('ready');
    };
    try {
      await fetchPage(Math.max(0, requestedPage));
    } catch (cause) {
      if (signal.aborted || generation !== unclaimedPageGeneration.current
          || isOperationsSessionChangedError(cause)) return;
      setUnclaimed([]);
      setUnclaimedTotalCount(0);
      setUnclaimedTotalPages(0);
      setUnclaimedState('error');
    }
  }, []);

  const loadCoverageOptions = useCallback(async (
    cityId: number | null,
    session: OperationsSession,
    signal: AbortSignal
  ) => {
    if (cityId === null) {
      setCoverageOptions(null);
      setSelectedLocalityId(null);
      setSelectedCoverageTeamId(null);
      setLocalityCoverage([]);
      setLocalityCoverageState('ready');
      setCoverageReview(false);
      return;
    }
    try {
      const options = await operationsRoutingService.coverageOptions(cityId, { session, signal });
      if (signal.aborted || !isOperationsSessionActive(session)) return;
      setCoverageOptions(options);
      setSelectedLocalityId(options.localities[0]?.id ?? null);
      setSelectedCoverageTeamId(options.teams[0]?.id ?? null);
      setLocalityCoverage([]);
      setLocalityCoverageState('loading');
      setCoverageReview(false);
    } catch (cause) {
      if (signal.aborted || isOperationsSessionChangedError(cause)) return;
      setCoverageOptions(null);
      setSelectedLocalityId(null);
      setSelectedCoverageTeamId(null);
      setLocalityCoverage([]);
      setLocalityCoverageState('error');
    }
  }, []);

  const loadLocalityCoverage = useCallback(async (
    cityId: number,
    localityId: number,
    session: OperationsSession,
    signal: AbortSignal
  ): Promise<RoutingCoverage[] | null> => {
    const generation = ++localityCoverageGeneration.current;
    setLocalityCoverageState('loading');
    try {
      const entries = await operationsRoutingService.activeCoverageForLocality(
        cityId, localityId, { session, signal }
      );
      if (signal.aborted || generation !== localityCoverageGeneration.current
          || !isOperationsSessionActive(session)) return null;
      setLocalityCoverage(entries);
      setLocalityCoverageState('ready');
      return entries;
    } catch (cause) {
      if (signal.aborted || generation !== localityCoverageGeneration.current
          || isOperationsSessionChangedError(cause)) return null;
      setLocalityCoverage([]);
      setLocalityCoverageState('error');
      return null;
    }
  }, []);

  const reload = useCallback(async () => {
    const session = captureOperationsSession();
    if (!session) return;
    const generation = ++requestGeneration.current;
    const controller = new AbortController();
    reloadController.current?.abort();
    reloadController.current = controller;
    try {
      const nextTasks = await loadTasks(tasksPageRef.current, session, controller.signal);
      if (generation !== requestGeneration.current
          || !isOperationsSessionActive(session)) return;
      let nextCityOptions: Array<{ id: number; displayName: string }> = [];
      try {
        const cityPage = await operationsRoutingService.cities({ session, signal: controller.signal });
        nextCityOptions = cityPage.map((city) => ({ id: city.id, displayName: city.displayName }));
        if (generation !== requestGeneration.current || !isOperationsSessionActive(session)) return;
        setCityOptions(nextCityOptions);
      } catch (cause) {
        if (generation !== requestGeneration.current
            || isOperationsSessionChangedError(cause)) return;
        nextCityOptions = scopes
          .filter((scope): scope is { cityId: number; cityName: string } => scope.cityId !== null)
          .map((scope) => ({ id: scope.cityId, displayName: scope.cityName }));
        setCityOptions(nextCityOptions);
      }
      const nextCity = nextTasks.find((task) => task.cityId !== null)?.cityId
        ?? scopes[0]?.cityId ?? nextCityOptions[0]?.id ?? null;
      if (nextCity !== selectedCityIdRef.current) {
        coveragePageRef.current = 0;
        setCoveragePage(0);
      }
      setSelectedCityId(nextCity);
      await Promise.all([
        loadCoverage(nextCity, nextCity === selectedCityIdRef.current ? coveragePageRef.current : 0,
          session, controller.signal),
        loadCoverageOptions(nextCity, session, controller.signal),
        loadUnclaimed(unclaimedPageRef.current, session, controller.signal)
      ]);
    } finally {
      controller.abort();
      if (reloadController.current === controller) reloadController.current = null;
    }
  }, [loadCoverage, loadCoverageOptions, loadTasks, loadUnclaimed, scopes]);

  useEffect(() => () => {
    operationSequence.current += 1;
    activeRoutingOperation.current?.controller.abort();
    activeRoutingOperation.current = null;
    reloadController.current?.abort();
    taskController.current?.abort();
    coverageController.current?.abort();
    unclaimedController.current?.abort();
    localityCoverageController.current?.abort();
    actionController.current?.abort();
  }, []);

  useEffect(() => {
    if (!authority.entryAdmitted) return;
    void reload();
  }, [authority.entryAdmitted, reload]);

  useEffect(() => {
    if (!authority.entryAdmitted || selectedCityId === null) return;
    const session = captureOperationsSession();
    if (!session) return;
    const controller = new AbortController();
    void loadCoverage(selectedCityId, coveragePageRef.current, session, controller.signal);
    void loadCoverageOptions(selectedCityId, session, controller.signal);
    return () => controller.abort();
  }, [authority.entryAdmitted, selectedCityId, loadCoverage, loadCoverageOptions]);

  useEffect(() => {
    if (!authority.entryAdmitted || !selectedTask || selectedTask.cityId === null
      || !selectedTask.initialRoutingEligible) {
      setCandidates(null);
      setSelectedTeamId(null);
      return;
    }
    const session = captureOperationsSession();
    if (!session) return;
    const controller = new AbortController();
    setCandidateState('loading');
    void operationsRoutingService.candidates(
      selectedTask.requestId, selectedTask.cityId, { session, signal: controller.signal }
    ).then((result) => {
      if (controller.signal.aborted || !isOperationsSessionActive(session)) return;
      setCandidates(result);
      setSelectedTeamId(result.teams[0]?.id ?? null);
      setRouteReview(false);
      setCandidateState('ready');
    }).catch((cause) => {
      if (controller.signal.aborted || isOperationsSessionChangedError(cause)) return;
      setCandidates(null);
      setSelectedTeamId(null);
      setCandidateState('error');
    });
    return () => controller.abort();
  }, [authority.entryAdmitted, selectedTask]);

  const clearProtectedRoutingState = useCallback(() => {
    requestGeneration.current += 1;
    taskPageGeneration.current += 1;
    coveragePageGeneration.current += 1;
    unclaimedPageGeneration.current += 1;
    localityCoverageGeneration.current += 1;
    coverageFormGeneration.current += 1;
    operationSequence.current += 1;
    activeRoutingOperation.current?.controller.abort();
    activeRoutingOperation.current = null;
    reloadController.current?.abort();
    taskController.current?.abort();
    coverageController.current?.abort();
    unclaimedController.current?.abort();
    localityCoverageController.current?.abort();
    actionController.current?.abort();
    reloadController.current = null;
    taskController.current = null;
    coverageController.current = null;
    unclaimedController.current = null;
    localityCoverageController.current = null;
    actionController.current = null;
    busyRef.current = false;
    setBusy(null);
    setTasks([]);
    setTaskTotalCount(0);
    setTasksPage(0);
    setTaskTotalPages(0);
    tasksPageRef.current = 0;
    setTasksState('loading');
    setTasksError('');
    setSelectedTaskId(null);
    setCandidates(null);
    setCandidateState('loading');
    setSelectedTeamId(null);
    setRouteReview(false);
    setCoverage([]);
    setCoverageTotalCount(0);
    setCoveragePage(0);
    setCoverageTotalPages(0);
    coveragePageRef.current = 0;
    setCoverageState('loading');
    setCoverageOptions(null);
    setCityOptions([]);
    setSelectedLocalityId(null);
    setSelectedCoverageTeamId(null);
    setCoverageReview(false);
    setLocalityCoverage([]);
    setLocalityCoverageState('loading');
    setUnclaimed([]);
    setUnclaimedTotalCount(0);
    setUnclaimedPage(0);
    setUnclaimedTotalPages(0);
    unclaimedPageRef.current = 0;
    setUnclaimedState('loading');
    setMessage('');
    setMessageTone('info');
  }, []);

  useEffect(() => subscribeOperationsSession(() => {
    clearProtectedRoutingState();
    setSelectedCityId(null);
  }), [clearProtectedRoutingState]);

  useLayoutEffect(() => {
    if (previousAuthority.current === authorityFingerprint) return;
    previousAuthority.current = authorityFingerprint;
    clearProtectedRoutingState();
    setSelectedCityId(scopes[0]?.cityId ?? null);
  }, [authorityFingerprint, clearProtectedRoutingState, scopes]);

  const beginOperation = (label: string): RoutingOperation | null => {
    if (busyRef.current) return null;
    const session = captureOperationsSession();
    const authorityGeneration = authority.authorizationGeneration();
    if (!session || !authority.isDispatchAuthorized(session)) {
      announce('Current City routing access is being checked. No action was sent.', 'error');
      authority.revalidate();
      return null;
    }
    const controller = new AbortController();
    const operation: RoutingOperation = {
      id: ++operationSequence.current,
      label,
      session,
      authorityGeneration,
      controller,
      signal: controller.signal
    };
    busyRef.current = true;
    setBusy(label);
    actionController.current?.abort();
    actionController.current = controller;
    activeRoutingOperation.current = operation;
    return operation;
  };

  const operationIsCurrent = (
    session: OperationsSession,
    authorityGeneration: number
  ): boolean => isOperationsSessionActive(session)
    && authority.authorizationGeneration() === authorityGeneration;

  const finishOperation = (operation: RoutingOperation): void => {
    if (activeRoutingOperation.current !== operation) return;
    activeRoutingOperation.current = null;
    busyRef.current = false;
    setBusy(null);
    actionController.current = null;
  };

  const goToTasksPage = (page: number) => {
    const session = captureOperationsSession();
    if (!session || !authority.isDispatchAuthorized(session) || page < 0) return;
    taskController.current?.abort();
    const controller = new AbortController();
    taskController.current = controller;
    tasksPageRef.current = page;
    setTasksPage(page);
    void loadTasks(page, session, controller.signal);
  };

  const goToCoveragePage = (page: number) => {
    const session = captureOperationsSession();
    if (!session || !authority.isDispatchAuthorized(session) || page < 0
        || selectedCityId === null) return;
    coverageController.current?.abort();
    const controller = new AbortController();
    coverageController.current = controller;
    coveragePageRef.current = page;
    setCoveragePage(page);
    void loadCoverage(selectedCityId, page, session, controller.signal);
  };

  const goToUnclaimedPage = (page: number) => {
    const session = captureOperationsSession();
    if (!session || !authority.isDispatchAuthorized(session) || page < 0) return;
    unclaimedController.current?.abort();
    const controller = new AbortController();
    unclaimedController.current = controller;
    unclaimedPageRef.current = page;
    setUnclaimedPage(page);
    void loadUnclaimed(page, session, controller.signal);
  };

  const reviewCoverage = async () => {
    if (selectedCityId === null || selectedLocalityId === null || !selectedCoverageTeamId) return;
    const session = captureOperationsSession();
    if (!session || !authority.isDispatchAuthorized(session)) {
      announce('Current City routing access is being checked. Coverage was not reviewed.', 'error');
      authority.revalidate();
      return;
    }
    const generation = ++coverageFormGeneration.current;
    const cityId = selectedCityId;
    const localityId = selectedLocalityId;
    localityCoverageController.current?.abort();
    const controller = new AbortController();
    localityCoverageController.current = controller;
    const entries = await loadLocalityCoverage(
      cityId, localityId, session, controller.signal
    );
    if (entries === null || generation !== coverageFormGeneration.current
        || !isOperationsSessionActive(session)) return;
    setCoverageReview(true);
  };

  const routeSelectedTask = async () => {
    if (!selectedTask || !candidates || !selectedTeamId || !selectedTask.cityId
      || !routeReview) return;
    const team = candidates.teams.find((candidate) => candidate.id === selectedTeamId);
    if (!team) return;
    const operation = beginOperation('route');
    if (!operation) return;
    announce('Routing this Request to the selected Team…');
    try {
      await operationsRoutingService.route({
        requestId: selectedTask.requestId,
        expectedRequestVersion: selectedTask.requestVersion,
        cityId: selectedTask.cityId,
        expectedCityVersion: candidates.cityVersion,
        teamId: team.id,
        expectedTeamVersion: team.version,
        expectedRoutingTaskVersion: selectedTask.taskVersion
      }, { session: operation.session, signal: operation.signal });
      if (!operationIsCurrent(operation.session, operation.authorityGeneration)) return;
      announce(`Routed to ${team.displayName}. The Team can now claim the work.`, 'success');
      setRouteReview(false);
      await reload();
    } catch (cause) {
      if (isOperationsSessionChangedError(cause)
          || !operationIsCurrent(operation.session, operation.authorityGeneration)) return;
      if (cause instanceof ApiRequestError && cause.status === 409) {
        announce('The Request, Team or routing task changed. Current routing was reloaded; review before retrying.', 'error');
        await reload();
      } else if (cause instanceof ApiRequestError && (cause.status === 401 || cause.status === 403)) {
        announce('Your City routing permission changed. This action was not confirmed.', 'error');
        authority.revalidate();
      } else {
        try {
          const current = await operationsRoutingService.task(selectedTask.taskId, {
            session: operation.session,
            signal: operation.signal
          });
          if (!operationIsCurrent(operation.session, operation.authorityGeneration)) return;
          if (current.state === 'DONE') {
            announce(`Routed to ${current.resultTeamDisplayName || 'the selected Team'}. The current Team assignment was confirmed.`, 'success');
          } else {
            announce('The routing result is uncertain. Current task state was refreshed; review it before retrying.', 'error');
          }
          await reload();
        } catch (readFailure) {
          if (isOperationsSessionChangedError(readFailure)
              || !operationIsCurrent(operation.session, operation.authorityGeneration)) return;
          announce('The routing result is uncertain and could not be checked. Refresh before trying again.', 'error');
        }
      }
    } finally {
      finishOperation(operation);
    }
  };

  const recheckSelectedTask = async () => {
    if (!selectedTask || selectedTask.state !== 'NEEDS_REVIEW') return;
    const operation = beginOperation('recheck');
    if (!operation) return;
    try {
      await operationsRoutingService.recheck(selectedTask.taskId, selectedTask.taskVersion, {
        session: operation.session,
        signal: operation.signal
      });
      if (!operationIsCurrent(operation.session, operation.authorityGeneration)) return;
      announce('The routing task was queued for a fresh checked decision.', 'success');
      await reload();
    } catch (cause) {
      if (isOperationsSessionChangedError(cause)
          || !operationIsCurrent(operation.session, operation.authorityGeneration)) return;
      announce(cause instanceof ApiRequestError && cause.status === 409
        ? 'The routing task changed. Reload it before checking again.'
        : 'The routing recheck could not be confirmed.', 'error');
    } finally {
      finishOperation(operation);
    }
  };

  const addCoverage = async () => {
    if (!selectedCityId || !selectedLocalityId || !selectedCoverageTeamId
      || !coverageReview || localityCoverageState !== 'ready') return;
    const operation = beginOperation('coverage');
    if (!operation) return;
    try {
      await operationsRoutingService.createCoverage({
        cityId: selectedCityId,
        localityId: selectedLocalityId,
        teamId: selectedCoverageTeamId
      }, { session: operation.session, signal: operation.signal });
      if (!operationIsCurrent(operation.session, operation.authorityGeneration)) return;
      announce('Coverage was saved. Future intake uses the current verified mapping.', 'success');
      setCoverageReview(false);
      await loadCoverage(selectedCityId, coveragePageRef.current, operation.session, operation.signal);
    } catch (cause) {
      if (isOperationsSessionChangedError(cause)
          || !operationIsCurrent(operation.session, operation.authorityGeneration)) return;
      announce(cause instanceof ApiRequestError && cause.status === 409
        ? 'Coverage for this Team and locality already exists. Retired coverage must be restored explicitly.'
        : 'Coverage was not confirmed. Reload and review the current mappings.', 'error');
    } finally {
      finishOperation(operation);
    }
  };

  const changeCoverage = async (entry: RoutingCoverage) => {
    const operation = beginOperation(`coverage-${entry.id}`);
    if (!operation || selectedCityId === null) return;
    try {
      await operationsRoutingService.changeCoverageState(
        entry.id, entry.version, !entry.active,
        { session: operation.session, signal: operation.signal }
      );
      if (!operationIsCurrent(operation.session, operation.authorityGeneration)) return;
      announce(entry.active ? 'Coverage was retired.' : 'Coverage was restored.', 'success');
      await loadCoverage(selectedCityId, coveragePageRef.current, operation.session, operation.signal);
    } catch (cause) {
      if (isOperationsSessionChangedError(cause)
          || !operationIsCurrent(operation.session, operation.authorityGeneration)) return;
      announce('Coverage changed or the request could not be confirmed. Reload before retrying.', 'error');
    } finally {
      finishOperation(operation);
    }
  };

  const authorityView = resolveOperationsAuthorityView(authority);
  if (authorityView === 'restricted') {
    return <RoutingShell title="City routing is unavailable" message="Your account does not have a current City routing permission." />;
  }
  if (authorityView === 'unavailable') {
    return (
      <RoutingShell title="City routing could not be verified"
        message="Protected routing data remains hidden until current access can be checked."
        action={<button type="button" className="routing-button" onClick={authority.revalidate}>Retry access check</button>} />
    );
  }
  if (authorityView === 'checking') {
    return <RoutingShell title="City routing" message="Checking your current City routing access…" />;
  }

  return (
    <main className="routing-page" aria-labelledby="routing-title">
      <div className="routing-shell">
        <header className="routing-header">
          <div>
            <p className="routing-eyebrow"><Route size={16} aria-hidden="true" /> Operations</p>
            <h1 id="routing-title">City routing and coverage</h1>
            <p>Review safe routing exceptions, choose a verified Team, and maintain locality coverage.</p>
          </div>
          <div className="routing-header-actions">
            {canCoordinate && (
              <button type="button" className="routing-button routing-button--quiet"
                onClick={() => navigate('/operations')}>
                <ArrowLeft size={16} aria-hidden="true" /> My work
              </button>
            )}
            <button type="button" className="routing-button routing-button--quiet"
              onClick={() => void reload()} disabled={busy !== null}>
              <RefreshCw size={16} aria-hidden="true" /> Refresh
            </button>
          </div>
        </header>

        <div className="routing-summary" aria-label="Current routing summary">
          <div><span>Tracked Requests</span><strong>{tasksState === 'ready' ? taskTotalCount : '—'}</strong></div>
          <div><span>Exceptions on this page</span><strong>{tasks.filter((task) => task.state === 'NEEDS_REVIEW').length}</strong></div>
          <div><span>Coverage in selected City</span><strong>{coverageState === 'ready' ? coverageTotalCount : '—'}</strong></div>
        </div>

        <div className="routing-notice" role="status" aria-live="polite" data-tone={messageTone}>
          {message || (tasksState === 'ready' && tasks.length === 0
            ? 'There are no pending routing tasks in your current City scope.'
            : 'Routing status is loaded from the server.')}
        </div>

        <div className="routing-grid">
          <section className="routing-panel" aria-labelledby="routing-queue-title">
            <div className="routing-panel-heading">
              <div>
                <h2 id="routing-queue-title">Routing queue</h2>
                <p>Pending and exception records in your current City scope.</p>
              </div>
              <span className="routing-chip">{tasks.length} shown</span>
            </div>
            {tasksState === 'loading' && <div className="routing-empty"><Clock3 /> Loading routing work…</div>}
            {tasksState === 'error' && (
              <div className="routing-empty routing-empty--error">
                <CircleAlert />
                <p>{tasksError}</p>
                <button type="button" className="routing-button" onClick={() => void reload()}>Try again</button>
              </div>
            )}
            {tasksState === 'ready' && tasks.length === 0 && (
              <div className="routing-empty"><CheckCircle2 /><p>No Requests are waiting for routing in this scope.</p></div>
            )}
            <div className="routing-list">
              {tasks.map((task) => (
                <button key={task.taskId} type="button"
                  className={`routing-row${task.taskId === selectedTaskId ? ' routing-row--selected' : ''}`}
                  onClick={() => {
                    setSelectedTaskId(task.taskId);
                    setSelectedCityId(task.cityId ?? selectedCityId);
                    setRouteReview(false);
                    setMessage('');
                    setMessageTone('info');
                  }}>
                  <span className="routing-row-main">
                    <strong>{task.listingTitle || `Visit Request #${task.requestId}`}</strong>
                    <span><MapPin size={14} aria-hidden="true" /> {task.localityDisplayName || 'Locality unavailable'} · {task.cityDisplayName || 'City unresolved'}</span>
                  </span>
                  <span className={`routing-state routing-state--${task.state.toLowerCase()}`}>
                    {stateLabel(task.state)}
                  </span>
                  <span className="routing-row-time">{formatDate(task.updatedAt)}</span>
                </button>
              ))}
            </div>
            <PageControls page={tasksPage} totalPages={taskTotalPages}
              label="Routing queue" disabled={busy !== null} onPage={goToTasksPage} />
          </section>

          <section className="routing-panel" aria-labelledby="routing-detail-title">
            <div className="routing-panel-heading">
              <div>
                <h2 id="routing-detail-title">Routing decision</h2>
                <p>Choose a Team only after checking the Request and locality.</p>
              </div>
              {selectedTask && <span className="routing-chip">Request #{selectedTask.requestId}</span>}
            </div>
            {!selectedTask && <div className="routing-empty"><Route /><p>Select a Request to review routing.</p></div>}
            {selectedTask && (
              <div className="routing-detail">
                <dl>
                  <div><dt>Property</dt><dd>{selectedTask.listingTitle || 'Title unavailable'}</dd></div>
                  <div><dt>City</dt><dd>{selectedTask.cityDisplayName || 'Unresolved'}</dd></div>
                  <div><dt>Locality</dt><dd>{selectedTask.localityDisplayName || 'Unresolved'}</dd></div>
                  <div><dt>Status</dt><dd>{stateLabel(selectedTask.state)}</dd></div>
                </dl>
                {selectedTask.state !== 'PENDING' && selectedTask.state !== 'RETRY' && (
                  <p className="routing-reason"><CircleAlert size={17} aria-hidden="true" /> {reasonLabel(selectedTask.reasonCode)}</p>
                )}
                {selectedTask.initialRoutingEligible && selectedTask.cityId ? (
                  <>
                    {candidateState === 'loading' && <p className="routing-muted">Loading active Teams…</p>}
                    {candidateState === 'error' && (
                      <p className="routing-reason"><CircleAlert size={17} /> Team candidates could not be read. Refresh before routing.</p>
                    )}
                    {candidateState === 'ready' && candidates && candidates.teams.length === 0 && (
                      <p className="routing-reason"><Users size={17} /> No active Team is available in this City.</p>
                    )}
                    {candidateState === 'ready' && candidates && candidates.teams.length > 0 && (
                      <div className="routing-form">
                        <label htmlFor="routing-team">Route to Team</label>
                        <select id="routing-team" value={selectedTeamId ?? ''}
                          onChange={(event) => {
                            setSelectedTeamId(Number(event.target.value));
                            setRouteReview(false);
                          }}>
                          {candidates.teams.map((team) => (
                            <option key={team.id} value={team.id}>{team.displayName}</option>
                          ))}
                        </select>
                        <p>Routing makes the Request available for an eligible Team member to claim. It does not book a Visit or assign a person.</p>
                        {!routeReview ? (
                          <button type="button" className="routing-button routing-button--primary"
                            disabled={busy !== null || selectedTeamId === null}
                            onClick={() => setRouteReview(true)}>
                            Review Team selection
                          </button>
                        ) : (
                          <div className="routing-review">
                            <strong>Confirm Team routing</strong>
                            <p>
                              Route {selectedTask.listingTitle || `Request #${selectedTask.requestId}`}
                              {' '}to {candidates.teams.find((team) => team.id === selectedTeamId)?.displayName}.
                              The Team will see the work; no person is assigned and no Visit is booked.
                            </p>
                            <div>
                              <button type="button" className="routing-button routing-button--primary"
                                disabled={busy !== null} onClick={() => void routeSelectedTask()}>
                                {busy === 'route' ? 'Routing…' : 'Confirm Team routing'}
                              </button>
                              <button type="button" className="routing-button routing-button--quiet"
                                onClick={() => setRouteReview(false)}>Cancel</button>
                            </div>
                          </div>
                        )}
                      </div>
                    )}
                    {selectedTask.state === 'NEEDS_REVIEW' && (
                      <button type="button" className="routing-button routing-button--quiet"
                        disabled={busy !== null} onClick={() => void recheckSelectedTask()}>
                        {busy === 'recheck' ? 'Checking…' : 'Check routing again'}
                      </button>
                    )}
                  </>
                ) : (
                  <p className="routing-muted">This Request is no longer eligible for initial Team routing.</p>
                )}
              </div>
            )}
          </section>
        </div>

        <section className="routing-panel routing-coverage" aria-labelledby="coverage-title">
          <div className="routing-panel-heading">
            <div>
              <h2 id="coverage-title">Verified locality coverage</h2>
              <p>Only active same-City mappings can make a Request eligible for automatic routing.</p>
            </div>
            {(cityOptions.length > 1 || scopes.length > 1) && (
              <label htmlFor="routing-city-filter" className="routing-city-picker">
                <span>City</span>
                <select id="routing-city-filter" value={selectedCityId ?? ''}
                  onChange={(event) => {
                    coveragePageRef.current = 0;
                    setCoveragePage(0);
                    setCoverageReview(false);
                    coverageFormGeneration.current += 1;
                    setSelectedCityId(Number(event.target.value));
                  }}>
                  {(cityOptions.length > 0 ? cityOptions : scopes.map((scope) => ({
                    id: scope.cityId,
                    displayName: scope.cityName
                  }))).map((city) => (
                    <option key={city.id ?? city.displayName} value={city.id ?? ''}>
                      {city.displayName}
                    </option>
                  ))}
                </select>
              </label>
            )}
          </div>
          {coverageOptions && (
            <div className="routing-coverage-form">
              <label htmlFor="coverage-locality">
                <span>Locality</span>
                <select id="coverage-locality" value={selectedLocalityId ?? ''}
                  onChange={(event) => {
                    coverageFormGeneration.current += 1;
                    setSelectedLocalityId(Number(event.target.value));
                    setCoverageReview(false);
                    setLocalityCoverage([]);
                    setLocalityCoverageState('loading');
                  }}>
                  {coverageOptions.localities.map((option) => (
                    <option key={option.id} value={option.id}>{option.displayName}</option>
                  ))}
                </select>
              </label>
              <label htmlFor="coverage-team">
                <span>Team</span>
                <select id="coverage-team" value={selectedCoverageTeamId ?? ''}
                  onChange={(event) => {
                    coverageFormGeneration.current += 1;
                    setSelectedCoverageTeamId(Number(event.target.value));
                    setCoverageReview(false);
                  }}>
                  {coverageOptions.teams.map((option) => (
                    <option key={option.id} value={option.id}>{option.displayName}</option>
                  ))}
                </select>
              </label>
              {!coverageReview ? (
                <button type="button" className="routing-button routing-button--primary"
                  disabled={busy !== null || !selectedLocalityId || !selectedCoverageTeamId}
                  onClick={() => void reviewCoverage()}>
                  Review coverage
                </button>
              ) : (
                <button type="button" className="routing-button routing-button--primary"
                  disabled={busy !== null || localityCoverageState !== 'ready'
                    || !selectedLocalityId || !selectedCoverageTeamId}
                  onClick={() => void addCoverage()}>
                  {busy === 'coverage' ? 'Saving…' : 'Confirm coverage'}
                </button>
              )}
            </div>
          )}
          {coverageReview && localityCoverageState === 'loading' && (
            <p className="routing-muted">Checking every active mapping for this locality…</p>
          )}
          {coverageReview && localityCoverageState === 'error' && (
            <p className="routing-reason">
              <CircleAlert size={17} />
              Existing coverage for this locality could not be verified. Confirm is unavailable.
            </p>
          )}
          {coverageReview && activeCoverageOverlaps.length > 0 && (
            <p className="routing-reason">
              <CircleAlert size={17} />
              This locality already has active coverage from {activeCoverageOverlaps.map((entry) => entry.teamDisplayName).join(', ')}.
              Adding this Team creates an overlap, so automatic routing will stop and require an exception decision.
            </p>
          )}
          {coverageState === 'loading' && <p className="routing-muted">Loading coverage…</p>}
          {coverageState === 'error' && <p className="routing-reason"><CircleAlert size={17} /> Coverage could not be loaded.</p>}
          {coverageState === 'ready' && coverage.length === 0 && (
            <p className="routing-muted">No coverage mappings exist in this City.</p>
          )}
          <div className="routing-coverage-list">
            {coverage.map((entry) => (
              <article key={entry.id} className={entry.active ? '' : 'routing-coverage--inactive'}>
                <div>
                  <strong>{entry.localityDisplayName}</strong>
                  <span>{entry.teamDisplayName}</span>
                </div>
                <span className={`routing-state ${entry.active ? 'routing-state--done' : 'routing-state--skipped'}`}>
                  {entry.active ? 'Active' : 'Retired'}
                </span>
                <button type="button" className="routing-button routing-button--quiet"
                  disabled={busy !== null} onClick={() => void changeCoverage(entry)}>
                  {entry.active ? 'Retire' : 'Restore'}
                </button>
              </article>
            ))}
          </div>
          <PageControls page={coveragePage} totalPages={coverageTotalPages}
            label="Coverage" disabled={busy !== null} onPage={goToCoveragePage} />
        </section>

        <section className="routing-panel routing-coverage" aria-labelledby="unclaimed-title">
          <div className="routing-panel-heading">
            <div>
              <h2 id="unclaimed-title">Unclaimed Team work</h2>
              <p>Current unassigned Team work in your City scope. This overview does not claim or reassign work.</p>
            </div>
            <span className="routing-chip">
              {unclaimedState === 'ready' ? `${unclaimedTotalCount} total` : '—'}
            </span>
          </div>
          {unclaimedState === 'loading' && <p className="routing-muted">Loading unclaimed Team work…</p>}
          {unclaimedState === 'error' && (
            <p className="routing-reason"><CircleAlert size={17} /> Unclaimed Team work could not be read.</p>
          )}
          {unclaimedState === 'ready' && unclaimed.length === 0 && (
            <p className="routing-muted">No unclaimed Team work is currently visible in this scope.</p>
          )}
          <div className="routing-coverage-list">
            {unclaimed.map((entry) => (
              <article key={`${entry.targetType}-${entry.targetId}`}>
                <div>
                  <strong>{entry.targetType === 'REQUEST' ? 'Visit Request' : 'Visit plan'} #{entry.targetId}</strong>
                  <span>{entry.cityDisplayName} · {entry.teamDisplayName} · {entry.status}</span>
                </div>
                <span className="routing-row-time">{formatDate(entry.createdAt)}</span>
              </article>
            ))}
          </div>
          <PageControls page={unclaimedPage} totalPages={unclaimedTotalPages}
            label="Unclaimed Team work" disabled={busy !== null} onPage={goToUnclaimedPage} />
        </section>
      </div>
    </main>
  );
};

const RoutingShell: React.FC<{
  title: string;
  message: string;
  action?: React.ReactNode;
}> = ({ title, message, action }) => (
  <main className="routing-page">
    <section className="routing-status-card" aria-live="polite">
      <ShieldCheck size={24} aria-hidden="true" />
      <h1>{title}</h1>
      <p>{message}</p>
      {action}
    </section>
  </main>
);
