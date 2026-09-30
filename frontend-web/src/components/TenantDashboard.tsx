import React, { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { ArrowRight, BedDouble, CalendarDays, Clock3, Home as HomeIcon, MapPin, RefreshCw, Search } from 'lucide-react';
import { Property, UserProfile } from '../types';
import { tenantVisitService, TenantVisitRequest } from '../services/tenantVisitService';
import { belongsToTenantVisitSession, isCurrentTenantVisitSession, readTenantVisitSession } from '../utils/tenantVisitSession';
import { appendUniqueVisitRequests, tenantVisitStatusLabel, tenantVisitView } from '../utils/tenantVisitView';
import { buildCloudinaryUrl } from '../utils/mediaTransform';

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

const formatRequestedDate = (value: string | null | undefined): string | null => {
  if (!value) return null;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null :
    new Intl.DateTimeFormat('en-IN', { day: 'numeric', month: 'short', year: 'numeric' }).format(date);
};

const PropertyImage: React.FC<{ src?: string | null; alt: string; priority?: boolean }> = ({ src, alt, priority = false }) => {
  const [failed, setFailed] = useState(false);
  const image = typeof src === 'string' ? src.trim() : '';
  useEffect(() => setFailed(false), [image]);
  return image && !failed ? (
    <img src={buildCloudinaryUrl(image, 'DISCOVERY_CARD')} alt={alt} loading={priority ? 'eager' : 'lazy'}
      decoding="async" onError={() => setFailed(true)} className="h-full w-full object-cover" />
  ) : <div className="flex h-full w-full items-center justify-center bg-[#e8e6df] text-slate-500" role="img" aria-label="Property photo unavailable"><HomeIcon size={36} aria-hidden="true" /></div>;
};

const focusClass = 'focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-700';
const readText = (value: unknown): string => typeof value === 'string' ? value.trim() : '';

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
  const pageRequestRef = useRef<AbortController | null>(null);
  const pagePendingRef = useRef(false);

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
    pageRequestRef.current?.abort();
    pagePendingRef.current = false;
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
    return () => { controller.abort(); pageRequestRef.current?.abort(); };
  }, [user.id, reload]);

  const session = readTenantVisitSession(user.id);
  const visibleHistory: HistoryState = !session ? { ...emptyHistory, status: 'error' } :
    session.key === history.identityKey ? history : emptyHistory;
  const view = tenantVisitView(visibleHistory.status, visibleHistory.requests);
  const firstName = user.fullName?.trim().split(/\s+/)[0];

  const loadMore = async () => {
    if (!session || visibleHistory.status !== 'ready' || !visibleHistory.hasMore || pagePendingRef.current) return;
    pagePendingRef.current = true;
    const controller = new AbortController();
    pageRequestRef.current = controller;
    setLoadMorePending(true);
    setLoadMoreError(false);
    try {
      const next = await tenantVisitService.list(visibleHistory.page + 1, controller.signal);
      if (controller.signal.aborted || !isCurrentTenantVisitSession(session)) return;
      if (!belongsToTenantVisitSession(session, next.userId)) {
        setLoadMoreError(true);
        return;
      }
      setHistory(current => current.identityKey !== session.key || current.page !== visibleHistory.page ? current : {
        identityKey: session.key, status: 'ready',
        requests: appendUniqueVisitRequests(current.requests, next.requests),
        totalCount: next.totalCount, page: next.page, hasMore: next.hasMore
      });
    } catch {
      if (!controller.signal.aborted && isCurrentTenantVisitSession(session)) setLoadMoreError(true);
    } finally {
      if (pageRequestRef.current === controller) {
        pageRequestRef.current = null;
        pagePendingRef.current = false;
        setLoadMorePending(false);
      }
    }
  };

  return (
    <main className="min-w-0 bg-[#f7f7f2] pb-16 text-slate-950">
      <div className="mx-auto w-full max-w-7xl min-w-0 px-4 pt-5 sm:px-6 sm:pt-8 lg:px-8 lg:pt-10">
        <header className="relative grid min-w-0 gap-7 overflow-hidden rounded-[28px] bg-[#12372e] px-5 py-7 text-white sm:rounded-[36px] sm:px-9 sm:py-9 lg:gap-10 lg:px-9 lg:py-8 xl:grid-cols-[minmax(0,0.9fr)_minmax(0,1.1fr)] xl:items-center xl:gap-12 xl:px-12">
          <div className="relative z-10 max-w-3xl lg:max-w-xl">
            <p className="text-xs font-bold uppercase tracking-[0.2em] text-emerald-200">Your home search</p>
            <h1 className="mt-3 break-words font-['Outfit'] text-[clamp(2rem,6vw,4.25rem)] font-semibold leading-[1.08] tracking-tight lg:mt-3 lg:text-[clamp(2.25rem,3.5vw,2.875rem)]">
              {firstName ? `Find your place, ${firstName}.` : 'Find your place.'}
            </h1>
            <p className="mt-3 max-w-xl text-sm leading-6 text-emerald-50/85 sm:text-base lg:max-w-md">Explore available homes and keep your visit requests in one place.</p>
          </div>
          <form onSubmit={event => { event.preventDefault(); onSearchHomes(cityInput.trim(), queryInput.trim()); document.getElementById('discover-homes')?.scrollIntoView({ behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth', block: 'start' }); }}
            className="relative z-10 grid min-w-0 gap-3 rounded-[22px] bg-white p-3 text-slate-900 shadow-[0_22px_55px_-30px_rgba(0,0,0,.5)] sm:grid-cols-[minmax(0,12rem)_minmax(0,1fr)_auto] sm:items-end sm:p-4 lg:grid-cols-[minmax(0,10rem)_minmax(0,1fr)_auto] xl:grid-cols-[minmax(0,12rem)_minmax(0,1fr)_auto]">
            <label className="block min-w-0 text-xs font-semibold text-slate-600">City
              <input value={cityInput} onChange={event => setCityInput(event.target.value)} required maxLength={100} autoComplete="address-level2"
                className={`mt-1 min-h-11 w-full min-w-0 rounded-xl border border-slate-300 bg-white px-3 text-base text-slate-950 ${focusClass}`} />
            </label>
            <label className="block min-w-0 text-xs font-semibold text-slate-600">Search homes
              <span className="relative mt-1 block"><Search size={18} className="pointer-events-none absolute left-3 top-3.5 text-slate-500" aria-hidden="true" />
                <input value={queryInput} onChange={event => setQueryInput(event.target.value)} placeholder="Name or locality"
                  className={`min-h-11 w-full min-w-0 rounded-xl border border-slate-300 bg-white pl-10 pr-3 text-base font-normal text-slate-950 placeholder:text-slate-500 ${focusClass}`} />
              </span>
            </label>
            <button type="submit" className={`inline-flex min-h-11 items-center justify-center gap-2 rounded-xl bg-emerald-700 px-4 text-sm font-semibold text-white transition-colors hover:bg-emerald-800 active:bg-emerald-900 ${focusClass}`}>
              Explore homes <ArrowRight size={17} aria-hidden="true" />
            </button>
          </form>
        </header>

        <div className="mt-10 grid min-w-0 gap-12 sm:mt-14 sm:gap-14 lg:mt-10 lg:grid-cols-[minmax(0,0.9fr)_minmax(0,1.1fr)] lg:items-start lg:gap-8 xl:gap-10">
          <section id="visit-history" aria-labelledby="visit-history-title" className="min-w-0 scroll-mt-24">
            <div className="mb-5 flex flex-wrap items-end justify-between gap-3 sm:mb-6">
              <div><p className="text-xs font-bold uppercase tracking-[0.16em] text-emerald-800">Your journey</p>
                <h2 id="visit-history-title" className="mt-1 font-['Outfit'] text-2xl font-semibold tracking-tight sm:text-3xl">Visit requests</h2>
                <p className="mt-1 text-sm text-slate-600">Requests you have sent for homes you want to see.</p>
              </div>
              {view === 'populated' && <p className="text-sm font-medium text-slate-600" aria-label={`${visibleHistory.totalCount} ${visibleHistory.totalCount === 1 ? 'visit request' : 'visit requests'} sent`}>
                {visibleHistory.totalCount} {visibleHistory.totalCount === 1 ? 'request' : 'requests'} sent
              </p>}
            </div>

            {view === 'loading' && <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-1" role="status" aria-live="polite" aria-label="Loading your visit requests">
              {[0, 1].map(index => <div key={index} className="overflow-hidden rounded-[24px] border border-slate-200 bg-white shadow-sm sm:flex sm:h-48">
                <div className="aspect-[16/9] bg-slate-200 motion-safe:animate-pulse sm:aspect-auto sm:w-[38%] sm:shrink-0" /><div className="p-5 sm:flex-1"><div className="h-4 w-24 rounded bg-slate-200 motion-safe:animate-pulse" /><div className="mt-5 h-5 w-4/5 rounded bg-slate-200 motion-safe:animate-pulse" /><div className="mt-3 h-4 w-1/2 rounded bg-slate-100 motion-safe:animate-pulse" /></div>
              </div>)}<span className="sr-only">Loading your visit requests</span>
            </div>}

            {view === 'error' && <div className="rounded-[28px] border border-rose-200 bg-white px-5 py-8 shadow-sm sm:px-8" role="alert">
              <h3 className="font-['Outfit'] text-xl font-semibold">Your requests are unavailable right now</h3>
              <p className="mt-2 text-sm leading-6 text-slate-600">Please try again. You can still explore available homes below.</p>
              <button type="button" onClick={() => setReload(value => value + 1)} className={`mt-5 inline-flex min-h-11 items-center gap-2 rounded-xl bg-emerald-700 px-5 text-sm font-semibold text-white hover:bg-emerald-800 ${focusClass}`}><RefreshCw size={16} aria-hidden="true" /> Retry</button>
            </div>}

            {view === 'empty' && <div className="grid gap-5 overflow-hidden rounded-[28px] border border-[#e4e6dc] bg-white p-5 shadow-sm sm:grid-cols-[auto_1fr] sm:items-center sm:p-8" role="status" aria-live="polite">
              <div className="flex h-14 w-14 items-center justify-center rounded-2xl bg-emerald-50 text-emerald-800"><CalendarDays size={27} aria-hidden="true" /></div>
              <div><h3 className="font-['Outfit'] text-xl font-semibold">Your next home starts with a look around.</h3>
                <p className="mt-2 max-w-2xl text-sm leading-6 text-slate-600">You have not requested a property visit yet. Explore available homes and request a visit when one feels right.</p>
                <a href="#discover-homes" className={`mt-4 inline-flex min-h-11 items-center gap-2 rounded-xl bg-emerald-700 px-5 text-sm font-semibold text-white hover:bg-emerald-800 ${focusClass}`}>Explore homes <ArrowRight size={16} aria-hidden="true" /></a>
              </div>
            </div>}

            {view === 'populated' && <>
              <div className="grid min-w-0 gap-4 md:grid-cols-2 lg:grid-cols-1">
                {visibleHistory.requests.map(request => {
                  const date = formatRequestedDate(request.requestedAt);
                  const location = [readText(request.sector), readText(request.city)].filter(Boolean).join(', ');
                  const title = readText(request.propertyTitle);
                  const bhk = readText(request.bhk);
                  const propertyType = readText(request.propertyType);
                  const preferredTiming = readText(request.preferredVisitTiming);
                  return <article key={request.requestId} className="min-w-0 overflow-hidden rounded-[26px] border border-[#e4e6dc] bg-white shadow-[0_10px_30px_-24px_rgba(15,23,42,.35)] transition-shadow hover:shadow-[0_14px_36px_-22px_rgba(15,23,42,.3)] sm:flex">
                    <div className="aspect-[16/9] min-w-0 bg-[#e8e6df] sm:aspect-auto sm:w-[38%] sm:shrink-0 lg:aspect-square lg:self-center"><PropertyImage src={request.coverImageUrl} alt={title ? `${title} photo` : 'Property photo'} /></div>
                    <div className="flex min-w-0 flex-1 flex-col p-5 sm:p-6">
                      <div className="flex flex-wrap items-center gap-2">
                        <span className={`rounded-full border px-3 py-1.5 text-xs font-semibold ${request.status === 'RECEIVED' ? 'border-emerald-200 bg-emerald-50 text-emerald-900' : 'border-slate-200 bg-slate-100 text-slate-800'}`}>{tenantVisitStatusLabel(request.status)}</span>
                        {date && <span className="text-xs text-slate-600">Requested {date}</span>}
                      </div>
                      <h3 className="mt-4 break-words font-['Outfit'] text-lg font-semibold leading-snug sm:text-xl">{title || 'Property'}</h3>
                      {(bhk || propertyType) && <p className="mt-1 flex flex-wrap items-center gap-x-2 gap-y-1 text-xs font-semibold uppercase tracking-wide text-emerald-800">{bhk && <span className="inline-flex items-center gap-1"><BedDouble size={15} aria-hidden="true" />{bhk}</span>}{propertyType && <span>{propertyType.replace(/_/g, ' ').toLowerCase()}</span>}</p>}
                      {location && <p className="mt-2 flex min-w-0 items-start gap-1.5 text-sm text-slate-600"><MapPin size={16} className="mt-0.5 shrink-0" aria-hidden="true" /><span className="min-w-0 break-words">{location}</span></p>}
                      {preferredTiming && <p className="mt-2 flex min-w-0 items-start gap-1.5 text-sm text-slate-600"><Clock3 size={16} className="mt-0.5 shrink-0" aria-hidden="true" /><span className="min-w-0 break-words">Preferred: {preferredTiming}</span></p>}
                      <div className="mt-auto pt-4">{request.propertyAvailable && Number.isSafeInteger(request.propertyId) && request.propertyId > 0 ? <Link to={`/property/${request.propertyId}`} className={`inline-flex min-h-11 items-center gap-2 text-sm font-semibold text-emerald-800 underline-offset-4 hover:underline ${focusClass}`}>View property <ArrowRight size={16} aria-hidden="true" /></Link> : <p className="text-sm text-slate-600">This property is no longer available to view.</p>}</div>
                    </div>
                  </article>;
                })}
              </div>
              {visibleHistory.hasMore && <div className="mt-6 text-center"><button type="button" onClick={loadMore} disabled={loadMorePending} className={`min-h-11 rounded-xl border border-slate-300 bg-white px-6 text-sm font-semibold text-slate-800 hover:bg-slate-50 disabled:opacity-60 ${focusClass}`}>{loadMorePending ? 'Loading more…' : 'Load more requests'}</button>
                {loadMoreError && <p role="alert" className="mt-2 text-sm text-rose-700">Could not load more requests. Please try again.</p>}</div>}
            </>}
          </section>

          <section id="discover-homes" aria-labelledby="discover-homes-title" className="min-w-0 scroll-mt-24">
            <div className="mb-5 sm:mb-6"><p className="text-xs font-bold uppercase tracking-[0.16em] text-emerald-800">Keep exploring</p>
              <h2 id="discover-homes-title" className="mt-1 font-['Outfit'] text-2xl font-semibold tracking-tight sm:text-3xl">Available homes</h2>
              <p className="mt-1 text-sm text-slate-600">Browse current listings and request a visit when you find a fit.</p>
            </div>
            {discoveryState === 'LOADING' && <div className="grid gap-5 md:grid-cols-2 lg:grid-cols-1 xl:grid-cols-2" role="status" aria-label="Loading available homes">
              {[0, 1, 2].map(index => <div key={index} className="overflow-hidden rounded-[26px] border border-slate-200 bg-white"><div className="aspect-[16/10] bg-slate-200 motion-safe:animate-pulse" /><div className="p-5"><div className="h-5 w-3/4 rounded bg-slate-200 motion-safe:animate-pulse" /><div className="mt-3 h-4 w-1/2 rounded bg-slate-100 motion-safe:animate-pulse" /><div className="mt-6 h-11 rounded-xl bg-slate-100 motion-safe:animate-pulse" /></div></div>)}
            </div>}
            {discoveryState === 'ERROR' && <div className="rounded-[26px] border border-slate-200 bg-white p-6 shadow-sm" role="alert"><p className="text-sm text-slate-700">Available homes could not be loaded right now.</p><button type="button" onClick={onRetryDiscovery} className={`mt-4 min-h-11 rounded-xl bg-emerald-700 px-5 text-sm font-semibold text-white hover:bg-emerald-800 ${focusClass}`}>Retry</button></div>}
            {discoveryState === 'READY' && properties.length === 0 && <div className="rounded-[26px] border border-slate-200 bg-white p-8 text-sm text-slate-600 shadow-sm">No homes match this search. Try another city or search term above.</div>}
            {discoveryState === 'READY' && properties.length > 0 && <div className={`grid min-w-0 gap-5 md:grid-cols-2 lg:grid-cols-1 ${properties.length === 1 ? 'xl:grid-cols-1' : 'xl:grid-cols-2'}`}>
              {properties.map(property => <article key={property.id} className="group min-w-0 overflow-hidden rounded-[26px] border border-[#e4e6dc] bg-white shadow-[0_10px_30px_-24px_rgba(15,23,42,.35)] transition-shadow hover:shadow-[0_14px_36px_-22px_rgba(15,23,42,.3)]">
                <div className="aspect-[16/10] min-w-0 overflow-hidden bg-[#e8e6df]"><PropertyImage src={property.images?.[0]} alt={property.title ? `${property.title} photo` : 'Property photo'} /></div>
                <div className="p-5"><div className="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1 text-xs font-semibold uppercase tracking-wide text-emerald-800">{property.bhk && <span className="inline-flex items-center gap-1"><BedDouble size={15} aria-hidden="true" />{property.bhk}</span>}{property.propertyType && <span>{property.propertyType.toLowerCase()}</span>}</div>
                  <h3 className="mt-2 break-words font-['Outfit'] text-lg font-semibold leading-snug">{property.title?.trim() || 'Property'}</h3>
                  {(property.sector || property.city) && <p className="mt-2 flex min-w-0 items-start gap-1.5 text-sm text-slate-600"><MapPin size={16} className="mt-0.5 shrink-0" aria-hidden="true" /><span className="min-w-0 break-words">{[property.sector, property.city].filter(Boolean).join(', ')}</span></p>}
                  {property.listingType === 'RENT' && property.monthlyRent > 0 && <p className="mt-4 text-xl font-semibold">₹{property.monthlyRent.toLocaleString('en-IN')}<span className="text-sm font-normal text-slate-600"> / month</span></p>}
                  {property.listingType === 'SALE' && property.askingPrice && property.askingPrice > 0 && <p className="mt-4 text-xl font-semibold">₹{property.askingPrice.toLocaleString('en-IN')}</p>}
                  <div className="mt-5 grid grid-cols-2 gap-2 border-t border-slate-100 pt-4"><Link to={`/property/${property.id}`} className={`inline-flex min-h-11 items-center justify-center rounded-xl border border-slate-300 px-2 text-center text-sm font-semibold text-slate-800 hover:bg-slate-50 ${focusClass}`}>View home</Link><button type="button" onClick={() => onRequestVisit(property)} className={`min-h-11 rounded-xl bg-emerald-700 px-2 text-sm font-semibold text-white hover:bg-emerald-800 ${focusClass}`}>Request visit</button></div>
                </div>
              </article>)}
            </div>}
            {discoveryState === 'READY' && hasMoreProperties && <div className="mt-7 text-center"><button type="button" onClick={onLoadMoreProperties} disabled={loadingMoreProperties} className={`min-h-11 rounded-xl border border-slate-300 bg-white px-6 text-sm font-semibold text-slate-800 hover:bg-slate-50 disabled:opacity-60 ${focusClass}`}>{loadingMoreProperties ? 'Loading more…' : 'Load more homes'}</button>{loadMorePropertiesError && <p role="alert" className="mt-2 text-sm text-rose-700">Could not load more homes. Please try again.</p>}</div>}
          </section>
        </div>
      </div>
    </main>
  );
};
