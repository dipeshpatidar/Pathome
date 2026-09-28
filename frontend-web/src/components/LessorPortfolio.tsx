import React, { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { ArrowRight, Building2, Plus } from 'lucide-react';
import { getErrorMessage } from '../services/apiError';
import { LessorDraftSummary, lessorDraftService } from '../services/lessorDraftService';
import { LessorListingSummary, lessorPortfolioService } from '../services/lessorPortfolioService';
import { LastUpdatedMeta } from './LastUpdatedMeta';

const BUTTON = 'inline-flex min-h-11 items-center justify-center gap-2 rounded-xl bg-emerald-700 px-5 text-sm font-semibold text-white hover:bg-emerald-800 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500';
const SECONDARY = 'inline-flex min-h-11 items-center justify-center gap-2 rounded-xl border border-slate-300 bg-white px-4 text-sm font-semibold text-slate-700 hover:border-slate-400 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500';
const MONEY = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 });
import { WORKFLOW_STATUS_CONFIG } from '../utils/lessorWorkflow';

export function LessorPortfolio({ onAdd, onOpenDraft, onOpenListing }: {
  onAdd: () => void; onOpenDraft: (id: string) => void; onOpenListing: (id: number) => void;
}) {
  const [drafts, setDrafts] = useState<LessorDraftSummary[]>([]);
  const [listings, setListings] = useState<LessorListingSummary[]>([]);
  const [draftPage, setDraftPage] = useState(0);
  const [listingPage, setListingPage] = useState(0);
  const [moreDrafts, setMoreDrafts] = useState(false);
  const [moreListings, setMoreListings] = useState(false);
  const [loadingDrafts, setLoadingDrafts] = useState(true);
  const [loadingListings, setLoadingListings] = useState(true);
  const [draftError, setDraftError] = useState('');
  const [listingError, setListingError] = useState('');

  const loadDrafts = async (page: number) => {
    setLoadingDrafts(true); setDraftError('');
    try { const result = await lessorDraftService.list(page); setDrafts(previous => page === 0 ? result.items : [...previous, ...result.items]); setDraftPage(page); setMoreDrafts(result.hasMore); }
    catch (cause) { setDraftError(getErrorMessage(cause, 'Could not load drafts.')); }
    finally { setLoadingDrafts(false); }
  };
  const loadListings = async (page: number) => {
    setLoadingListings(true); setListingError('');
    try { const result = await lessorPortfolioService.list(page); setListings(previous => page === 0 ? result.items : [...previous, ...result.items]); setListingPage(page); setMoreListings(result.hasMore); }
    catch (cause) { setListingError(getErrorMessage(cause, 'Could not load properties.')); }
    finally { setLoadingListings(false); }
  };
  useEffect(() => { void loadDrafts(0); void loadListings(0); }, []);

  const empty = !loadingDrafts && !loadingListings && !draftError && !listingError && drafts.length === 0 && listings.length === 0;
  return <section>
    <div className="flex flex-wrap items-end justify-between gap-4">
      <div>
        <p className="text-xs font-bold uppercase tracking-widest text-emerald-700">Your workspace</p>
        <h1 className="mt-1 font-['Outfit',sans-serif] text-3xl font-bold text-slate-950">My Properties</h1>
      </div>
      <div className="flex flex-wrap items-center gap-3">
        <Link
          to="/"
          className="inline-flex min-h-11 items-center justify-center gap-1.5 rounded-xl border border-slate-300 bg-white px-3.5 text-sm font-semibold text-slate-700 shadow-2xs transition-colors hover:border-slate-400 hover:bg-slate-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 cursor-pointer"
        >
          <Building2 className="h-4 w-4 text-slate-500" />
          <span>Browse rental homes</span>
        </Link>
        <button className={BUTTON} onClick={onAdd}><Plus className="h-4 w-4"/>Add property</button>
      </div>
    </div>
    {empty && <div className="mt-10 max-w-xl rounded-2xl border border-slate-200 bg-white p-7"><Building2 className="mb-4 h-7 w-7 text-emerald-700"/><h2 className="text-xl font-semibold text-slate-950">Your first property starts here</h2><p className="mt-2 text-sm text-slate-600">Add the basics now. You can return to finish later.</p><div className="mt-5 flex flex-wrap items-center gap-3"><button className={BUTTON} onClick={onAdd}>Add property</button><Link to="/" className={SECONDARY}>Browse rental homes</Link></div></div>}
    {(loadingDrafts || loadingListings) && drafts.length === 0 && listings.length === 0 && <div aria-label="Loading properties" className="mt-8 grid gap-4 sm:grid-cols-2">{[1, 2].map(number => <div key={number} className="overflow-hidden rounded-2xl border border-slate-200 bg-white"><div className="aspect-[16/9] animate-pulse motion-reduce:animate-none bg-slate-200"/><div className="space-y-3 p-4"><div className="h-4 w-24 animate-pulse motion-reduce:animate-none rounded bg-slate-100"/><div className="h-6 w-3/4 animate-pulse motion-reduce:animate-none rounded bg-slate-100"/><div className="h-4 w-1/2 animate-pulse motion-reduce:animate-none rounded bg-slate-100"/><div className="h-11 w-28 animate-pulse motion-reduce:animate-none rounded bg-slate-100"/></div></div>)}</div>}
    {(drafts.length > 0 || draftError) && <div className="mt-9"><h2 className="text-lg font-semibold text-slate-950">Drafts</h2>{draftError && <p role="alert" className="mt-3 rounded-xl border border-rose-200 bg-rose-50 p-4 text-sm text-rose-800">{draftError}<button className="ml-3 underline" onClick={() => { void loadDrafts(draftPage); }}>Retry</button></p>}
      <div className="mt-4 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">{drafts.map(draft => {
        return (
          <article key={draft.draftId} className="flex flex-col min-w-0 overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-xs">
            <div className="aspect-[16/9] bg-slate-100">
              {draft.coverUrl ? (
                <img src={draft.coverUrl} alt="Property cover" loading="lazy" className="h-full w-full object-cover"/>
              ) : (
                <div className="flex h-full items-center justify-center text-slate-400">
                  <Building2 className="h-9 w-9" aria-hidden="true"/>
                </div>
              )}
            </div>
            <div className="flex flex-1 flex-col justify-between p-4">
              <div>
                <div className="flex flex-wrap items-center gap-1.5">
                  <span className="inline-flex items-center gap-1.5 rounded-full border border-amber-200/80 bg-amber-50 px-2.5 py-0.5 text-xs font-bold uppercase tracking-wider text-amber-900">
                    <span className="h-1.5 w-1.5 rounded-full bg-amber-500" aria-hidden="true" />
                    <span>Draft · {draft.completionPercent}% complete</span>
                  </span>
                </div>
                <p className="mt-1 text-xs text-slate-500">Finish your listing when you're ready.</p>
                <h3 className="mt-2.5 line-clamp-2 min-h-12 break-words text-lg font-semibold text-slate-950">{draft.title}</h3>
                <p className="mt-1 truncate text-sm text-slate-600">{[draft.locality, draft.city].filter(Boolean).join(', ') || 'Location to add'}</p>
                <p className="mt-2 text-sm font-semibold text-slate-800">{draft.monthlyRent !== null ? `${MONEY.format(draft.monthlyRent)} / month` : 'Rent to add'}</p>
              </div>
              <div className="mt-4 border-t border-slate-100 pt-3">
                <LastUpdatedMeta updatedAt={draft.updatedAt} className="mb-3" />
                <button
                  type="button"
                  onClick={() => onOpenDraft(draft.draftId)}
                  className="w-full inline-flex min-h-11 items-center justify-between rounded-xl border border-slate-300 bg-white px-4 text-sm font-semibold text-slate-700 shadow-2xs transition-all hover:border-slate-400 hover:bg-slate-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 cursor-pointer"
                >
                  <span>Continue draft</span>
                  <ArrowRight className="h-4 w-4 text-slate-400" />
                </button>
              </div>
            </div>
          </article>
        );
      })}</div>{moreDrafts && <button disabled={loadingDrafts} className={`${SECONDARY} mt-5`} onClick={() => { void loadDrafts(draftPage + 1); }}>{loadingDrafts ? 'Loading…' : 'Show more drafts'}</button>}</div>}
    {(listings.length > 0 || listingError) && <div className="mt-10"><h2 className="text-lg font-semibold text-slate-950">Submitted and managed properties</h2>{listingError && <p role="alert" className="mt-3 rounded-xl border border-rose-200 bg-rose-50 p-4 text-sm text-rose-800">{listingError}<button className="ml-3 underline" onClick={() => { void loadListings(listingPage); }}>Retry</button></p>}
      <div className="mt-4 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">{listings.map(listing => {
        const statusInfo = WORKFLOW_STATUS_CONFIG[listing.status] || {
          label: listing.status,
          guidance: '',
          badgeClass: 'bg-slate-100 text-slate-800 border-slate-200',
          dotClass: 'bg-slate-400'
        };

        return (
          <article key={listing.listingId} className="flex flex-col min-w-0 overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-xs">
            <div className="aspect-[16/9] bg-slate-100">
              {listing.coverUrl ? (
                <img src={listing.coverUrl} alt="Property cover" loading="lazy" className="h-full w-full object-cover"/>
              ) : (
                <div className="flex h-full items-center justify-center text-slate-400">
                  <Building2 className="h-9 w-9" aria-hidden="true"/>
                </div>
              )}
            </div>
            <div className="flex flex-1 flex-col justify-between p-4">
              <div>
                <div className="flex flex-wrap items-center gap-1.5">
                  <span className={`inline-flex items-center gap-1.5 rounded-full border px-2.5 py-0.5 text-xs font-bold uppercase tracking-wider ${statusInfo.badgeClass}`}>
                    <span className={`h-1.5 w-1.5 rounded-full ${statusInfo.dotClass}`} aria-hidden="true" />
                    <span>{statusInfo.label}</span>
                  </span>
                </div>
                <p className="mt-1 text-xs text-slate-500">{statusInfo.guidance}</p>

                {listing.openRevisionStatus && (
                  <div className="mt-2.5 rounded-xl border border-amber-200/90 bg-amber-50/80 p-2.5 text-xs text-amber-950">
                    <p className="font-semibold flex items-center gap-1.5">
                      <span className="h-1.5 w-1.5 rounded-full bg-amber-600" aria-hidden="true" />
                      {listing.openRevisionStatus === 'REVIEW'
                        ? 'Changes under review'
                        : listing.openRevisionStatus === 'CHANGES_REQUIRED'
                        ? 'Revision changes requested'
                        : 'Revision draft in progress'}
                    </p>
                    <p className="mt-0.5 text-[11px] text-amber-800">
                      {listing.openRevisionStatus === 'REVIEW'
                        ? 'Your live listing remains active while changes are reviewed.'
                        : listing.openRevisionStatus === 'CHANGES_REQUIRED'
                        ? 'Review requested edits to update your listing.'
                        : 'You have unfinished edits for this property.'}
                    </p>
                  </div>
                )}

                <h3 className="mt-2.5 line-clamp-2 min-h-12 break-words text-lg font-semibold text-slate-950">{listing.title}</h3>
                <p className="mt-1 truncate text-sm text-slate-600">{listing.locality}, {listing.city}</p>
                <p className="mt-2 text-sm font-semibold text-slate-800">{MONEY.format(listing.monthlyRent)} / month</p>
              </div>
              <div className="mt-4 border-t border-slate-100 pt-3">
                <LastUpdatedMeta updatedAt={listing.updatedAt} className="mb-3" />
                <button
                  type="button"
                  onClick={() => onOpenListing(listing.listingId)}
                  className="w-full inline-flex min-h-11 items-center justify-between rounded-xl border border-slate-300 bg-white px-4 text-sm font-semibold text-slate-700 shadow-2xs transition-all hover:border-slate-400 hover:bg-slate-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 cursor-pointer"
                >
                  <span>
                    {listing.status === 'CHANGES_REQUIRED' || listing.openRevisionStatus === 'CHANGES_REQUIRED'
                      ? 'Review changes'
                      : 'Preview property'}
                  </span>
                  <ArrowRight className="h-4 w-4 text-slate-400" />
                </button>
              </div>
            </div>
          </article>
        );
      })}</div>{moreListings && <button disabled={loadingListings} className={`${SECONDARY} mt-5`} onClick={() => { void loadListings(listingPage + 1); }}>{loadingListings ? 'Loading…' : 'Show more properties'}</button>}</div>}
  </section>;
}
