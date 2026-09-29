import React, { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { ArrowRight, Building2, LoaderCircle, Plus, Trash2 } from 'lucide-react';
import { getErrorMessage } from '../services/apiError';
import { LessorDraft, LessorDraftSummary, lessorDraftService } from '../services/lessorDraftService';
import { lessorMediaService } from '../services/lessorMediaService';
import { LessorListingSummary, lessorPortfolioService } from '../services/lessorPortfolioService';
import { LastUpdatedMeta } from './LastUpdatedMeta';
import { lessorStepStorageKey } from '../utils/lessorStepResume';

const BUTTON = 'inline-flex min-h-11 items-center justify-center gap-2 rounded-xl bg-emerald-700 px-5 text-sm font-semibold text-white hover:bg-emerald-800 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500';
const SECONDARY = 'inline-flex min-h-11 items-center justify-center gap-2 rounded-xl border border-slate-300 bg-white px-4 text-sm font-semibold text-slate-700 hover:border-slate-400 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500';
const MONEY = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 });
import { getListingActionLabel, getRevisionNotice, WORKFLOW_STATUS_CONFIG } from '../utils/lessorWorkflow';

function DraftCard({ draft, onOpen, onDelete, deleting }: {
  draft: LessorDraftSummary; onOpen: (id: string) => void; onDelete?: (draft: LessorDraftSummary) => void; deleting?: boolean;
}) {
  return (
    <article className="flex min-w-0 flex-col overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-xs">
      <div className="aspect-[16/9] bg-slate-100">
        {draft.coverUrl ? (
          <img src={draft.coverUrl} alt="Property cover" loading="lazy" className="h-full w-full object-cover" />
        ) : (
          <div className="flex h-full items-center justify-center text-slate-400">
            <Building2 className="h-9 w-9" aria-hidden="true" />
          </div>
        )}
      </div>
      <div className="flex flex-1 flex-col justify-between p-4">
        <div>
          <span className="inline-flex items-center gap-1.5 rounded-full border border-amber-200/80 bg-amber-50 px-2.5 py-0.5 text-xs font-bold uppercase tracking-wider text-amber-900">
            <span className="h-1.5 w-1.5 rounded-full bg-amber-500" aria-hidden="true" />
            Draft · {draft.completionPercent}% complete
          </span>
          <h3 className="mt-3 line-clamp-2 min-h-12 break-words text-lg font-semibold text-slate-950">{draft.title}</h3>
          <p className="mt-1 break-words text-sm text-slate-600">{[draft.locality, draft.city].filter(Boolean).join(', ') || 'Location to add'}</p>
          <p className="mt-2 text-sm font-semibold text-slate-800">{draft.monthlyRent !== null ? `${MONEY.format(draft.monthlyRent)} / month` : 'Rent to add'}</p>
        </div>
        <div className="mt-4 border-t border-slate-100 pt-3">
          <LastUpdatedMeta updatedAt={draft.updatedAt} className="mb-3" />
          <button type="button" onClick={() => onOpen(draft.draftId)}
            className="inline-flex min-h-11 w-full items-center justify-between rounded-xl border border-slate-300 bg-white px-4 text-sm font-semibold text-slate-700 shadow-2xs transition-colors hover:border-slate-400 hover:bg-slate-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500">
            <span>Continue draft</span><ArrowRight className="h-4 w-4 text-slate-400" aria-hidden="true" />
          </button>
          {onDelete && <button type="button" onClick={() => onDelete(draft)} disabled={deleting}
            className="mt-1 inline-flex min-h-11 w-full items-center justify-center gap-2 rounded-xl px-4 text-sm font-semibold text-rose-700 transition-colors hover:bg-rose-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-rose-500 disabled:cursor-not-allowed disabled:opacity-50">
            <Trash2 className="h-4 w-4" aria-hidden="true" />Delete draft
          </button>}
        </div>
      </div>
    </article>
  );
}

function guestDraftSummary(draft: LessorDraft, coverUrl: string | null): LessorDraftSummary {
  const labels = { FLAT: 'Flat', HOUSE: 'House', STUDIO: 'Studio', PENTHOUSE: 'Penthouse', SERVICED_APARTMENT: 'Serviced apartment' };
  const basics = draft.data.basics;
  const title = [basics.bhkCount, labels[basics.propertyType]].filter(Boolean).join(' ') || 'Property listing';
  return {
    draftId: draft.draftId, title, status: draft.status, completionPercent: draft.completionPercent,
    updatedAt: draft.updatedAt, propertyType: basics.propertyType, bhkCount: basics.bhkCount,
    city: draft.data.location?.city || null, locality: draft.data.location?.localityInput || null,
    monthlyRent: draft.data.pricing?.monthlyRent ?? null, coverUrl
  };
}

export function GuestDraftWorkspace({ draft, loading, error, onAdd, onOpenDraft, onRetry }: {
  draft: LessorDraft | null; loading: boolean; error: string; onAdd: () => void;
  onOpenDraft: (id: string) => void; onRetry: () => void;
}) {
  const [coverUrl, setCoverUrl] = useState<string | null>(null);
  useEffect(() => {
    if (!draft || draft.status !== 'DRAFT') { setCoverUrl(null); return; }
    let live = true;
    lessorMediaService.list(draft.draftId, true)
      .then(items => {
        if (!live) return;
        const cover = items.find(item => item.cover && item.contentType.startsWith('image/'));
        setCoverUrl(cover?.url || null);
      })
      .catch(() => { if (live) setCoverUrl(null); });
    return () => { live = false; };
  }, [draft?.draftId, draft?.status]);
  const resumable = draft?.status === 'DRAFT' ? guestDraftSummary(draft, coverUrl) : null;
  return <section>
    <p className="text-xs font-bold uppercase tracking-widest text-emerald-700">Your workspace</p>
    <h1 className="mt-1 font-['Outfit',sans-serif] text-3xl font-bold text-slate-950">Your property drafts</h1>
    <p className="mt-2 text-sm leading-relaxed text-slate-600">Your unfinished property listing is saved in this browser.</p>
    {loading && <p role="status" className="mt-8 text-sm text-slate-600">Checking your saved draft…</p>}
    {error && <div role="alert" className="mt-8 rounded-2xl border border-rose-200 bg-rose-50 p-5 text-sm text-rose-800">{error}<button type="button" className={`${SECONDARY} mt-4 block`} onClick={onRetry}>Try again</button></div>}
    {!loading && !error && resumable && <div className="mt-7 max-w-sm"><DraftCard draft={resumable} onOpen={onOpenDraft} /></div>}
    {!loading && !error && !resumable && <div className="mt-8 max-w-xl rounded-2xl border border-slate-200 bg-white p-6 sm:p-8">
      <Building2 className="h-8 w-8 text-emerald-700" aria-hidden="true" />
      <h2 className="mt-4 text-xl font-semibold text-slate-950">Your property listing starts here</h2>
      <p className="mt-2 text-sm text-slate-600">Start with the basics. You can return to finish in this browser.</p>
      <button type="button" className={`${BUTTON} mt-5`} onClick={onAdd}>Post Your Property</button>
    </div>}
  </section>;
}

export function LessorPortfolio({ userId = null, onAdd, onOpenDraft, onOpenListing, draftsOnly = false, mainActionLabel = 'Add property' }: {
  userId?: number | null; onAdd: () => void; onOpenDraft: (id: string) => void; onOpenListing: (id: number) => void; draftsOnly?: boolean; mainActionLabel?: string;
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
  const [confirmDraft, setConfirmDraft] = useState<LessorDraftSummary | null>(null);
  const [deleteState, setDeleteState] = useState<'confirm' | 'deleting' | 'success'>('confirm');
  const [deleteError, setDeleteError] = useState('');
  const [deletingDraftId, setDeletingDraftId] = useState<string | null>(null);
  const dialogRef = useRef<HTMLDialogElement>(null);
  const draftRequestRevision = useRef(0);

  const loadDrafts = async (page: number) => {
    const revision = ++draftRequestRevision.current;
    setLoadingDrafts(true); setDraftError('');
    try {
      const result = await lessorDraftService.list(page);
      if (revision !== draftRequestRevision.current) return;
      setDrafts(previous => page === 0 ? result.items : [...previous, ...result.items]);
      setDraftPage(page); setMoreDrafts(result.hasMore);
    } catch (cause) {
      if (revision === draftRequestRevision.current) setDraftError(getErrorMessage(cause, 'Could not load drafts.'));
    } finally {
      if (revision === draftRequestRevision.current) setLoadingDrafts(false);
    }
  };
  const loadListings = async (page: number) => {
    setLoadingListings(true); setListingError('');
    try { const result = await lessorPortfolioService.list(page); setListings(previous => page === 0 ? result.items : [...previous, ...result.items]); setListingPage(page); setMoreListings(result.hasMore); }
    catch (cause) { setListingError(getErrorMessage(cause, 'Could not load properties.')); }
    finally { setLoadingListings(false); }
  };
  useEffect(() => { void loadDrafts(0); if (!draftsOnly) void loadListings(0); else setLoadingListings(false); }, [draftsOnly]);

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    if (confirmDraft && !dialog.open) dialog.showModal();
    if (!confirmDraft && dialog.open) dialog.close();
  }, [confirmDraft]);

  const openDeleteConfirmation = (draft: LessorDraftSummary) => {
    setDeleteError('');
    setDeleteState('confirm');
    setConfirmDraft(draft);
  };

  const deleteDraft = async () => {
    if (!confirmDraft || deletingDraftId) return;
    const draft = confirmDraft;
    setDeletingDraftId(draft.draftId);
    setDeleteState('deleting');
    setDeleteError('');
    try {
      await lessorDraftService.discard(draft.draftId);
      setDrafts(previous => previous.filter(item => item.draftId !== draft.draftId));
      const stepKey = lessorStepStorageKey(draft.draftId, userId, false);
      if (stepKey) localStorage.removeItem(stepKey);
      window.dispatchEvent(new Event('pathome_lessor_drafts_changed'));
      setDeleteState('success');
      void loadDrafts(0);
    } catch (cause) {
      setDeleteError(getErrorMessage(cause, "We couldn't confirm deletion. Refresh your drafts and try again."));
      setDeleteState('confirm');
    } finally {
      setDeletingDraftId(null);
    }
  };

  const visibleDrafts = drafts.filter(draft => draft.status === 'DRAFT');
  const empty = !loadingDrafts && !loadingListings && !draftError && !listingError && visibleDrafts.length === 0 && listings.length === 0;
  return <section>
    <div className="flex flex-wrap items-end justify-between gap-4">
      <div>
        <p className="text-xs font-bold uppercase tracking-widest text-emerald-700">Your workspace</p>
        <h1 className="mt-1 font-['Outfit',sans-serif] text-3xl font-bold text-slate-950">{draftsOnly ? 'Your property drafts' : 'My Properties'}</h1>
        {draftsOnly && <p className="mt-2 text-sm leading-relaxed text-slate-600">Your unfinished property listings are saved here.</p>}
      </div>
      <div className="flex flex-wrap items-center gap-3">
        <Link
          to="/"
          className="inline-flex min-h-11 items-center justify-center gap-1.5 rounded-xl border border-slate-300 bg-white px-3.5 text-sm font-semibold text-slate-700 shadow-2xs transition-colors hover:border-slate-400 hover:bg-slate-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 cursor-pointer"
        >
          <Building2 className="h-4 w-4 text-slate-500" />
          <span>Browse rental homes</span>
        </Link>
        <button className={BUTTON} onClick={onAdd}><Plus className="h-4 w-4"/>{mainActionLabel}</button>
      </div>
    </div>
    {empty && <div className="mt-10 max-w-xl rounded-2xl border border-slate-200 bg-white p-7"><Building2 className="mb-4 h-7 w-7 text-emerald-700"/><h2 className="text-xl font-semibold text-slate-950">{draftsOnly ? 'Your property listing starts here' : 'Your first property starts here'}</h2><p className="mt-2 text-sm text-slate-600">Add the basics now. You can return to finish later.</p><div className="mt-5 flex flex-wrap items-center gap-3"><button className={BUTTON} onClick={onAdd}>{mainActionLabel}</button><Link to="/" className={SECONDARY}>Browse rental homes</Link></div></div>}
    {(loadingDrafts || loadingListings) && visibleDrafts.length === 0 && listings.length === 0 && <div aria-label="Loading properties" className="mt-8 grid gap-4 sm:grid-cols-2">{[1, 2].map(number => <div key={number} className="overflow-hidden rounded-2xl border border-slate-200 bg-white"><div className="aspect-[16/9] animate-pulse motion-reduce:animate-none bg-slate-200"/><div className="space-y-3 p-4"><div className="h-4 w-24 animate-pulse motion-reduce:animate-none rounded bg-slate-100"/><div className="h-6 w-3/4 animate-pulse motion-reduce:animate-none rounded bg-slate-100"/><div className="h-4 w-1/2 animate-pulse motion-reduce:animate-none rounded bg-slate-100"/><div className="h-11 w-28 animate-pulse motion-reduce:animate-none rounded bg-slate-100"/></div></div>)}</div>}
    {(visibleDrafts.length > 0 || draftError) && <div className="mt-9">{!draftsOnly && <h2 className="text-lg font-semibold text-slate-950">Drafts</h2>}{draftError && <p role="alert" className="mt-3 rounded-xl border border-rose-200 bg-rose-50 p-4 text-sm text-rose-800">{draftError}<button className="ml-3 underline" onClick={() => { void loadDrafts(draftPage); }}>Retry</button></p>}
      <div className="mt-4 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">{visibleDrafts.map(draft => <DraftCard key={draft.draftId} draft={draft} onOpen={onOpenDraft} onDelete={openDeleteConfirmation} deleting={deletingDraftId === draft.draftId} />)}</div>{moreDrafts && <button disabled={loadingDrafts} className={`${SECONDARY} mt-5`} onClick={() => { void loadDrafts(draftPage + 1); }}>{loadingDrafts ? 'Loading…' : 'Show more drafts'}</button>}</div>}
    {!draftsOnly && (listings.length > 0 || listingError) && <div className="mt-10"><h2 className="text-lg font-semibold text-slate-950">Submitted and managed properties</h2>{listingError && <p role="alert" className="mt-3 rounded-xl border border-rose-200 bg-rose-50 p-4 text-sm text-rose-800">{listingError}<button className="ml-3 underline" onClick={() => { void loadListings(listingPage); }}>Retry</button></p>}
      <div className="mt-4 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">{listings.map(listing => {
        const statusInfo = WORKFLOW_STATUS_CONFIG[listing.status] || {
          label: listing.status,
          guidance: '',
          badgeClass: 'bg-slate-100 text-slate-800 border-slate-200',
          dotClass: 'bg-slate-400'
        };
        const revisionNotice = getRevisionNotice(listing.status, listing.openRevisionStatus);

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

                {revisionNotice && (
                  <div className="mt-2.5 rounded-xl border border-amber-200/90 bg-amber-50/80 p-2.5 text-xs text-amber-950">
                    <p className="font-semibold flex items-center gap-1.5">
                      <span className="h-1.5 w-1.5 rounded-full bg-amber-600" aria-hidden="true" />
                      {revisionNotice.title}
                    </p>
                    <p className="mt-0.5 text-[11px] text-amber-800">
                      {revisionNotice.message}
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
                    {getListingActionLabel(listing.status, listing.openRevisionStatus)}
                  </span>
                  <ArrowRight className="h-4 w-4 text-slate-400" />
                </button>
              </div>
            </div>
          </article>
        );
      })}</div>{moreListings && <button disabled={loadingListings} className={`${SECONDARY} mt-5`} onClick={() => { void loadListings(listingPage + 1); }}>{loadingListings ? 'Loading…' : 'Show more properties'}</button>}</div>}
    <dialog ref={dialogRef} aria-labelledby="delete-draft-title" aria-describedby="delete-draft-description"
      onCancel={event => { if (deletingDraftId) event.preventDefault(); else setConfirmDraft(null); }}
      onClose={() => { if (!deletingDraftId) setConfirmDraft(null); }}
      className="m-auto max-h-[calc(100dvh-2rem)] w-[calc(100%-2rem)] max-w-lg overflow-y-auto rounded-2xl border border-slate-200 bg-white p-0 text-slate-900 shadow-2xl backdrop:bg-slate-950/55">
      {confirmDraft && <div className="p-5 sm:p-6">
        {deleteState === 'success' ? <>
          <h2 id="delete-draft-title" className="font-['Outfit',sans-serif] text-2xl font-bold">Draft deleted</h2>
          <p id="delete-draft-description" role="status" aria-live="polite" className="mt-3 text-sm leading-relaxed text-slate-600">
            {confirmDraft.title} was removed from your drafts. Uploaded media cleanup will continue automatically.
          </p>
          <button type="button" autoFocus onClick={() => setConfirmDraft(null)} className={`${BUTTON} mt-6 w-full sm:w-auto`}>Done</button>
        </> : <>
          <p className="text-xs font-bold uppercase tracking-widest text-rose-700">Delete unfinished listing</p>
          <h2 id="delete-draft-title" className="mt-1 font-['Outfit',sans-serif] text-2xl font-bold">Delete this draft?</h2>
          <p id="delete-draft-description" className="mt-2 text-sm leading-relaxed text-slate-600">
            This removes the unfinished listing from your drafts. Any published property stays unchanged. Uploaded media cleanup continues automatically.
          </p>
          <div className="mt-5 flex min-w-0 items-center gap-3 rounded-xl border border-slate-200 bg-slate-50 p-3">
            <div className="h-16 w-20 shrink-0 overflow-hidden rounded-lg bg-slate-200">
              {confirmDraft.coverUrl ? <img src={confirmDraft.coverUrl} alt="" className="h-full w-full object-cover" />
                : <div className="flex h-full items-center justify-center"><Building2 className="h-6 w-6 text-slate-500" aria-hidden="true" /></div>}
            </div>
            <div className="min-w-0">
              <p className="break-words text-sm font-semibold text-slate-950">{confirmDraft.title}</p>
              <p className="mt-1 break-words text-xs text-slate-600">{[confirmDraft.locality, confirmDraft.city].filter(Boolean).join(', ') || 'Location to add'}</p>
              <p className="mt-1 text-xs text-slate-600">{confirmDraft.monthlyRent !== null ? `${MONEY.format(confirmDraft.monthlyRent)} / month` : 'Rent to add'}</p>
            </div>
          </div>
          {deleteError && <p role="alert" className="mt-4 rounded-xl border border-rose-200 bg-rose-50 p-3 text-sm text-rose-800">{deleteError}</p>}
          <div className="mt-6 flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
            <button
              type="button"
              autoFocus={deleteState !== 'deleting'}
              disabled={deleteState === 'deleting'}
              onClick={() => setConfirmDraft(null)}
              className={`${SECONDARY} w-full disabled:cursor-not-allowed disabled:opacity-60 sm:w-auto`}
            >
              Cancel
            </button>
            <button
              type="button"
              disabled={deleteState === 'deleting'}
              aria-busy={deleteState === 'deleting'}
              onClick={() => { void deleteDraft(); }}
              className="inline-flex min-h-11 w-full items-center justify-center gap-2 rounded-xl bg-rose-700 px-4 text-sm font-semibold text-white transition-colors hover:bg-rose-800 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-rose-500 disabled:cursor-not-allowed disabled:opacity-60 sm:w-auto"
            >
              {deleteState === 'deleting' ? (
                <>
                  <LoaderCircle className="h-4 w-4 animate-spin motion-reduce:animate-none" aria-hidden="true" />
                  <span>Deleting…</span>
                </>
              ) : (
                deleteError ? 'Retry delete' : 'Delete draft'
              )}
            </button>
          </div>
        </>}
      </div>}
    </dialog>
  </section>;
}
