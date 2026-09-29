import React, { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { ArrowRight, CalendarDays, Clock3, Home as HomeIcon, MapPin, RefreshCw, Search } from 'lucide-react';
import { Property, UserProfile } from '../types';
import { tenantVisitService, TenantVisitRequest } from '../services/tenantVisitService';
import { belongsToTenantVisitSession, isCurrentTenantVisitSession, readTenantVisitSession } from '../utils/tenantVisitSession';
import { appendUniqueVisitRequests, tenantVisitStatusLabel, tenantVisitView } from '../utils/tenantVisitView';

interface TenantDashboardProps {
  user: UserProfile;
  properties: Property[];
  discoveryState: 'LOADING' | 'READY' | 'ERROR';
  discoveryCity: string;
  discoveryQuery: string;
  hasMoreProperties: boolean;
  loadingMoreProperties: boolean;
  loadMorePropertiesError: string | null;
  onSearchHomes: (city: string, query: string) => void;
  onRetryDiscovery: () => void;
  onLoadMoreProperties: () => void;
  onRequestVisit: (property: Property) => void;
}

type HistoryState = {
  identityKey: string | null;
  status: 'loading' | 'ready' | 'error';
  requests: TenantVisitRequest[];
  totalCount: number;
  page: number;
  hasMore: boolean;
};

const emptyHistory: HistoryState = {
  identityKey: null, status: 'loading', requests: [], totalCount: 0, page: 0, hasMore: false
};

const formatRequestedDate = (value: string): string => {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? 'Date unavailable' :
    new Intl.DateTimeFormat('en-IN', { day: 'numeric', month: 'short', year: 'numeric' }).format(date);
};

export const TenantDashboard: React.FC<TenantDashboardProps> = ({
  user, properties, discoveryState, discoveryCity, discoveryQuery, hasMoreProperties,
  loadingMoreProperties, loadMorePropertiesError, onSearchHomes, onRetryDiscovery,
  onLoadMoreProperties, onRequestVisit
}) => {
  const [history, setHistory] = useState<HistoryState>(emptyHistory);
  const [loadMorePending, setLoadMorePending] = useState(false);
  const [loadMoreError, setLoadMoreError] = useState(false);
  const [reload, setReload] = useState(0);
  const [cityInput, setCityInput] = useState(discoveryCity);
  const [queryInput, setQueryInput] = useState(discoveryQuery);

  useEffect(() => {
    setCityInput(discoveryCity);
    setQueryInput(discoveryQuery);
  }, [discoveryCity, discoveryQuery]);

  useEffect(() => {
    const refresh = () => setReload(value => value + 1);
    window.addEventListener('pathome_auth_changed', refresh);
    window.addEventListener('pathome_visit_request_created', refresh);
    window.addEventListener('storage', refresh);
    return () => {
      window.removeEventListener('pathome_auth_changed', refresh);
      window.removeEventListener('pathome_visit_request_created', refresh);
      window.removeEventListener('storage', refresh);
    };
  }, []);

  useEffect(() => {
    const session = readTenantVisitSession(user.id);
    if (!session) {
      setHistory({ ...emptyHistory, status: 'error' });
      return;
    }
    const controller = new AbortController();
    setHistory({ ...emptyHistory, identityKey: session.key });
    setLoadMoreError(false);
    setLoadMorePending(false);
    tenantVisitService.list(0, controller.signal).then(page => {
      if (controller.signal.aborted || !isCurrentTenantVisitSession(session)) return;
      if (!belongsToTenantVisitSession(session, page.userId)) {
        setHistory({ ...emptyHistory, identityKey: session.key, status: 'error' });
        return;
      }
      setHistory({ identityKey: session.key, status: 'ready', requests: page.requests,
        totalCount: page.totalCount, page: 0, hasMore: page.hasMore });
    }).catch(() => {
      if (controller.signal.aborted || !isCurrentTenantVisitSession(session)) return;
      setHistory({ ...emptyHistory, identityKey: session.key, status: 'error' });
    });
    return () => controller.abort();
  }, [user.id, reload]);

  const session = readTenantVisitSession(user.id);
  const visibleHistory: HistoryState = !session ? { ...emptyHistory, status: 'error' } :
    session.key === history.identityKey ? history : emptyHistory;
  const view = tenantVisitView(visibleHistory.status, visibleHistory.requests);
  const loadMore = async () => {
    if (!session || visibleHistory.status !== 'ready' || !visibleHistory.hasMore || loadMorePending) return;
    setLoadMorePending(true);
    setLoadMoreError(false);
    try {
      const next = await tenantVisitService.list(visibleHistory.page + 1);
      if (!isCurrentTenantVisitSession(session)) return;
      if (!belongsToTenantVisitSession(session, next.userId)) {
        setLoadMoreError(true);
        return;
      }
      setHistory(current => current.identityKey !== session.key ? current : {
        identityKey: session.key, status: 'ready',
        requests: appendUniqueVisitRequests(current.requests, next.requests),
        totalCount: next.totalCount, page: next.page, hasMore: next.hasMore
      });
    } catch {
      if (isCurrentTenantVisitSession(session)) setLoadMoreError(true);
    } finally {
      if (isCurrentTenantVisitSession(session)) setLoadMorePending(false);
    }
  };

  return (
    <main className="mx-auto w-full max-w-7xl min-w-0 px-4 pb-16 pt-6 sm:px-6 lg:px-8 lg:pt-10">
      <header className="rounded-3xl border border-slate-200 bg-white px-5 py-7 shadow-sm sm:px-8 sm:py-9">
        <p className="text-xs font-bold uppercase tracking-[0.16em] text-emerald-700">Your Pathome account</p>
        <h1 className="mt-2 break-words font-['Outfit'] text-3xl font-bold tracking-tight text-slate-950 sm:text-4xl">
          Welcome{user.fullName?.trim() ? `, ${user.fullName.trim().split(/\s+/)[0]}` : ''}
        </h1>
        <p className="mt-3 max-w-2xl text-sm leading-6 text-slate-600 sm:text-base">
          Keep track of the property visit requests you have sent, and continue exploring available homes.
        </p>
      </header>

      <section aria-labelledby="visit-history-title" className="mt-8 min-w-0 sm:mt-10">
        <div className="mb-5 flex flex-wrap items-end justify-between gap-3">
          <div>
            <p className="text-xs font-bold uppercase tracking-[0.16em] text-emerald-700">Your activity</p>
            <h2 id="visit-history-title" className="mt-1 font-['Outfit'] text-2xl font-bold text-slate-950 sm:text-3xl">
              Visit requests
            </h2>
          </div>
          {visibleHistory.status === 'ready' && (
            <div className="rounded-2xl border border-emerald-200 bg-emerald-50 px-4 py-2 text-sm text-emerald-900" aria-live="polite" aria-label={`${visibleHistory.totalCount} visit requests sent`}>
              <strong className="text-lg">{visibleHistory.totalCount}</strong> sent
            </div>
          )}
        </div>

        {view === 'loading' && (
          <div className="grid gap-4 md:grid-cols-2" role="status" aria-live="polite" aria-label="Loading your visit requests">
            {[0, 1].map(index => <div key={index} className="h-44 rounded-3xl border border-slate-200 bg-white p-5 shadow-sm">
              <div className="h-5 w-1/2 rounded bg-slate-100 motion-safe:animate-pulse" />
              <div className="mt-5 h-4 w-3/4 rounded bg-slate-100 motion-safe:animate-pulse" />
              <div className="mt-3 h-4 w-2/3 rounded bg-slate-100 motion-safe:animate-pulse" />
            </div>)}
            <span className="sr-only">Loading your visit requests</span>
          </div>
        )}

        {view === 'error' && (
          <div className="rounded-3xl border border-rose-200 bg-white px-5 py-8 shadow-sm sm:px-8" role="alert">
            <h3 className="font-['Outfit'] text-xl font-bold text-slate-950">We could not load your visit requests</h3>
            <p className="mt-2 text-sm leading-6 text-slate-600">Your history is temporarily unavailable. Please try again.</p>
            <button type="button" onClick={() => setReload(value => value + 1)}
              className="mt-5 inline-flex min-h-11 items-center gap-2 rounded-xl bg-emerald-700 px-5 text-sm font-semibold text-white hover:bg-emerald-800 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-700">
              <RefreshCw size={16} aria-hidden="true" /> Retry
            </button>
          </div>
        )}

        {view === 'empty' && (
          <div className="rounded-3xl border border-slate-200 bg-white px-5 py-10 text-center shadow-sm sm:px-8 sm:py-12" role="status" aria-live="polite">
            <div className="mx-auto flex h-14 w-14 items-center justify-center rounded-2xl bg-emerald-50 text-emerald-700">
              <CalendarDays size={26} aria-hidden="true" />
            </div>
            <h3 className="mt-5 font-['Outfit'] text-xl font-bold text-slate-950">No visit requests yet</h3>
            <p className="mx-auto mt-2 max-w-md text-sm leading-6 text-slate-600">
              When you request a property visit, you will find that request here. A request is not a confirmed appointment.
            </p>
            <a href="#discover-homes" className="mt-5 inline-flex min-h-11 items-center gap-2 rounded-xl bg-emerald-700 px-5 text-sm font-semibold text-white hover:bg-emerald-800 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-700">
              Explore homes <ArrowRight size={16} aria-hidden="true" />
            </a>
          </div>
        )}

        {view === 'populated' && (
          <>
            <div className="grid min-w-0 gap-4 lg:grid-cols-2">
              {visibleHistory.requests.map(request => (
                <article key={request.requestId} className="min-w-0 overflow-hidden rounded-3xl border border-slate-200 bg-white shadow-sm sm:flex">
                  <div className="flex h-44 items-center justify-center bg-slate-100 text-slate-400 sm:h-auto sm:w-40 sm:shrink-0">
                    {request.coverImageUrl ? <img src={request.coverImageUrl} alt="" className="h-full w-full object-cover" /> : <HomeIcon size={32} aria-hidden="true" />}
                  </div>
                  <div className="min-w-0 flex-1 p-5">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <span className="rounded-full border border-emerald-200 bg-emerald-50 px-3 py-1 text-xs font-semibold text-emerald-800">
                        {tenantVisitStatusLabel(request.status)}
                      </span>
                      <span className="text-xs text-slate-500">Requested {formatRequestedDate(request.requestedAt)}</span>
                    </div>
                    <h3 className="mt-4 break-words font-['Outfit'] text-lg font-bold leading-snug text-slate-950">
                      {request.propertyTitle || 'Property'}
                    </h3>
                    {(request.sector || request.city) && <p className="mt-1 flex items-start gap-1.5 break-words text-sm text-slate-600">
                      <MapPin size={16} className="mt-0.5 shrink-0" aria-hidden="true" />
                      {[request.sector, request.city].filter(Boolean).join(', ')}
                    </p>}
                    {request.preferredVisitTiming && <p className="mt-3 flex items-start gap-1.5 break-words text-sm text-slate-600">
                      <Clock3 size={16} className="mt-0.5 shrink-0" aria-hidden="true" />
                      Preferred timing: {request.preferredVisitTiming}
                    </p>}
                    {request.propertyAvailable ? <Link to={`/property/${request.propertyId}`}
                      className="mt-4 inline-flex min-h-11 items-center gap-1.5 text-sm font-semibold text-emerald-800 underline-offset-4 hover:underline focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-700">
                      View property <ArrowRight size={16} aria-hidden="true" />
                    </Link> : <p className="mt-4 text-sm text-slate-500">This property is no longer available to view.</p>}
                  </div>
                </article>
              ))}
            </div>
            {visibleHistory.hasMore && <div className="mt-6 text-center">
              <button type="button" onClick={loadMore} disabled={loadMorePending}
                className="min-h-11 rounded-xl border border-slate-300 bg-white px-6 text-sm font-semibold text-slate-800 hover:bg-slate-50 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-700 disabled:opacity-60">
                {loadMorePending ? 'Loading more…' : 'Load more requests'}
              </button>
              {loadMoreError && <p role="alert" className="mt-2 text-sm text-rose-700">Could not load more requests. Please try again.</p>}
            </div>}
          </>
        )}
      </section>

      <section id="discover-homes" aria-labelledby="discover-homes-title" className="mt-10 min-w-0 scroll-mt-24 sm:mt-12">
        <div className="mb-5">
          <div>
            <p className="text-xs font-bold uppercase tracking-[0.16em] text-emerald-700">Keep exploring</p>
            <h2 id="discover-homes-title" className="mt-1 font-['Outfit'] text-2xl font-bold text-slate-950 sm:text-3xl">Available homes</h2>
          </div>
          <form onSubmit={event => { event.preventDefault(); onSearchHomes(cityInput.trim(), queryInput.trim()); }}
            className="mt-5 grid gap-3 rounded-2xl border border-slate-200 bg-white p-4 shadow-sm sm:grid-cols-[minmax(0,12rem)_minmax(0,1fr)_auto] sm:items-end">
            <label className="block min-w-0 text-sm font-semibold text-slate-700">
              City
              <input value={cityInput} onChange={event => setCityInput(event.target.value)} required maxLength={100}
                className="mt-1 min-h-11 w-full min-w-0 rounded-xl border border-slate-300 bg-white px-4 text-base text-slate-900 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-700" />
            </label>
            <label className="relative block min-w-0 text-sm font-semibold text-slate-700">
              Search homes
              <Search size={18} className="pointer-events-none absolute left-4 top-10 text-slate-500" aria-hidden="true" />
              <input value={queryInput} onChange={event => setQueryInput(event.target.value)} placeholder="Name or locality"
                className="mt-1 min-h-11 w-full min-w-0 rounded-xl border border-slate-300 bg-white pl-11 pr-4 text-base font-normal text-slate-900 placeholder:text-slate-500 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-700" />
            </label>
            <button type="submit" className="min-h-11 rounded-xl bg-slate-900 px-5 text-sm font-semibold text-white hover:bg-slate-800 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-700">Search</button>
          </form>
        </div>
        {discoveryState === 'LOADING' && <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3" role="status" aria-label="Loading available homes">
          {[0, 1, 2].map(index => <div key={index} className="h-64 rounded-3xl border border-slate-200 bg-white shadow-sm motion-safe:animate-pulse" />)}
        </div>}
        {discoveryState === 'ERROR' && <div className="rounded-3xl border border-slate-200 bg-white p-6 shadow-sm" role="alert">
          <p className="text-sm text-slate-700">Available homes could not be loaded right now.</p>
          <button type="button" onClick={onRetryDiscovery} className="mt-3 min-h-11 rounded-xl bg-emerald-700 px-5 text-sm font-semibold text-white focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-700">Retry</button>
        </div>}
        {discoveryState === 'READY' && properties.length === 0 && <div className="rounded-3xl border border-slate-200 bg-white p-8 text-center text-sm text-slate-600 shadow-sm">
          No homes match this search. Try another city or search term.
        </div>}
        {discoveryState === 'READY' && properties.length > 0 && <div className="grid min-w-0 gap-5 md:grid-cols-2 lg:grid-cols-3">
          {properties.map(property => <article key={property.id} className="min-w-0 overflow-hidden rounded-3xl border border-slate-200 bg-white shadow-sm">
            <div className="flex h-48 items-center justify-center bg-slate-100 text-slate-400">
              {property.images?.[0] ? <img src={property.images[0]} alt="" className="h-full w-full object-cover" /> : <HomeIcon size={34} aria-hidden="true" />}
            </div>
            <div className="min-w-0 p-5">
              <h3 className="break-words font-['Outfit'] text-lg font-bold text-slate-950">{property.title}</h3>
              {(property.sector || property.city) && <p className="mt-1 flex items-start gap-1.5 break-words text-sm text-slate-600"><MapPin size={16} className="mt-0.5 shrink-0" aria-hidden="true" />{[property.sector, property.city].filter(Boolean).join(', ')}</p>}
              {property.monthlyRent > 0 && property.listingType === 'RENT' && <p className="mt-3 text-base font-bold text-slate-950">₹{property.monthlyRent.toLocaleString('en-IN')}<span className="text-sm font-normal text-slate-600"> / month</span></p>}
              <div className="mt-5 flex flex-col gap-2 border-t border-slate-100 pt-4 sm:flex-row">
                <Link to={`/property/${property.id}`} className="inline-flex min-h-11 flex-1 items-center justify-center rounded-xl border border-slate-300 px-4 text-sm font-semibold text-slate-800 hover:bg-slate-50 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-700">View home</Link>
                <button type="button" onClick={() => onRequestVisit(property)} className="min-h-11 flex-1 rounded-xl bg-emerald-700 px-4 text-sm font-semibold text-white hover:bg-emerald-800 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-700">Request a visit</button>
              </div>
            </div>
          </article>)}
        </div>}
        {discoveryState === 'READY' && hasMoreProperties && <div className="mt-6 text-center">
          <button type="button" onClick={onLoadMoreProperties} disabled={loadingMoreProperties}
            className="min-h-11 rounded-xl border border-slate-300 bg-white px-6 text-sm font-semibold text-slate-800 hover:bg-slate-50 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-700 disabled:opacity-60">
            {loadingMoreProperties ? 'Loading more…' : 'Load more homes'}
          </button>
          {loadMorePropertiesError && <p role="alert" className="mt-2 text-sm text-rose-700">Could not load more homes. Please try again.</p>}
        </div>}
      </section>
    </main>
  );
};
