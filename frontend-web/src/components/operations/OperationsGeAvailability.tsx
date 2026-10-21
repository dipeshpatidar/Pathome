import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { CircleAlert, Info, Inbox, Lock, RefreshCw, Search, X } from 'lucide-react';
import { ApiRequestError } from '../../services/apiError';
import {
  formatOperationsAvailabilityDateTime,
  formatOperationsAvailabilityWindow,
  formatOperationsDetailDateTime,
  formatOperationsDuration,
  geAvailabilityResultCopy,
  operationsRecordReference,
  recommendationStatusHint,
  type OperationsGeAvailabilityEntry,
  type OperationsGeAvailabilityStop,
  type OperationsGeAvailabilityTravelAllowance,
  type OperationsGeAvailabilityView
} from '../../utils/operationsWorkspacePresentation';

export type GeAvailabilityLoader = (
  input: { q: string | null; cursor: string | null; size?: number },
  signal: AbortSignal
) => Promise<OperationsGeAvailabilityView | null>;

interface Props {
  sessionId: number;
  sessionVersion: number;
  city: string;
  loadAvailability: GeAvailabilityLoader;
  onClose: () => void;
  /** A 409 means this evaluation no longer describes the authoritative Session. */
  onRequireFreshSession: () => void;
}

type AvailabilityFailure = 'none' | 'denied' | 'stale' | 'transient' | 'session-ended';

interface AvailabilityState {
  status: 'loading' | 'ready' | 'error';
  view: OperationsGeAvailabilityView | null;
  items: OperationsGeAvailabilityEntry[];
  cursor: string | null;
  ended: boolean;
  more: 'idle' | 'loading' | 'error';
  cursorRejected: boolean;
  mismatch: boolean;
  failure: AvailabilityFailure;
}

const EMPTY_STATE: AvailabilityState = {
  status: 'loading',
  view: null,
  items: [],
  cursor: null,
  ended: false,
  more: 'idle',
  cursorRejected: false,
  mismatch: false,
  failure: 'none'
};

const PAGE_SIZE = 20;
const FOCUSABLE =
  'button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), a[href], summary, [tabindex]:not([tabindex="-1"])';

const isRejectedCursorFailure = (cause: unknown): boolean =>
  cause instanceof ApiRequestError && cause.status === 400;

const planHintFor = (view: OperationsGeAvailabilityView | null): string | null => {
  if (!view) return null;
  if (view.planExplanationCodes.includes('ITINERARY_EXCEEDS_SESSION_DURATION_POLICY')) {
    return 'The configured dwell and between-stop travel total exceeds the maximum visit plan duration.';
  }
  if (view.planExplanationCodes.includes('TENANT_WINDOW_EXPIRED')) {
    return 'The tenant’s available window is already in the past, so no new visit time can be evaluated inside it.';
  }
  return recommendationStatusHint(view.planStatus);
};

const Fact: React.FC<{ label: string; children: React.ReactNode; mono?: boolean }> = ({
  label,
  children,
  mono = false
}) => (
  <div className="oe-gea-fact">
    <dt>{label}</dt>
    <dd className={mono ? 'oe-mono' : undefined}>{children}</dd>
  </div>
);

const StopWindowRow: React.FC<{ stop: OperationsGeAvailabilityStop }> = ({ stop }) => {
  const window = formatOperationsAvailabilityWindow(stop.availabilityStartAt, stop.availabilityEndAt, stop.zoneId);
  return (
    <li className="oe-gea-stop">
      <div className="oe-gea-stop-head">
        <span className="oe-gea-stop-num oe-num" aria-hidden="true">{stop.position}</span>
        <span className="oe-gea-stop-title">
          {stop.listingId === null
            ? 'Property not recorded'
            : `Property ${operationsRecordReference(stop.listingId)}`}
        </span>
        <span className={`oe-chip${stop.confirmationStatus === 'CONFIRMED' ? ' oe-chip--pine' : ''}`}>
          {stop.confirmationStatus === 'CONFIRMED'
            ? 'Lessor-confirmed'
            : stop.confirmationStatus ?? 'Confirmation not returned'}
        </span>
      </div>
      <p className="oe-gea-stop-line">
        {window ?? 'No complete lessor-recorded availability window.'}
      </p>
      <p className="oe-gea-stop-meta">
        {stop.availabilitySource
          ? `Source: ${stop.availabilitySource.replace(/_/g, ' ')}`
          : 'Source not recorded'}
        {stop.availabilityConfirmedAt
          ? ` · Recorded ${formatOperationsAvailabilityDateTime(stop.availabilityConfirmedAt, stop.zoneId)
            ?? 'time not available'}`
          : ''}
        {stop.offsetMinutes !== null ? ` · Starts ${stop.offsetMinutes} min after the visit begins` : ''}
      </p>
    </li>
  );
};

const TravelRow: React.FC<{ leg: OperationsGeAvailabilityTravelAllowance }> = ({ leg }) => (
  <li className="oe-gea-travel">
    <span className="oe-gea-travel-route">
      Stop {leg.fromPosition} → Stop {leg.toPosition}
    </span>
    <span className="oe-gea-travel-value">{formatOperationsDuration(leg.minutes) ?? `${leg.minutes} min`}</span>
    <span className="oe-gea-travel-meta">
      {leg.source ? leg.source.replace(/_/g, ' ').toLowerCase() : 'source not returned'}
      {leg.confidence ? ` · ${leg.confidence.toLowerCase()} confidence` : ''}
    </span>
  </li>
);

export const OperationsGeAvailability: React.FC<Props> = ({
  sessionId,
  sessionVersion,
  city,
  loadAvailability,
  onClose,
  onRequireFreshSession
}) => {
  const [state, setState] = useState<AvailabilityState>(EMPTY_STATE);
  const [query, setQuery] = useState('');
  const [ranQuery, setRanQuery] = useState('');
  const controller = useRef<AbortController | null>(null);
  const generation = useRef(0);
  const moreInFlight = useRef(false);
  const debounce = useRef<number | undefined>(undefined);
  const dialogRef = useRef<HTMLDivElement | null>(null);
  const headingRef = useRef<HTMLHeadingElement | null>(null);
  const searchInput = useRef<HTMLInputElement | null>(null);
  const contextKey = `${sessionId}|${sessionVersion}`;
  const lastContext = useRef(contextKey);

  const matchesContext = useCallback(
    (view: OperationsGeAvailabilityView | null): view is OperationsGeAvailabilityView =>
      view !== null && view.sessionId === sessionId && view.sessionVersion === sessionVersion,
    [sessionId, sessionVersion]
  );

  const load = useCallback(async (
    requestedQuery: string,
    requestedCursor: string | null,
    reset: boolean
  ): Promise<number> => {
    controller.current?.abort();
    const nextController = new AbortController();
    controller.current = nextController;
    const ownGeneration = ++generation.current;
    setState((current) => ({
      ...current,
      status: reset ? 'loading' : current.status,
      items: reset ? [] : current.items,
      view: reset ? null : current.view,
      cursor: reset ? null : current.cursor,
      ended: reset ? false : current.ended,
      more: reset ? 'idle' : current.more,
      cursorRejected: false,
      mismatch: false,
      failure: 'none'
    }));
    try {
      const page = await loadAvailability({
        q: requestedQuery || null,
        cursor: requestedCursor,
        size: PAGE_SIZE
      }, nextController.signal);
      if (nextController.signal.aborted || ownGeneration !== generation.current) return ownGeneration;
      if (page === null) {
        /*
         * The workspace refused to publish this answer because the captured identity, authority or
         * mounted Session instance moved on. Clear the protected view rather than leaving a
         * perpetual skeleton; there is no authorized page to show.
         */
        setState({
          ...EMPTY_STATE,
          status: 'error',
          failure: 'stale'
        });
        return ownGeneration;
      }
      if (!matchesContext(page)) {
        setState((current) => ({
          ...current,
          status: 'ready',
          view: null,
          items: [],
          cursor: null,
          ended: true,
          mismatch: true,
          failure: 'none'
        }));
        return ownGeneration;
      }
      setState((current) => {
        const base = reset ? [] : current.items;
        const seen = new Set(base.map((item) => item.groundExecutiveUserId));
        return {
          status: 'ready',
          view: page,
          items: [...base, ...page.entries.filter((item) => !seen.has(item.groundExecutiveUserId))],
          cursor: page.nextCursor,
          ended: page.nextCursor === null,
          more: 'idle',
          cursorRejected: false,
          mismatch: false,
          failure: 'none'
        };
      });
      return ownGeneration;
    } catch (cause) {
      if (nextController.signal.aborted || ownGeneration !== generation.current) return ownGeneration;
      const status = cause instanceof ApiRequestError ? cause.status ?? null : null;
      if (requestedCursor !== null && isRejectedCursorFailure(cause)) {
        setState((current) => ({
          ...current,
          status: current.items.length > 0 ? 'ready' : 'error',
          cursor: null,
          cursorRejected: true,
          more: 'idle'
        }));
        return ownGeneration;
      }
      if (status === 403 || status === 404) {
        // Definitive authorization/root denial: no roster, timing, cursor or stale page survives.
        setState({ ...EMPTY_STATE, status: 'error', failure: 'denied' });
        return ownGeneration;
      }
      if (status === 409) {
        // The evaluation is stale. Nothing from it may be booked or retained; re-read the Session.
        setState({ ...EMPTY_STATE, status: 'error', failure: 'stale' });
        return ownGeneration;
      }
      if (status === 401) {
        // Existing sign-in handling owns the redirect; this view only stops exposing protected data.
        setState({ ...EMPTY_STATE, status: 'error', failure: 'session-ended' });
        return ownGeneration;
      }
      setState((current) => ({
        ...current,
        status: reset ? 'error' : current.status,
        more: reset ? 'idle' : 'error',
        failure: reset ? 'transient' : current.failure
      }));
      return ownGeneration;
    }
  }, [loadAvailability, matchesContext]);

  useEffect(() => {
    setState(EMPTY_STATE);
    setQuery('');
    setRanQuery('');
    lastContext.current = contextKey;
    void load('', null, true);
    return () => controller.current?.abort();
  }, [contextKey, load]);

  useEffect(() => {
    if (lastContext.current === contextKey) return;
    lastContext.current = contextKey;
    window.clearTimeout(debounce.current);
    setQuery('');
    setRanQuery('');
    setState(EMPTY_STATE);
    void load('', null, true);
  }, [contextKey, load]);

  useEffect(() => () => {
    window.clearTimeout(debounce.current);
    controller.current?.abort();
  }, []);

  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    const overflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    headingRef.current?.focus();
    const onFocusIn = (event: FocusEvent) => {
      if (dialogRef.current && event.target instanceof Node && !dialogRef.current.contains(event.target)) {
        headingRef.current?.focus();
      }
    };
    document.addEventListener('focusin', onFocusIn);
    return () => {
      document.removeEventListener('focusin', onFocusIn);
      document.body.style.overflow = overflow;
      previous?.focus();
    };
  }, []);

  const onKeyDown = (event: React.KeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'Escape') {
      event.preventDefault();
      event.stopPropagation();
      onClose();
      return;
    }
    if (event.key !== 'Tab' || !dialogRef.current) return;
    const nodes = [...dialogRef.current.querySelectorAll<HTMLElement>(FOCUSABLE)]
      .filter((node) => node.getClientRects().length > 0);
    if (nodes.length === 0) return;
    const first = nodes[0];
    const last = nodes[nodes.length - 1];
    const active = document.activeElement as HTMLElement | null;
    const index = active ? nodes.indexOf(active) : -1;
    if (index === -1) {
      event.preventDefault();
      (event.shiftKey ? last : first).focus();
    } else if (event.shiftKey && index === 0) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && index === nodes.length - 1) {
      event.preventDefault();
      first.focus();
    }
  };

  const runSearch = (value: string) => {
    window.clearTimeout(debounce.current);
    controller.current?.abort();
    generation.current += 1;
    const trimmed = value.trim();
    setRanQuery(trimmed);
    setState((current) => ({
      ...EMPTY_STATE,
      status: 'loading',
      items: [],
      view: current.view
    }));
    debounce.current = window.setTimeout(() => {
      void load(trimmed, null, true);
    }, 400);
  };

  const loadMore = async () => {
    if (!state.cursor || moreInFlight.current) return;
    moreInFlight.current = true;
    const ownGeneration = generation.current + 1;
    setState((current) => ({ ...current, more: 'loading' }));
    try {
      await load(ranQuery, state.cursor, false);
    } finally {
      moreInFlight.current = false;
      // A finalizer from a superseded context must not clear a newer page's loading state.
      if (generation.current === ownGeneration) {
        setState((current) => current.more === 'loading' ? { ...current, more: 'idle' } : current);
      }
    }
  };

  const view = state.view;
  const timing = view?.timing ?? null;
  const planHint = useMemo(() => planHintFor(view), [view]);
  const tenantWindow = timing?.tenantWindow ?? null;
  const preferredTimes = tenantWindow?.preferredTimes ?? [];
  const stops = timing?.stops ?? [];
  const travel = timing?.betweenStopTravel ?? [];
  const loadedCount = state.items.length;
  const availabilityClosed = state.failure === 'denied'
    || state.failure === 'session-ended'
    || state.failure === 'stale';

  return (
    <div className="oe-gea-scrim">
      <div
        ref={dialogRef}
        className="oe-gea"
        role="dialog"
        aria-modal="true"
        aria-labelledby="oe-gea-title"
        tabIndex={-1}
        onKeyDown={onKeyDown}
      >
        <header className="oe-gea-head">
          <div className="oe-gea-headtext">
            <h2 id="oe-gea-title" ref={headingRef} tabIndex={-1} className="oe-gea-title">
              GE availability
            </h2>
            <p className="oe-gea-sub">
              <span className="oe-mono">{operationsRecordReference(sessionId)}</span>
              {` · ${city}`}
            </p>
          </div>
          <button type="button" className="oe-icon-btn" aria-label="Close GE availability" onClick={onClose}>
            <X size={18} aria-hidden="true" />
          </button>
        </header>

        <div className="oe-gea-body">
          <div className="oe-gea-info" role="note">
            <Info size={18} aria-hidden="true" />
            <p>
              This is an informational view of potential City scheduling candidates. A feasible row
              is not a booking and does not promise the same time later. To book, choose a freshly
              evaluated option in Browse feasible GEs.
            </p>
          </div>

          {view && !view.planReady && planHint ? (
            <div className="oe-notice oe-notice--warn" role="alert">
              <span className="oe-notice-icon" aria-hidden="true"><Lock size={18} /></span>
              <div>
                <p className="oe-notice-title">This visit plan is not ready for a reliable evaluation</p>
                <p className="oe-notice-body">{planHint}</p>
              </div>
            </div>
          ) : null}

          {state.status === 'loading' && view === null ? (
            <div className="oe-gea-skeleton" aria-busy="true" aria-label="Loading GE availability">
              <span className="oe-skel" style={{ width: '45%' }} />
              <span className="oe-skel" style={{ width: '80%' }} />
              <span className="oe-skel" style={{ width: '65%' }} />
            </div>
          ) : null}

          {view && timing ? (
            <section className="oe-gea-panel" aria-labelledby="oe-gea-plan-title">
              <div className="oe-gea-panel-head">
                <h3 id="oe-gea-plan-title" className="oe-gea-panel-title">This visit plan</h3>
                <span className="oe-gea-policy">
                  {view.schedulingPolicyVersion ? `Scheduling policy ${view.schedulingPolicyVersion}` : 'Policy version not returned'}
                  {view.visitPolicyVersion !== null ? ` · Visit policy v${view.visitPolicyVersion}` : ''}
                </span>
              </div>

              <dl className="oe-gea-facts">
                <Fact label="Tenant available window">
                  {tenantWindow && formatOperationsAvailabilityWindow(tenantWindow.startAt, tenantWindow.endAt, tenantWindow.zoneId)
                    ? formatOperationsAvailabilityWindow(tenantWindow.startAt, tenantWindow.endAt, tenantWindow.zoneId)
                    : 'Not complete or not recorded'}
                </Fact>
                <Fact label="Tenant preference">
                  {preferredTimes.length > 0
                    ? preferredTimes
                      .map((value) => formatOperationsAvailabilityDateTime(value, tenantWindow?.zoneId)
                        ?? 'Time not available')
                      .join(' · ')
                    : 'None recorded'}
                </Fact>
                <Fact label="Configured dwell per stop">
                  {formatOperationsDuration(timing.configuredStopDwellMinutes) ?? 'Not returned'}
                </Fact>
                <Fact label="Calculated itinerary duration">
                  {formatOperationsDuration(timing.calculatedItineraryDurationMinutes) ?? 'Not available'}
                </Fact>
                <Fact label="Maximum visit plan duration">
                  {formatOperationsDuration(timing.maximumSessionDurationMinutes) ?? 'Not returned'}
                </Fact>
                <Fact label="Evaluated at">
                  {formatOperationsDetailDateTime(view.evaluatedAt) ?? 'Not returned'}
                </Fact>
              </dl>

              <h4 className="oe-gea-subtitle">Property windows</h4>
              {stops.length > 0 ? (
                <ol className="oe-gea-stops">
                  {stops.map((stop) => <StopWindowRow key={`${stop.position}-${stop.itemId ?? 'none'}`} stop={stop} />)}
                </ol>
              ) : (
                <p className="oe-gea-empty-inline">No active stops were returned.</p>
              )}

              <h4 className="oe-gea-subtitle">Between-property allowances</h4>
              {travel.length > 0 ? (
                <ul className="oe-gea-travels">
                  {travel.map((leg) => (
                    <TravelRow key={`${leg.fromPosition}-${leg.toPosition}`} leg={leg} />
                  ))}
                </ul>
              ) : (
                <p className="oe-gea-empty-inline">
                  {stops.length <= 1
                    ? 'No between-stop travel applies to a single-stop plan.'
                    : 'Between-stop allowances were not returned for this evaluation.'}
                </p>
              )}

              <p className="oe-gea-footnote">
                Calculated duration = configured dwell for each stop + the between-stop travel
                allowances shown here. First-stop arrival travel and later-commitment protection are
                checked separately and are not included in this total. Travel allowances are planning
                values, not a live ETA.
              </p>

              {view.evaluationBounded || timing.planningWindowBounded ? (
                <p className="oe-gea-footnote oe-gea-footnote--warn">
                  {view.evaluationBounded
                    ? 'The server reported a bounded candidate search, so this is not an organization-wide conclusion. '
                    : ''}
                  {timing.planningWindowBounded
                    ? 'The tenant window is longer than the configured planning horizon, so only its first bounded part is evaluated.'
                    : ''}
                </p>
              ) : null}

              {view.planInputFingerprint ? (
                <details className="oe-gea-fingerprint">
                  <summary>Plan snapshot reference</summary>
                  <code>{view.planInputFingerprint}</code>
                </details>
              ) : null}
            </section>
          ) : null}

          <section className="oe-gea-panel" aria-labelledby="oe-gea-roster-title">
            <div className="oe-gea-panel-head">
              <h3 id="oe-gea-roster-title" className="oe-gea-panel-title">Potential City scheduling candidates</h3>
              <span className="oe-gea-count" aria-live="polite">
                {loadedCount} shown
              </span>
            </div>
            <p className="oe-gea-hint">
              Only entries loaded in this view are counted. The server does not return an
              organization-wide total, and this list is not a staff roster.
            </p>

            <label htmlFor="oe-gea-search" className="oe-gea-search-label">
              Search this City scope by partial name or exact user ID
            </label>
            <div className="oe-search oe-search--panel">
              <Search size={16} aria-hidden="true" />
              <input
                ref={searchInput}
                id="oe-gea-search"
                className="oe-search-input"
                type="text"
                inputMode="search"
                autoComplete="off"
                maxLength={80}
                placeholder="e.g. Rahul or 4207"
                disabled={availabilityClosed}
                value={query}
                onChange={(event) => {
                  setQuery(event.target.value);
                  runSearch(event.target.value);
                }}
                onKeyDown={(event) => {
                  if (event.key === 'Escape' && query) {
                    event.preventDefault();
                    event.stopPropagation();
                    setQuery('');
                    runSearch('');
                  }
                }}
              />
              {query ? (
                <button
                  type="button"
                  className="oe-search-clear"
                  aria-label="Clear GE availability search"
                  onClick={() => {
                    setQuery('');
                    runSearch('');
                    searchInput.current?.focus();
                  }}
                >
                  <X size={16} aria-hidden="true" />
                </button>
              ) : null}
            </div>

            <div className="oe-gea-status" role="status" aria-live="polite">
              {state.status === 'loading'
                ? 'Loading GE availability…'
                : state.failure === 'denied'
                  ? 'Access to this visit plan is no longer available.'
                  : state.failure === 'stale'
                    ? 'This evaluation is out of date.'
                    : state.failure === 'session-ended'
                      ? 'Your session is no longer active.'
                : state.mismatch
                  ? 'The server answered for a different visit plan version.'
                  : `${loadedCount} shown${state.cursor ? '. More records remain to check.' : '.'}`}
            </div>

            {state.mismatch ? (
              <div className="oe-notice oe-notice--warn" role="alert">
                <span className="oe-notice-icon" aria-hidden="true"><CircleAlert size={18} /></span>
                <div>
                  <p className="oe-notice-title">This answer belongs to another version of the visit plan</p>
                  <p className="oe-notice-body">Nothing was opened or changed. Close this view, reload the visit plan, and open availability again.</p>
                </div>
              </div>
            ) : null}

            {state.cursorRejected ? (
              <div className="oe-notice oe-notice--warn" role="alert">
                <span className="oe-notice-icon" aria-hidden="true"><CircleAlert size={18} /></span>
                <div>
                  <p className="oe-notice-title">This continuation has expired</p>
                  <p className="oe-notice-body">The entries already shown stay informational only. Start the list again to read it from the first page.</p>
                  <div className="oe-notice-actions">
                    <button type="button" className="oe-btn oe-btn--secondary oe-btn--sm" onClick={() => void load(ranQuery, null, true)}>
                      Start again
                    </button>
                  </div>
                </div>
              </div>
            ) : null}

            {state.failure === 'denied' ? (
              <div className="oe-notice oe-notice--warn" role="alert">
                <span className="oe-notice-icon" aria-hidden="true"><Lock size={18} /></span>
                <div>
                  <p className="oe-notice-title">This visit plan is no longer available to you</p>
                  <p className="oe-notice-body">
                    The roster, timing information and continuation were withdrawn. Nothing was
                    changed or booked.
                  </p>
                </div>
              </div>
            ) : null}

            {state.failure === 'stale' ? (
              <div className="oe-notice oe-notice--warn" role="alert">
                <span className="oe-notice-icon" aria-hidden="true"><CircleAlert size={18} /></span>
                <div>
                  <p className="oe-notice-title">This evaluation no longer matches the visit plan</p>
                  <p className="oe-notice-body">
                    The stale roster and timing were cleared. Reload the authoritative visit plan
                    before reading availability again.
                  </p>
                  <div className="oe-notice-actions">
                    <button
                      type="button"
                      className="oe-btn oe-btn--secondary oe-btn--sm"
                      onClick={onRequireFreshSession}
                    >
                      Reload visit plan
                    </button>
                  </div>
                </div>
              </div>
            ) : null}

            {state.failure === 'session-ended' ? (
              <div className="oe-notice oe-notice--warn" role="alert">
                <span className="oe-notice-icon" aria-hidden="true"><Lock size={18} /></span>
                <div>
                  <p className="oe-notice-title">Your session ended</p>
                  <p className="oe-notice-body">
                    Protected availability information was cleared. Sign in again to continue.
                  </p>
                </div>
              </div>
            ) : null}

            {state.failure === 'transient' && state.status === 'error' ? (
              <div className="oe-notice oe-notice--warn" role="alert">
                <span className="oe-notice-icon" aria-hidden="true"><CircleAlert size={18} /></span>
                <div>
                  <p className="oe-notice-title">GE availability could not be loaded</p>
                  <p className="oe-notice-body">Nothing was changed. Check your connection and try again.</p>
                  <div className="oe-notice-actions">
                    <button type="button" className="oe-btn oe-btn--secondary oe-btn--sm" onClick={() => void load(ranQuery, null, true)}>
                      <RefreshCw size={14} aria-hidden="true" />
                      Try again
                    </button>
                  </div>
                </div>
              </div>
            ) : null}

            {state.items.length > 0 ? (
              <ul className="oe-gea-list">
                {state.items.map((entry) => {
                  const copy = geAvailabilityResultCopy(entry.resultCode);
                  const name = entry.groundExecutiveName?.trim() || 'Name not recorded';
                  return (
                    <li key={entry.groundExecutiveUserId} className={`oe-gea-row${copy.feasible ? ' oe-gea-row--feasible' : ''}`}>
                      <div className="oe-gea-who">
                        <p className="oe-gea-name">{name}</p>
                        <p className="oe-gea-id oe-mono">GE user ID {entry.groundExecutiveUserId}</p>
                      </div>
                      <span className={`oe-chip${copy.feasible ? ' oe-chip--pine' : ''}`}>{copy.label}</span>
                      <p className="oe-gea-detail">{copy.detail}</p>
                      {copy.feasible ? (
                        <p className="oe-gea-detail oe-gea-detail--quiet">Not bookable from this view.</p>
                      ) : null}
                    </li>
                  );
                })}
              </ul>
            ) : state.status === 'ready' && !state.mismatch && !state.cursorRejected ? (
              <div className="oe-gea-empty">
                <Inbox size={20} aria-hidden="true" />
                <p>No Ground Executive entries were returned for this City scope.</p>
              </div>
            ) : null}

            {state.more === 'error' ? (
              <div className="oe-notice oe-notice--warn" role="alert">
                <span className="oe-notice-icon" aria-hidden="true"><CircleAlert size={18} /></span>
                <div>
                  <p className="oe-notice-title">More records could not be loaded</p>
                  <p className="oe-notice-body">The entries already shown stay informational only.</p>
                  <div className="oe-notice-actions">
                    <button type="button" className="oe-btn oe-btn--secondary oe-btn--sm" onClick={() => void loadMore()}>
                      Try again
                    </button>
                  </div>
                </div>
              </div>
            ) : null}

            {state.cursor && !state.cursorRejected && !state.mismatch && !availabilityClosed ? (
              <div className="oe-gea-more">
                <button
                  type="button"
                  className="oe-btn oe-btn--secondary oe-btn--md"
                  disabled={state.more === 'loading'}
                  onClick={() => void loadMore()}
                >
                  {state.more === 'loading' ? 'Loading more…' : 'Load more records'}
                </button>
              </div>
            ) : null}
          </section>
        </div>
      </div>
    </div>
  );
};
