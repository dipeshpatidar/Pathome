import React, { useEffect, useRef, useState } from 'react';
import { ArrowRight, Check, LoaderCircle, Pencil, RefreshCw } from 'lucide-react';
import { getErrorMessage } from '../services/apiError';
import { LessorPreview, LessorSubmission, lessorSubmissionService } from '../services/lessorSubmissionService';
import { lessorMediaService } from '../services/lessorMediaService';
import { lessorContactService } from '../services/lessorContactService';
import { LessorContactModal } from './LessorContactModal';
import { LessorMediaAsset } from './LessorMediaAsset';

const SECONDARY = 'inline-flex min-h-11 items-center justify-center gap-2 rounded-xl border border-slate-300 bg-white px-4 text-sm font-semibold text-slate-700 hover:bg-slate-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500';
const MONEY = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 });

export function LessorPreviewStep({ draftId, onEdit, onDone, guest = false, onGuestSubmit }: {
  draftId: string; onEdit: (section: 'basics' | 'pricing' | 'location' | 'media' | 'details') => void;
  onDone: () => void; guest?: boolean; onGuestSubmit?: () => void;
}) {
  const [preview, setPreview] = useState<LessorPreview | null>(null);
  const [submission, setSubmission] = useState<LessorSubmission | null>(null);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [preparing, setPreparing] = useState(false);
  const [error, setError] = useState('');
  const [showContactModal, setShowContactModal] = useState(false);
  const [contactInitial, setContactInitial] = useState<{ name: string; phone: string }>({ name: '', phone: '' });
  const submittingRef = useRef(false);

  const load = async () => {
    setLoading(true); setError('');
    try { setPreview(await lessorSubmissionService.preview(draftId, guest)); }
    catch (cause) { setError(getErrorMessage(cause, 'Could not load your preview.')); }
    finally { setLoading(false); }
  };
  useEffect(() => { void load(); }, [draftId, guest]);

  const executeSubmit = async () => {
    submittingRef.current = true; setSubmitting(true); setError('');
    try {
      if (preview?.media.some(item => item.status === 'STAGED')) await lessorMediaService.promote(draftId);
      setSubmission(await lessorSubmissionService.submit(draftId));
    }
    catch (cause) { setError(draftId.startsWith('guest-')
      ? `Your property is saved. We couldn't submit it yet. ${getErrorMessage(cause, 'Please retry.')}`
      : getErrorMessage(cause, 'Could not submit your property.')); }
    finally { submittingRef.current = false; setSubmitting(false); }
  };

  const submit = async () => {
    if (submittingRef.current || !preview || preview.missingRequirements.length) return;
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

  if (submission) return <div role="status" className="mx-auto max-w-xl py-8"><div className="flex h-14 w-14 items-center justify-center rounded-full bg-emerald-100 text-emerald-700"><Check className="h-7 w-7"/></div><h1 className="mt-5 font-['Outfit',sans-serif] text-3xl font-bold text-slate-950">Property submitted</h1><p className="mt-3 text-base text-slate-600">Your property has been submitted for review. You can track its status in My Properties.</p><button type="button" className={`${SECONDARY} mt-7`} onClick={onDone}>Go to My Properties<ArrowRight className="h-4 w-4"/></button></div>;
  if (loading) return <div className="flex min-h-40 items-center justify-center gap-2 text-sm text-slate-600"><LoaderCircle className="h-4 w-4 animate-spin"/>Preparing preview…</div>;
  if (!preview) return <div role="alert" className="rounded-xl border border-rose-200 bg-rose-50 p-5 text-sm text-rose-800">{error}<button className={`${SECONDARY} mt-4`} onClick={() => { void load(); }}><RefreshCw className="h-4 w-4"/>Retry</button></div>;
  const cover = preview.media.find(item => item.cover && item.contentType.startsWith('image/'));
  return <div>
    <h1 className="font-['Outfit',sans-serif] text-3xl font-bold text-slate-950">Review your property</h1><p className="mt-2 text-sm text-slate-600">This is how the public details will read after approval. Your full address and contact are private.</p>
    <div className="mt-6 overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm"><div className="aspect-[16/10] bg-slate-100">{cover?.url ? <LessorMediaAsset url={cover.url} contentType={cover.contentType} alt="Selected property cover" className="h-full w-full object-cover"/> : <div className="flex h-full items-center justify-center text-sm text-slate-500">Add a cover photo</div>}</div>
      <div className="p-5"><p className="text-xs font-semibold uppercase tracking-widest text-emerald-700">Long-term rental</p><h2 className="mt-2 break-words font-['Outfit',sans-serif] text-2xl font-bold text-slate-950">{preview.title}</h2><p className="mt-1 text-sm text-slate-600">{[preview.locality, preview.city].filter(Boolean).join(', ') || 'Location to confirm'}</p><p className="mt-5 text-xl font-bold text-slate-950">{preview.monthlyRent !== null ? MONEY.format(preview.monthlyRent) : 'Rent to add'} <span className="text-sm font-normal text-slate-500">/ month</span></p><p className="mt-1 text-sm text-slate-600">Deposit: {preview.securityDeposit !== null ? MONEY.format(preview.securityDeposit) : 'To add'}</p><div className="mt-5 grid gap-2 text-sm text-slate-700 sm:grid-cols-2"><span>Available: {preview.availableFrom || 'To add'}</span>{preview.totalAreaSqFt && <span>{preview.totalAreaSqFt} sq ft</span>}{preview.furnishingStatus && <span>{preview.furnishingStatus.replace(/_/g, ' ').toLowerCase()}</span>}</div>{preview.description && <p className="mt-5 whitespace-pre-wrap text-sm leading-relaxed text-slate-700">{preview.description}</p>}</div></div>
    <div className="mt-6 grid gap-2 sm:grid-cols-2">{(['basics','pricing','location','media','details'] as const).map(section => <button key={section} type="button" className={SECONDARY} onClick={() => onEdit(section)}><Pencil className="h-4 w-4"/>Edit {section}</button>)}</div>
    {preview.missingRequirements.length > 0 && <div role="alert" className="mt-6 rounded-xl border border-amber-200 bg-amber-50 p-4 text-sm text-amber-900"><p className="font-semibold">Finish these before submission:</p><ul className="mt-2 list-inside list-disc space-y-1">{preview.missingRequirements.map(requirement => <li key={requirement}>{requirement}</li>)}</ul></div>}
    {!guest && preview.media.some(item => item.status === 'STAGED') && <button type="button" disabled={preparing} className={`${SECONDARY} mt-4`} onClick={() => { setPreparing(true); setError(''); void lessorMediaService.promote(draftId).then(load).catch(cause => setError(getErrorMessage(cause, 'Could not prepare your photos. Retry.'))).finally(() => setPreparing(false)); }}>{preparing ? 'Preparing photos…' : 'Prepare photos for submission'}</button>}
    {error && <p role="alert" className="mt-5 text-sm text-rose-700">{error}</p>}
    <button type="button" disabled={submitting || preview.missingRequirements.length > 0} className="mt-7 inline-flex min-h-12 w-full items-center justify-center gap-2 rounded-xl bg-emerald-700 px-6 text-sm font-semibold text-white hover:bg-emerald-800 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 disabled:cursor-not-allowed disabled:opacity-50" onClick={() => { void submit(); }}>{submitting ? 'Submitting…' : error && !guest ? 'Retry submission' : 'Submit property'}<ArrowRight className="h-4 w-4"/></button>
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
