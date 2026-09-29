import React, { useEffect, useRef, useState } from 'react';
import { ArrowRight, LoaderCircle, Pencil, RefreshCw } from 'lucide-react';
import { getErrorMessage } from '../services/apiError';
import { LessorPreview, LessorSubmission, lessorSubmissionService } from '../services/lessorSubmissionService';
import { LessorMediaItem, lessorMediaService } from '../services/lessorMediaService';
import { lessorContactService } from '../services/lessorContactService';
import { LessorContactModal } from './LessorContactModal';
import { LessorMediaAsset } from './LessorMediaAsset';
import { displayMediaName } from '../utils/lessorMedia';

const SECONDARY = 'inline-flex min-h-11 items-center justify-center gap-2 rounded-xl border border-slate-300 bg-white px-4 text-sm font-semibold text-slate-700 hover:bg-slate-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500';
const MONEY = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 });

function SummarySection({ title, onEdit, children }: { title: string; onEdit: () => void; children: React.ReactNode }) {
  return <section className="min-w-0 rounded-2xl border border-slate-200 bg-white p-4 shadow-xs sm:p-5">
    <div className="flex min-w-0 items-start justify-between gap-3">
      <h2 className="font-['Outfit',sans-serif] text-lg font-semibold text-slate-950">{title}</h2>
      <button type="button" onClick={onEdit} aria-label={`Edit ${title.toLowerCase()}`}
        className="inline-flex min-h-11 shrink-0 items-center gap-1.5 rounded-xl px-3 text-sm font-semibold text-emerald-800 transition-colors hover:bg-emerald-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500">
        <Pencil className="h-3.5 w-3.5" aria-hidden="true" />Edit
      </button>
    </div>
    <div className="mt-1 min-w-0 break-words text-sm leading-relaxed text-slate-700">{children}</div>
  </section>;
}

export function LessorPreviewStep({ draftId, onEdit, onSubmitted, guest = false, onGuestSubmit,
  hasActiveUploads = false, mediaItems = [] }: {
  draftId: string; onEdit: (section: 'basics' | 'pricing' | 'location' | 'media' | 'details') => void;
  onSubmitted: (submission: LessorSubmission) => void; guest?: boolean; onGuestSubmit?: () => void;
  hasActiveUploads?: boolean; mediaItems?: LessorMediaItem[];
}) {
  const [preview, setPreview] = useState<LessorPreview | null>(null);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [preparing, setPreparing] = useState(false);
  const [error, setError] = useState('');
  const [showContactModal, setShowContactModal] = useState(false);
  const [contactInitial, setContactInitial] = useState<{ name: string; phone: string }>({ name: '', phone: '' });
  const submittingRef = useRef(false);
  const attemptedPromotion = useRef('');

  const load = async () => {
    setLoading(true); setError('');
    try { setPreview(await lessorSubmissionService.preview(draftId, guest)); }
    catch (cause) { setError(getErrorMessage(cause, 'Could not load your preview.')); }
    finally { setLoading(false); }
  };
  useEffect(() => { void load(); }, [draftId, guest, mediaItems]);

  useEffect(() => {
    if (guest || !preview) return;
    const staged = preview.media.filter(item => item.status === 'STAGED').map(item => item.mediaId).join(',');
    if (!staged || attemptedPromotion.current === staged) return;
    attemptedPromotion.current = staged;
    setPreparing(true);
    setError('');
    void lessorMediaService.promote(draftId)
      .then(load)
      .catch(cause => setError(getErrorMessage(cause, 'Photo processing failed. Retry it.')))
      .finally(() => setPreparing(false));
  }, [draftId, guest, preview]);

  const retryPreparation = () => {
    attemptedPromotion.current = '';
    setPreview(current => current ? { ...current } : current);
  };

  const executeSubmit = async () => {
    submittingRef.current = true; setSubmitting(true); setError('');
    try {
      if (preview?.media.some(item => item.status === 'STAGED')) await lessorMediaService.promote(draftId);
      const result = await lessorSubmissionService.submit(draftId);
      onSubmitted(result);
      window.dispatchEvent(new Event('pathome_lessor_drafts_changed'));
      window.dispatchEvent(new Event('pathome_auth_changed'));
    }
    catch (cause) { setError(draftId.startsWith('guest-')
      ? `Your property is saved. We couldn't submit it yet. ${getErrorMessage(cause, 'Please retry.')}`
      : getErrorMessage(cause, 'Could not submit your property.')); }
    finally { submittingRef.current = false; setSubmitting(false); }
  };

  const submit = async () => {
    if (submittingRef.current || !preview || preview.missingRequirements.length || hasActiveUploads || preparing ||
        mediaItems.some(item => item.status === 'PENDING' || item.status === 'DELETING')) return;
    if (guest) { onGuestSubmit?.(); return; }
    submittingRef.current = true; setSubmitting(true); setError('');
    try {
      const contact = await lessorContactService.getContact();
      if (!contact.complete) {
        setContactInitial({
          name: contact.fullName || '',
          phone: contact.phoneNumber || ''
        });
        setShowContactModal(true);
        submittingRef.current = false;
        setSubmitting(false);
        return;
      }
      await executeSubmit();
    } catch (cause) {
      setError(getErrorMessage(cause, 'Could not verify your contact details.'));
      submittingRef.current = false;
      setSubmitting(false);
    }
  };

  if (loading) return <div className="flex min-h-40 items-center justify-center gap-2 text-sm text-slate-600"><LoaderCircle className="h-4 w-4 animate-spin"/>Preparing preview…</div>;
  if (!preview) return <div role="alert" className="rounded-xl border border-rose-200 bg-rose-50 p-5 text-sm text-rose-800">{error}<button className={`${SECONDARY} mt-4`} onClick={() => { void load(); }}><RefreshCw className="h-4 w-4"/>Retry</button></div>;
  const cover = preview.media.find(item => item.cover && item.contentType.startsWith('image/'));
  const propertyType = preview.propertyType ? {
    FLAT: 'Flat', HOUSE: 'House', STUDIO: 'Studio', PENTHOUSE: 'Penthouse', SERVICED_APARTMENT: 'Serviced apartment'
  }[preview.propertyType] : 'Not added';
  const photoCount = preview.media.filter(item => item.contentType.startsWith('image/')).length;
  const videoCount = preview.media.filter(item => item.contentType.startsWith('video/')).length;
  const stagedMedia = !guest ? preview.media.filter(item => item.status === 'STAGED') : [];
  const failedMedia = mediaItems.filter(item => item.status === 'FAILED' || item.status === 'DELETING');
  const pendingMedia = mediaItems.filter(item => item.status === 'PENDING');
  const processing = hasActiveUploads || preparing || (!error && stagedMedia.length > 0);
  const visibleMissing = (hasActiveUploads || stagedMedia.length > 0)
    ? preview.missingRequirements.filter(requirement => requirement !== 'choose a cover photo')
    : preview.missingRequirements;
  return <div>
    <h1 className="font-['Outfit',sans-serif] text-2xl font-bold text-slate-950 sm:text-3xl">Review your property</h1>
    <p className="mt-2 text-sm leading-relaxed text-slate-600">Check each section before submitting for review. Your full address and contact stay private.</p>
    <div className="mt-5 grid gap-3 sm:gap-4 lg:grid-cols-2">
      <SummarySection title="Photos" onEdit={() => onEdit('media')}>
        <div className="mb-3 aspect-[16/9] max-h-64 overflow-hidden rounded-xl bg-slate-100">
          {cover?.url ? <LessorMediaAsset url={cover.url} contentType={cover.contentType} alt="Selected property cover" className="h-full w-full object-cover" />
            : <div className="flex h-full items-center justify-center text-slate-500">Cover photo to add</div>}
        </div>
        <p>{photoCount} {photoCount === 1 ? 'photo' : 'photos'}{videoCount > 0 ? ` · ${videoCount} ${videoCount === 1 ? 'video' : 'videos'}` : ''}</p>
      </SummarySection>
      <div className="grid min-w-0 gap-3 sm:gap-4">
        <SummarySection title="Property" onEdit={() => onEdit('basics')}>
          <p className="font-semibold text-slate-950">{preview.title}</p>
          <p>Long-term rental · {propertyType}</p>
        </SummarySection>
        <SummarySection title="Configuration" onEdit={() => onEdit('basics')}>
          <p>{preview.bhkCount || 'Configuration to add'}</p>
          <p>{preview.furnishingStatus ? preview.furnishingStatus.replace(/_/g, ' ').toLowerCase() : 'Furnishing not added'}</p>
          {preview.totalAreaSqFt !== null && <p>{preview.totalAreaSqFt} sq ft</p>}
        </SummarySection>
      </div>
      <SummarySection title="Pricing" onEdit={() => onEdit('pricing')}>
        <p>Monthly rent: <strong className="font-semibold text-slate-950">{preview.monthlyRent !== null ? MONEY.format(preview.monthlyRent) : 'To add'}</strong></p>
        <p>Security deposit: <strong className="font-semibold text-slate-950">{preview.securityDeposit !== null ? MONEY.format(preview.securityDeposit) : 'To add'}</strong></p>
      </SummarySection>
      <SummarySection title="Location" onEdit={() => onEdit('location')}>
        <p>{[preview.locality, preview.city].filter(Boolean).join(', ') || 'Location to confirm'}</p>
        <p className="text-xs text-slate-500">The full street address is private.</p>
      </SummarySection>
      <SummarySection title="Availability" onEdit={() => onEdit('details')}>
        <p>Available from: {preview.availableFrom || 'To add'}</p>
      </SummarySection>
      <SummarySection title="Additional details" onEdit={() => onEdit('details')}>
        {preview.description ? <p className="whitespace-pre-wrap">{preview.description}</p> : <p>No description added.</p>}
      </SummarySection>
    </div>
    {processing && <div role="status" className="mt-6 flex items-center gap-2 rounded-xl border border-emerald-200 bg-emerald-50 p-4 text-sm text-emerald-900"><LoaderCircle className="h-4 w-4 animate-spin"/>Finishing your photos… You can review the other sections.</div>}
    {pendingMedia.length > 0 && !hasActiveUploads && <div role="status" className="mt-6 rounded-xl border border-amber-200 bg-amber-50 p-4 text-sm text-amber-900">Some photos may still be uploading. Open Photos to check their status.</div>}
    {failedMedia.length > 0 && <div role="alert" className="mt-6 rounded-xl border border-rose-200 bg-rose-50 p-4 text-sm text-rose-900">
      <p className="font-semibold">These photos need attention:</p>
      <ul className="mt-2 list-inside list-disc">{failedMedia.map((item, index) => <li key={item.mediaId}>{displayMediaName(item, index)}</li>)}</ul>
      <button type="button" className={`${SECONDARY} mt-3`} onClick={() => onEdit('media')}>Open Photos to retry or remove</button>
    </div>}
    {visibleMissing.length > 0 && <div role="alert" className="mt-6 rounded-xl border border-amber-200 bg-amber-50 p-4 text-sm text-amber-900"><p className="font-semibold">Finish these before submission:</p><ul className="mt-2 list-inside list-disc space-y-1">{visibleMissing.map(requirement => <li key={requirement}>{requirement}</li>)}</ul></div>}
    {error && <p role="alert" className="mt-5 text-sm text-rose-700">{error}</p>}
    {error && stagedMedia.length > 0 && <div className="mt-3 text-sm text-rose-800">{stagedMedia.map(displayMediaName).join(', ')}</div>}
    {error && stagedMedia.length > 0 && <button type="button" className={`${SECONDARY} mt-3`} onClick={retryPreparation}><RefreshCw className="h-4 w-4"/>Retry photo processing</button>}
    <button type="button" disabled={submitting || processing || pendingMedia.length > 0 || failedMedia.some(item => item.status === 'DELETING') || stagedMedia.length > 0 || preview.missingRequirements.length > 0} className="mt-7 inline-flex min-h-12 w-full items-center justify-center gap-2 rounded-xl bg-emerald-700 px-6 text-sm font-semibold text-white hover:bg-emerald-800 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 disabled:cursor-not-allowed disabled:opacity-50" onClick={() => { void submit(); }}>{submitting ? 'Submitting…' : error && !guest ? 'Retry submission' : 'Submit property'}<ArrowRight className="h-4 w-4"/></button>
    <LessorContactModal
      isOpen={showContactModal}
      initialFullName={contactInitial.name}
      initialPhoneNumber={contactInitial.phone}
      onSuccess={() => {
        setShowContactModal(false);
        void executeSubmit();
      }}
      onCancel={() => setShowContactModal(false)}
    />
  </div>;
}
