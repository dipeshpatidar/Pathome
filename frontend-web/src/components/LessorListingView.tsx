import React, { useEffect, useState } from 'react';
import { ArrowLeft, Building2, LoaderCircle } from 'lucide-react';
import { getErrorMessage } from '../services/apiError';
import { LessorListingDetail, lessorPortfolioService } from '../services/lessorPortfolioService';
import { LessorListingErrorState } from './LessorListingErrorState';

const SECONDARY = 'inline-flex min-h-11 items-center justify-center gap-2 rounded-xl border border-slate-300 bg-white px-4 text-sm font-semibold text-slate-700 hover:bg-slate-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500';
const LABELS: Record<string, string> = {
  SUBMITTED: 'Submitted for review',
  UNDER_REVIEW: 'Under review',
  CHANGES_REQUIRED: 'Changes required',
  PUBLISHED: 'Published',
  PAUSED: 'Paused',
  ARCHIVED: 'Archived'
};

const STATUS_GUIDANCE: Record<string, string> = {
  SUBMITTED: 'Pathome has received your property for review.',
  UNDER_REVIEW: 'Our team is reviewing your property.',
  CHANGES_REQUIRED: 'Updates are needed before this property can be published.',
  PUBLISHED: 'Your property is live.',
  PAUSED: 'This property is currently not visible to renters.',
  ARCHIVED: 'This property has been archived.'
};

const MONEY = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 });

export function LessorListingView({ listingId, onBack, onOpenDraft }: { listingId: number; onBack: () => void; onOpenDraft: (id: string) => void }) {
  const [detail, setDetail] = useState<LessorListingDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [confirmArchive, setConfirmArchive] = useState(false);
  const load = async () => {
    setLoading(true); setDetail(null); setError('');
    try { setDetail(await lessorPortfolioService.get(listingId)); }
    catch (cause) { setError(getErrorMessage(cause, 'Unable to open this property.')); }
    finally { setLoading(false); }
  };
  useEffect(() => { void load(); }, [listingId]);

  const startRevision = async () => {
    if (busy) return;
    setBusy(true); setError('');
    try { const draft = await lessorPortfolioService.startRevision(listingId); onOpenDraft(draft.draftId); }
    catch (cause) { setError(getErrorMessage(cause, 'Unable to start a revision.')); }
    finally { setBusy(false); }
  };
  const updateStatus = async (action: 'pause' | 'archive' | 'resume-review') => {
    if (busy || !detail) return;
    setBusy(true); setError('');
    try { await lessorPortfolioService.changeStatus(listingId, detail.version, action); setConfirmArchive(false); await load(); }
    catch (cause) { setError(getErrorMessage(cause, 'Unable to update this property.')); }
    finally { setBusy(false); }
  };

  if (loading) return <div className="flex min-h-48 items-center justify-center gap-2 text-slate-600"><LoaderCircle className="h-5 w-5 animate-spin"/>Opening property…</div>;
  if (!detail) return <LessorListingErrorState
    error={error || 'Unable to open this property.'}
    onRetry={() => { void load(); }}
    onBack={onBack}
  />;
  const property = detail.preview;
  const cover = property.media.find(item => item.cover && item.contentType.startsWith('image/')) || property.media.find(item => item.contentType.startsWith('image/'));
  return <section className="mx-auto max-w-3xl">
    <button className={SECONDARY} onClick={onBack}><ArrowLeft className="h-4 w-4"/>My Properties</button>
    <div className="mt-8 flex flex-wrap items-center justify-between gap-2">
      <p className="text-xs font-bold uppercase tracking-widest text-emerald-700">Your property</p>
      <p className="rounded-full bg-slate-100 px-3 py-1 text-xs font-semibold text-slate-700">{LABELS[detail.status] || 'Status unavailable'}</p>
    </div>
    {STATUS_GUIDANCE[detail.status] && (
      <p className="mt-1 text-xs text-slate-500">{STATUS_GUIDANCE[detail.status]}</p>
    )}
    <h1 className="mt-2 break-words font-['Outfit',sans-serif] text-3xl font-bold text-slate-950">{property.title}</h1>
    <p className="mt-2 text-sm text-slate-600">{[property.locality, property.city].filter(Boolean).join(', ')}</p>
    <p className="mt-3 text-sm text-slate-600">This preview leaves out your private address and contact details.</p>
    {detail.reviewNote && <div role="status" className="mt-5 rounded-xl border border-amber-200 bg-amber-50 p-4 text-sm text-amber-950"><p className="font-semibold">Reviewer note</p><p className="mt-1 whitespace-pre-wrap">{detail.reviewNote}</p></div>}
    <div className="mt-6 overflow-hidden rounded-2xl border border-slate-200 bg-white"><div className="aspect-[16/9] bg-slate-100">{cover?.url ? <img src={cover.url} alt="Property cover" className="h-full w-full object-cover"/> : <div className="flex h-full items-center justify-center text-slate-400"><Building2 className="h-10 w-10" aria-hidden="true"/></div>}</div>
      <div className="p-5"><p className="text-xl font-bold text-slate-950">{property.monthlyRent !== null ? MONEY.format(property.monthlyRent) : 'Rent unavailable'} <span className="text-sm font-normal text-slate-500">/ month</span></p><p className="mt-1 text-sm text-slate-600">Deposit: {property.securityDeposit !== null ? MONEY.format(property.securityDeposit) : 'Unavailable'}</p><div className="mt-5 grid gap-2 text-sm text-slate-700 sm:grid-cols-2"><span>Available: {property.availableFrom || 'Not specified'}</span>{property.totalAreaSqFt && <span>{property.totalAreaSqFt} sq ft</span>}{property.furnishingStatus && <span>{property.furnishingStatus.replace(/_/g, ' ').toLowerCase()}</span>}</div>{property.description && <p className="mt-5 whitespace-pre-wrap text-sm leading-relaxed text-slate-700">{property.description}</p>}</div></div>
    {property.media.length > 1 && <div className="mt-4 grid grid-cols-2 gap-2 sm:grid-cols-3">{property.media.filter(item => item !== cover && item.url).map(item => item.contentType.startsWith('image/') ? <img key={item.mediaId} src={item.url || undefined} alt="Additional property photo" loading="lazy" className="aspect-[4/3] w-full rounded-xl object-cover"/> : <video key={item.mediaId} controls preload="metadata" src={item.url || undefined} className="aspect-[4/3] w-full rounded-xl bg-slate-900"/>)}</div>}
    <div className="mt-6 flex flex-wrap gap-3">
      {detail.openRevisionStatus === 'DRAFT' && detail.openRevisionDraftId && <button className={SECONDARY} onClick={() => onOpenDraft(detail.openRevisionDraftId!)}>Continue revision</button>}
      {detail.openRevisionStatus === 'CHANGES_REQUIRED' && <button disabled={busy} className={SECONDARY} onClick={() => { void startRevision(); }}>{busy ? 'Opening…' : 'Review requested changes'}</button>}
      {detail.openRevisionStatus === 'REVIEW' && <p role="status" className="rounded-xl bg-amber-50 px-4 py-3 text-sm text-amber-900">Your revised details have been submitted for review. The approved listing remains unchanged until approval.</p>}
      {!detail.openRevisionDraftId && ['PUBLISHED', 'PAUSED', 'CHANGES_REQUIRED'].includes(detail.status) && <button disabled={busy} className={SECONDARY} onClick={() => { void startRevision(); }}>{busy ? 'Opening…' : detail.status === 'CHANGES_REQUIRED' ? 'Review changes' : 'Edit details for review'}</button>}
      {detail.status === 'PUBLISHED' && <button disabled={busy} className={SECONDARY} onClick={() => { void updateStatus('pause'); }}>Pause listing</button>}
      {detail.status === 'PAUSED' && <button disabled={busy} className={SECONDARY} onClick={() => { void updateStatus('resume-review'); }}>Request review to resume</button>}
      {['PUBLISHED', 'PAUSED'].includes(detail.status) && !confirmArchive && <button disabled={busy} className={SECONDARY} onClick={() => setConfirmArchive(true)}>Archive listing</button>}
    </div>
    {confirmArchive && <div className="mt-4 rounded-xl border border-amber-200 bg-amber-50 p-4 text-sm text-amber-950"><p className="font-semibold">Archive this listing?</p><p className="mt-1">It will no longer appear in public search, and this action cannot be undone here.</p><div className="mt-3 flex flex-wrap gap-2"><button disabled={busy} className={SECONDARY} onClick={() => { void updateStatus('archive'); }}>Confirm archive</button><button disabled={busy} className={SECONDARY} onClick={() => setConfirmArchive(false)}>Cancel</button></div></div>}
    {error && <p role="alert" className="mt-5 text-sm text-rose-700">{error}</p>}
  </section>;
}
