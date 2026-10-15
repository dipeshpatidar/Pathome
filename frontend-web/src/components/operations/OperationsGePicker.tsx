import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Check, CircleAlert, Inbox, RefreshCw, Search, Sparkles, X } from 'lucide-react';
import { ApiRequestError } from '../../services/apiError';
import {
  formatOperationsDate,
  formatOperationsDuration,
  formatOperationsSlotRange,
  groundExecutiveLabel,
  operationsZoneLabel,
  recommendationViewMatchesContext,
  recommendationStatusHint,
  slotKeyFor,
  type OperationsRecommendationCandidate,
  type OperationsRecommendationView,
  type OperationsSchedulingSelection
} from '../../utils/operationsWorkspacePresentation';
import { OPERATIONS_DISCOVERY_QUERY_MAX } from '../../services/operationsWorkspaceService';

/**
 * Ground Executive discovery: the bounded ranked suggestions, progressive cursor Browse, and search.
 *
 * Three properties of the real contract shape everything here:
 *  - the ranked read is bounded, so it is never presented as the best answer for the whole city;
 *  - discovery is cursor-paged, so an empty page with a continuation cursor means "keep going", not
 *    "there are no eligible Ground Executives";
 *  - each candidate carries exactly one evaluated option (its own time, reserved end, duration and
 *    zone). No time chip, duration or slot is ever fabricated here.
 *
 * Every result is bound to the session, version and query it was issued for, so a late page from an
 * older query or an older session can never be committed or selected.
 */

export interface DiscoveryLoadInput {
  q: string | null;
  cursor: string | null;
}

export type RecommendationLoader = (signal: AbortSignal) => Promise<OperationsRecommendationView | null>;
export type DiscoveryLoader = (
  input: DiscoveryLoadInput,
  signal: AbortSignal
) => Promise<OperationsRecommendationView | null>;

type TabKey = 'recommended' | 'browse' | 'search';

const TABS: { key: TabKey; label: string }[] = [
  { key: 'recommended', label: 'Recommended' },
  { key: 'browse', label: 'Browse all' },
  { key: 'search', label: 'Search' }
];

interface Props {
  sessionId: number;
  sessionVersion: number;
  city: string;
  selection: OperationsSchedulingSelection | null;
  onSelect: (selection: OperationsSchedulingSelection) => void;
  loadRanked: RecommendationLoader;
  loadDiscovery: DiscoveryLoader;
  /** Reports the ranked view that is currently displayed, so the review can reason about the top choice. */
  onRankedView: (view: OperationsRecommendationView | null) => void;
  /** Reloads the authoritative session, for an evaluation that no longer matches what is on screen. */
  onRefreshSession: () => void;
  blocked: boolean;
}

interface BrowseState {
  status: 'idle' | 'loading' | 'error';
  items: OperationsRecommendationCandidate[];
  cursor: string | null;
  /** True once the server returned no continuation cursor: the list is genuinely finished. */
  ended: boolean;
  lastAdded: number | null;
  /** Set when a continuation cursor was refused: the list must restart from its first page. */
  cursorRejected: boolean;
  /** Set when the server answered for a different Session or version than the one on screen. */
  mismatch: boolean;
}

interface SearchState {
  status: 'idle' | 'loading' | 'done' | 'error';
  ran: string;
  items: OperationsRecommendationCandidate[];
  nextCursor: string | null;
  more: 'idle' | 'loading' | 'error';
  /** Set when a continuation cursor was refused: the search must restart from its first page. */
  cursorRejected: boolean;
  /** Set when the server answered for a different Session or version than the one on screen. */
  mismatch: boolean;
}

const EMPTY_SEARCH: SearchState = {
  status: 'idle',
  ran: '',
  items: [],
  nextCursor: null,
  more: 'idle',
  cursorRejected: false,
  mismatch: false
};

/**
 * The backend rejects a continuation whose cursor page context no longer exists (it expires) with a
 * `400`. That is a definitive "this cursor is gone", so it is never retried unchanged — unlike a
 * transport or 5xx failure, which keeps the same cursor because the continuation is still valid.
 */
const isRejectedCursorFailure = (cause: unknown): boolean =>
  cause instanceof ApiRequestError && cause.status === 400;

export const OperationsGePicker: React.FC<Props> = ({
  sessionId,
  sessionVersion,
  city,
  selection,
  onSelect,
  loadRanked,
  loadDiscovery,
  onRankedView,
  onRefreshSession,
  blocked
}) => {
  const [tab, setTab] = useState<TabKey>('recommended');

  const [ranked, setRanked] = useState<OperationsRecommendationView | null>(null);
  const [rankedLoading, setRankedLoading] = useState(true);
  const [rankedError, setRankedError] = useState(false);
  /** The server answered for a different Session or version than the one this drawer is showing. */
  const [rankedMismatch, setRankedMismatch] = useState(false);

  const [browse, setBrowse] = useState<BrowseState>({
    status: 'idle',
    items: [],
    cursor: null,
    ended: false,
    lastAdded: null,
    cursorRejected: false,
    mismatch: false
  });
  const [query, setQuery] = useState('');
  const [search, setSearch] = useState<SearchState>(EMPTY_SEARCH);

  /**
   * A recommendation/discovery answer is only usable when the server evaluated the exact Session
   * version on screen. An answer for another version describes a different itinerary, so it can
   * neither be listed for selection nor recorded as the reviewed view.
   */
  const pageMatchesContext = useCallback(
    (view: OperationsRecommendationView | null): view is OperationsRecommendationView =>
      recommendationViewMatchesContext(view, sessionId, sessionVersion),
    [sessionId, sessionVersion]
  );

  const rankedController = useRef<AbortController | null>(null);
  const browseController = useRef<AbortController | null>(null);
  const searchController = useRef<AbortController | null>(null);
  const searchGeneration = useRef(0);
  /**
   * Synchronous in-flight guard for "Load more matches", keyed by the query and cursor it belongs to.
   *
   * The rendered `more === 'loading'` state only exists after a re-render, which leaves a window in
   * which a second click of the same control issues a duplicate request for the same cursor. A ref is
   * set in the same tick as the click, so a repeated press is refused outright.
   */
  const searchMoreInFlight = useRef<string | null>(null);
  const browseStarted = useRef(false);
  const debounce = useRef<number | undefined>(undefined);
  const searchInput = useRef<HTMLInputElement | null>(null);

  /**
   * The context every page belongs to. A change (a different session, a new version, a different
   * city) invalidates all loaded pages and clears the query, so nothing from the previous context can
   * be selected under the new one.
   */
  const contextKey = `${sessionId}|${sessionVersion}|${city}`;
  const lastContext = useRef(contextKey);

  const refreshRanked = useCallback(async () => {
    rankedController.current?.abort();
    const controller = new AbortController();
    rankedController.current = controller;
    setRankedLoading(true);
    setRankedError(false);
    setRankedMismatch(false);
    try {
      const view = await loadRanked(controller.signal);
      if (controller.signal.aborted) return;
      if (!pageMatchesContext(view)) {
        // Never list candidates evaluated against another itinerary version.
        setRanked(null);
        onRankedView(null);
        setRankedMismatch(true);
        return;
      }
      setRanked(view);
      onRankedView(view);
    } catch {
      if (controller.signal.aborted) return;
      setRanked(null);
      onRankedView(null);
      setRankedError(true);
    } finally {
      if (!controller.signal.aborted) setRankedLoading(false);
    }
  }, [loadRanked, onRankedView, pageMatchesContext]);

  useEffect(() => {
    void refreshRanked();
    return () => rankedController.current?.abort();
  }, [refreshRanked]);

  useEffect(() => {
    if (lastContext.current === contextKey) return;
    lastContext.current = contextKey;
    window.clearTimeout(debounce.current);
    searchGeneration.current += 1;
    searchController.current?.abort();
    browseController.current?.abort();
    browseStarted.current = false;
    setQuery('');
    setSearch(EMPTY_SEARCH);
    setBrowse({
      status: 'idle',
      items: [],
      cursor: null,
      ended: false,
      lastAdded: null,
      cursorRejected: false,
      mismatch: false
    });
    setRankedMismatch(false);
    setTab('recommended');
  }, [contextKey]);

  useEffect(() => () => {
    window.clearTimeout(debounce.current);
    browseController.current?.abort();
    searchController.current?.abort();
  }, []);

  const loadBrowse = useCallback(
    async (reset: boolean) => {
      browseController.current?.abort();
      const controller = new AbortController();
      browseController.current = controller;
      const requestedCursor = reset ? null : browse.cursor;
      setBrowse((current) => ({
        ...current,
        status: 'loading',
        items: reset ? [] : current.items,
        cursor: reset ? null : current.cursor,
        ended: reset ? false : current.ended,
        lastAdded: null,
        cursorRejected: false
      }));
      try {
        const page = await loadDiscovery({ q: null, cursor: requestedCursor }, controller.signal);
        if (controller.signal.aborted || page === null) return;
        if (!pageMatchesContext(page)) {
          setBrowse((current) => ({ ...current, status: 'idle', cursorRejected: false, mismatch: true, lastAdded: null }));
          return;
        }
        setBrowse((current) => {
          const base = reset ? [] : current.items;
          const seen = new Set(base.map(slotKeyFor));
          const added = page.candidates.filter((candidate) => !seen.has(slotKeyFor(candidate)));
          return {
            status: 'idle',
            items: [...base, ...added],
            cursor: page.nextCursor,
            ended: page.nextCursor === null,
            lastAdded: added.length,
            cursorRejected: false,
            mismatch: false
          };
        });
      } catch (cause) {
        if (controller.signal.aborted) return;
        if (requestedCursor !== null && isRejectedCursorFailure(cause)) {
          /**
           * The continuation was refused. The expired cursor is dropped here so the next request can
           * never reuse it; the OE gets an explicit restart of the same first page instead of a
           * "Try again" that would resend the dead cursor forever.
           */
          setBrowse((current) => ({ ...current, status: 'idle', cursor: null, cursorRejected: true }));
          return;
        }
        setBrowse((current) => ({ ...current, status: 'error' }));
      }
    },
    [browse.cursor, loadDiscovery, pageMatchesContext]
  );

  const changeTab = useCallback(
    (next: TabKey) => {
      setTab(next);
      if (next === 'browse' && !browseStarted.current) {
        browseStarted.current = true;
        void loadBrowse(true);
      }
    },
    [loadBrowse]
  );

  const runSearch = useCallback(
    (value: string) => {
      window.clearTimeout(debounce.current);
      searchController.current?.abort();
      // A query change abandons any continuation that was in flight for the previous query.
      searchMoreInFlight.current = null;
      searchGeneration.current += 1;
      const generation = searchGeneration.current;
      const trimmed = value.trim();
      if (!trimmed) {
        setSearch(EMPTY_SEARCH);
        return;
      }
      // A new query has no continuation: any cursor from the previous query is obsolete.
      setSearch((current) => ({
        ...current,
        status: 'loading',
        items: [],
        nextCursor: null,
        more: 'idle',
        cursorRejected: false,
        mismatch: false
      }));
      debounce.current = window.setTimeout(() => {
        void (async () => {
          const controller = new AbortController();
          searchController.current = controller;
          try {
            const page = await loadDiscovery({ q: trimmed, cursor: null }, controller.signal);
            if (generation !== searchGeneration.current || controller.signal.aborted || page === null) return;
            if (!pageMatchesContext(page)) {
              setSearch({ ...EMPTY_SEARCH, status: 'done', ran: trimmed, mismatch: true });
              return;
            }
            setSearch({
              status: 'done',
              ran: trimmed,
              items: page.candidates,
              nextCursor: page.nextCursor,
              more: 'idle',
              cursorRejected: false,
              mismatch: false
            });
          } catch {
            if (generation !== searchGeneration.current || controller.signal.aborted) return;
            setSearch({ ...EMPTY_SEARCH, status: 'error', ran: trimmed });
          }
        })();
      }, 400);
    },
    [loadDiscovery, pageMatchesContext]
  );

  const loadMoreSearch = useCallback(async () => {
    const { ran, nextCursor } = search;
    if (!nextCursor) return;
    const requestKey = `${ran}|${nextCursor}`;
    if (searchMoreInFlight.current !== null) return;
    searchMoreInFlight.current = requestKey;
    const generation = searchGeneration.current;
    const controller = new AbortController();
    searchController.current = controller;
    setSearch((current) => ({ ...current, more: 'loading' }));
    try {
      const page = await loadDiscovery({ q: ran, cursor: nextCursor }, controller.signal);
      if (generation !== searchGeneration.current || controller.signal.aborted || page === null) return;
      if (!pageMatchesContext(page)) {
        setSearch((current) => (current.ran === ran
          ? { ...current, more: 'idle', nextCursor: null, mismatch: true }
          : current));
        return;
      }
      setSearch((current) => {
        if (current.ran !== ran || current.nextCursor !== nextCursor) return current;
        const seen = new Set(current.items.map(slotKeyFor));
        return {
          ...current,
          items: [...current.items, ...page.candidates.filter((candidate) => !seen.has(slotKeyFor(candidate)))],
          nextCursor: page.nextCursor,
          more: 'idle',
          cursorRejected: false,
          mismatch: false
        };
      });
    } catch (cause) {
      if (generation !== searchGeneration.current || controller.signal.aborted) return;
      if (isRejectedCursorFailure(cause)) {
        /**
         * The continuation was refused. The dead cursor is cleared here so it can never be resent, and
         * the OE is offered a restart of the same normalized query from its first page.
         */
        setSearch((current) => (current.ran === ran
          ? { ...current, more: 'idle', nextCursor: null, cursorRejected: true }
          : current));
        return;
      }
      setSearch((current) => (current.ran === ran ? { ...current, more: 'error' } : current));
    } finally {
      // Released only if this request still owns the guard, so a superseded attempt cannot unlock a
      // newer one.
      if (searchMoreInFlight.current === requestKey) searchMoreInFlight.current = null;
    }
  }, [loadDiscovery, pageMatchesContext, search]);

  /**
   * Every selection carries the evaluation it was chosen from. The workspace compares that version
   * with the authoritative Session immediately before booking, so a candidate reviewed here can never
   * be booked against an itinerary version the OE never saw.
   */
  const selectCandidate = useCallback(
    (selection: Omit<OperationsSchedulingSelection, 'evaluated'>) => {
      onSelect({ ...selection, evaluated: { sessionId, sessionVersion } });
    },
    [onSelect, sessionId, sessionVersion]
  );
  const cardProps = { selection, onSelect: selectCandidate, blocked };

  return (
    <div className="oe-picker">
      <div role="tablist" aria-label="Choose how to find a Ground Executive" className="oe-ptabs">
        {TABS.map(({ key, label }) => (
          <button
            key={key}
            type="button"
            role="tab"
            id={`oe-ptab-${key}`}
            aria-selected={tab === key}
            aria-controls={`oe-ppanel-${key}`}
            tabIndex={tab === key ? 0 : -1}
            className="oe-ptab"
            onClick={() => changeTab(key)}
            onKeyDown={(event) => {
              const index = TABS.findIndex((entry) => entry.key === key);
              const next = event.key === 'ArrowRight'
                ? TABS[(index + 1) % TABS.length]
                : event.key === 'ArrowLeft'
                  ? TABS[(index + TABS.length - 1) % TABS.length]
                  : null;
              if (next) {
                event.preventDefault();
                changeTab(next.key);
                document.getElementById(`oe-ptab-${next.key}`)?.focus();
              }
            }}
          >
            {label}
          </button>
        ))}
      </div>

      <div
        role="tabpanel"
        id="oe-ppanel-recommended"
        aria-labelledby="oe-ptab-recommended"
        hidden={tab !== 'recommended'}
        className="oe-ppanel"
      >
        <div className="oe-ppanel-head">
          <p className="oe-ppanel-note">
            A limited ranked set of evaluated candidates in {city}. It is not a claim that these are the
            best Ground Executives in the city.
          </p>
          <button
            type="button"
            className="oe-btn oe-btn--secondary oe-btn--sm"
            onClick={() => void refreshRanked()}
            disabled={blocked || rankedLoading}
          >
            <RefreshCw size={14} aria-hidden="true" />
            Refresh
          </button>
        </div>

        {rankedLoading ? (
          <ul className="oe-ge-list" aria-busy="true" aria-label="Loading recommendations">
            {[0, 1, 2].map((key) => (
              <li key={key} className="oe-ge oe-ge--skeleton">
                <span className="oe-skel" style={{ width: '45%' }} />
                <span className="oe-skel" style={{ width: '75%' }} />
              </li>
            ))}
          </ul>
        ) : rankedMismatch ? (
          <Notice
            tone="warn"
            icon={<CircleAlert size={18} />}
            title="These suggestions were evaluated for a different version of this session"
            body="Nothing was selected or changed. Reload the session so the itinerary on screen is the one the server evaluated, then choose again."
          >
            <button type="button" className="oe-btn oe-btn--secondary oe-btn--sm" onClick={onRefreshSession}>
              Reload the session
            </button>
          </Notice>
        ) : rankedError ? (
          <Notice tone="warn" icon={<CircleAlert size={18} />} title="Recommendations couldn’t be loaded" body="Nothing was changed. You can try again, or use Browse all or Search.">
            <button type="button" className="oe-btn oe-btn--secondary oe-btn--sm" onClick={() => void refreshRanked()}>
              Try again
            </button>
          </Notice>
        ) : ranked && ranked.candidates.length > 0 ? (
          <ul className="oe-ge-list">
            {ranked.candidates.map((candidate) => (
              <GeCard
                key={slotKeyFor(candidate)}
                candidate={candidate}
                source="recommended"
                badge={candidate.rank === 1 ? 'Top evaluated suggestion' : `Suggestion ${candidate.rank}`}
                top={candidate.rank === 1}
                {...cardProps}
              />
            ))}
          </ul>
        ) : (
          <Notice
            icon={<Inbox size={18} />}
            title="No feasible suggestion was returned"
            body={
              recommendationStatusHint(ranked?.status)
              ?? 'You can still choose a Ground Executive from Browse all or Search. A reason will be required.'
            }
          />
        )}
        {ranked && ranked.candidateSearchTruncated ? (
          <p className="oe-footnote">
            The server reported that its candidate search was truncated, so this is a bounded sample.
          </p>
        ) : null}
      </div>

      <div role="tabpanel" id="oe-ppanel-browse" aria-labelledby="oe-ptab-browse" hidden={tab !== 'browse'} className="oe-ppanel">
        <p className="oe-ppanel-note">
          Eligible Ground Executives in {city}, in the order the server returned them. This is not a
          ranking, and it loads in pages rather than all at once.
        </p>

        {browse.status === 'error' ? (
          <Notice tone="warn" icon={<CircleAlert size={18} />} title="Eligible Ground Executives couldn’t be loaded" body="Nothing was changed. Try again, or use the ranked suggestions.">
            <button type="button" className="oe-btn oe-btn--secondary oe-btn--sm" onClick={() => void loadBrowse(browse.items.length === 0)}>
              Try again
            </button>
          </Notice>
        ) : null}

        {browse.mismatch ? (
          <Notice
            tone="warn"
            icon={<CircleAlert size={18} />}
            title="These candidates were evaluated for a different version of this session"
            body="Nothing was selected or changed. Reload the session so the itinerary on screen is the one the server evaluated, then browse again."
          >
            <button type="button" className="oe-btn oe-btn--secondary oe-btn--sm" onClick={onRefreshSession}>
              Reload the session
            </button>
          </Notice>
        ) : null}

        {browse.status === 'loading' && browse.items.length === 0 ? (
          <ul className="oe-ge-list" aria-busy="true" aria-label="Loading eligible Ground Executives">
            {[0, 1, 2].map((key) => (
              <li key={key} className="oe-ge oe-ge--skeleton">
                <span className="oe-skel" style={{ width: '40%' }} />
                <span className="oe-skel" style={{ width: '70%' }} />
              </li>
            ))}
          </ul>
        ) : null}

        {browse.items.length > 0 ? (
          <ul className="oe-ge-list">
            {browse.items.map((candidate) => (
              <GeCard key={slotKeyFor(candidate)} candidate={candidate} source="browse" {...cardProps} />
            ))}
          </ul>
        ) : null}

        {browseStarted.current && browse.status !== 'loading' && browse.status !== 'error' && browse.ended && browse.items.length === 0 ? (
          <Notice icon={<Inbox size={18} />} title="No eligible Ground Executives were returned" body="The first page of eligible Ground Executives is empty. Check again later." />
        ) : null}

        {browse.cursorRejected ? (
          <Notice
            tone="warn"
            icon={<CircleAlert size={18} />}
            title="This list’s continuation has expired"
            body="The server no longer recognises the page cursor, so the rest of the list can’t be continued. The candidates already shown stay listed. Start the list again to read it from the first page."
          >
            <button
              type="button"
              className="oe-btn oe-btn--secondary oe-btn--sm"
              disabled={blocked}
              onClick={() => void loadBrowse(true)}
            >
              Start this list again
            </button>
          </Notice>
        ) : null}

        {browseStarted.current && browse.status !== 'error' && !browse.ended && !browse.cursorRejected && !browse.mismatch ? (
          <div className="oe-more-row" role="status">
            {browse.status === 'loading' ? (
              <p className="oe-ppanel-note">Loading more candidates…</p>
            ) : (
              <>
                {browse.lastAdded === 0 ? (
                  <p className="oe-ppanel-note">
                    No eligible Ground Executives were on that page, but the server says more pages
                    remain, so the list isn’t finished.
                  </p>
                ) : null}
                <button type="button" className="oe-btn oe-btn--secondary oe-btn--md" onClick={() => void loadBrowse(false)} disabled={blocked}>
                  Load more
                </button>
              </>
            )}
          </div>
        ) : null}
        {browseStarted.current && browse.status !== 'error' && browse.ended && browse.items.length > 0 ? (
          <p className="oe-ppanel-note">End of list. The server has no further pages.</p>
        ) : null}
      </div>

      <div role="tabpanel" id="oe-ppanel-search" aria-labelledby="oe-ptab-search" hidden={tab !== 'search'} className="oe-ppanel">
        <label htmlFor="oe-ge-search" className="oe-ppanel-note">
          Search eligible Ground Executives in {city} by partial name or exact user ID.
        </label>
        <div className="oe-search oe-search--panel">
          <Search size={16} aria-hidden="true" />
          <input
            ref={searchInput}
            id="oe-ge-search"
            className="oe-search-input"
            type="text"
            inputMode="search"
            autoComplete="off"
            maxLength={OPERATIONS_DISCOVERY_QUERY_MAX}
            placeholder="e.g. Rahul or 4207"
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
              aria-label="Clear search"
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
        <p className="oe-footnote">
          {/^\d+$/.test(query.trim()) ? 'Searching by exact user ID.' : 'Searching by name; partial and case-insensitive.'}
          {' '}Results never include phone numbers or email addresses.
        </p>

        <div role="status" aria-live="polite" className="oe-search-status">
          {search.status === 'loading'
            ? 'Searching…'
            : search.status === 'done'
              ? `${search.items.length} ${search.items.length === 1 ? 'candidate' : 'candidates'} loaded for “${search.ran}”.${search.nextCursor ? ' More are available.' : ''}`
              : ''}
        </div>

        {search.status === 'idle' ? (
          <p className="oe-ppanel-note">Type a name or user ID to find a Ground Executive who may not appear in the suggestions.</p>
        ) : search.status === 'loading' ? (
          <ul className="oe-ge-list" aria-busy="true" aria-label="Searching">
            <li className="oe-ge oe-ge--skeleton">
              <span className="oe-skel" style={{ width: '40%' }} />
              <span className="oe-skel" style={{ width: '70%' }} />
            </li>
          </ul>
        ) : search.status === 'error' ? (
          <Notice tone="warn" icon={<CircleAlert size={18} />} title="Search didn’t complete" body="Nothing was changed. Try again, or use the ranked suggestions.">
            <button type="button" className="oe-btn oe-btn--secondary oe-btn--sm" onClick={() => runSearch(query)}>Try again</button>
          </Notice>
        ) : (
          <>
            {search.mismatch ? (
              <Notice
                tone="warn"
                icon={<CircleAlert size={18} />}
                title="This search was evaluated for a different version of this session"
                body="Nothing was selected or changed. Reload the session so the itinerary on screen is the one the server evaluated, then search again."
              >
                <button type="button" className="oe-btn oe-btn--secondary oe-btn--sm" onClick={onRefreshSession}>
                  Reload the session
                </button>
              </Notice>
            ) : null}

            {search.cursorRejected ? (
              <Notice
                tone="warn"
                icon={<CircleAlert size={18} />}
                title={`“${search.ran}” couldn’t be continued`}
                body="The server no longer recognises the page cursor, so more matches can’t be loaded from it. The matches already listed stay selectable. Start the search again to read it from the first page."
              >
                <button
                  type="button"
                  className="oe-btn oe-btn--secondary oe-btn--sm"
                  disabled={blocked}
                  onClick={() => runSearch(search.ran)}
                >
                  Start the search again
                </button>
              </Notice>
            ) : null}

            {search.items.length > 0 ? (
              <ul className="oe-ge-list">
                {search.items.map((candidate) => (
                  <GeCard key={slotKeyFor(candidate)} candidate={candidate} source="search" {...cardProps} />
                ))}
              </ul>
            ) : search.mismatch || search.cursorRejected ? null : search.nextCursor ? (
              <Notice
                icon={<Inbox size={18} />}
                title={`No eligible matches yet for “${search.ran}”`}
                body="That page had no eligible Ground Executives, but the server says more may remain. Load more to keep looking."
              />
            ) : (
              <Notice
                icon={<Inbox size={18} />}
                title={`No eligible Ground Executive matched “${search.ran}”`}
                body="Check the spelling or the user ID. Only Ground Executives eligible for this visit appear here."
              />
            )}
            {search.more === 'error' ? (
              <Notice tone="warn" icon={<CircleAlert size={18} />} title="More matches couldn’t be loaded" body="Nothing was changed. The candidates already listed are still selectable.">
                <button type="button" className="oe-btn oe-btn--secondary oe-btn--sm" onClick={() => void loadMoreSearch()}>Try again</button>
              </Notice>
            ) : search.nextCursor && !search.cursorRejected && !search.mismatch ? (
              <div className="oe-more-row">
                <button
                  type="button"
                  className="oe-btn oe-btn--secondary oe-btn--md"
                  disabled={blocked || search.more === 'loading'}
                  onClick={() => void loadMoreSearch()}
                >
                  {search.more === 'loading' ? 'Loading more…' : 'Load more matches'}
                </button>
              </div>
            ) : search.items.length > 0 ? (
              <p className="oe-ppanel-note">End of results for “{search.ran}”.</p>
            ) : null}
          </>
        )}
      </div>
    </div>
  );
};

export const Notice: React.FC<{
  icon: React.ReactNode;
  title: string;
  body: string;
  tone?: 'warn';
  children?: React.ReactNode;
}> = ({ icon, title, body, tone, children }) => (
  <div className={`oe-notice${tone === 'warn' ? ' oe-notice--warn' : ''}`} role={tone === 'warn' ? 'alert' : undefined}>
    <span className="oe-notice-icon" aria-hidden="true">{icon}</span>
    <div>
      <p className="oe-notice-title">{title}</p>
      <p className="oe-notice-body">{body}</p>
      {children ? <div className="oe-notice-actions">{children}</div> : null}
    </div>
  </div>
);

const GeCard: React.FC<{
  candidate: OperationsRecommendationCandidate;
  source: OperationsSchedulingSelection['source'];
  badge?: string;
  top?: boolean;
  selection: OperationsSchedulingSelection | null;
  /** The evaluation binding is added by the picker, which is the layer that knows the version. */
  onSelect: (selection: Omit<OperationsSchedulingSelection, 'evaluated'>) => void;
  blocked: boolean;
}> = ({ candidate, source, badge, top = false, selection, onSelect, blocked }) => {
  const selected = selection !== null
    && selection.candidate.groundExecutiveUserId === candidate.groundExecutiveUserId
    && selection.candidate.scheduledAt === candidate.scheduledAt;
  const usable = candidate.scheduledAt !== null && candidate.zoneId !== null;
  const label = groundExecutiveLabel(candidate);

  return (
    <li className={`oe-ge${selected ? ' oe-ge--selected' : ''}${usable ? '' : ' oe-ge--off'}`}>
      {badge ? (
        <span className={`oe-chip oe-ge-badge${top ? ' oe-chip--pine' : ''}`}>
          {top ? <Sparkles size={12} aria-hidden="true" /> : null}
          {badge}
        </span>
      ) : null}
      <div className="oe-ge-who">
        <p className="oe-ge-name">{label}</p>
        {candidate.groundExecutiveName !== null ? (
          <p className="oe-ge-id oe-mono">GE user ID {candidate.groundExecutiveUserId}</p>
        ) : null}
      </div>
      <dl className="oe-slot">
        <div className="oe-slot-row">
          <dt>Evaluated visit</dt>
          <dd className="oe-slot-when">
            {formatOperationsDate(candidate.scheduledAt) ?? 'Time not returned'}
            {formatOperationsSlotRange(candidate) ? ` · ${formatOperationsSlotRange(candidate)}` : ''}
          </dd>
        </div>
        <div className="oe-slot-pair">
          <div className="oe-slot-row">
            <dt>Evaluated duration</dt>
            <dd>{formatOperationsDuration(candidate.durationMinutes) ?? 'Not returned'}</dd>
          </div>
          <div className="oe-slot-row">
            <dt>Time zone</dt>
            <dd>{operationsZoneLabel(candidate.zoneId) ?? 'Not returned'}</dd>
          </div>
        </div>
      </dl>
      {!usable ? (
        <p className="oe-ge-warn">This candidate has no usable evaluated time, so it can’t be selected.</p>
      ) : null}
      <button
        type="button"
        className={`oe-btn oe-btn--md oe-btn--block ${selected ? 'oe-btn--primary' : 'oe-btn--secondary'}`}
        aria-pressed={selected}
        disabled={!usable || blocked}
        aria-label={`${selected ? 'Selected' : 'Select'} ${label}`}
        onClick={() => onSelect({ candidate, source })}
      >
        {selected ? <Check size={16} aria-hidden="true" /> : null}
        {selected ? 'Selected' : 'Select'}
      </button>
    </li>
  );
};
