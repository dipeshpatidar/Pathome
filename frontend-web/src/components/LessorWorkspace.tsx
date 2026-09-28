import React, { useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { ArrowLeft, ArrowRight, Building2, Check, LoaderCircle, RefreshCw } from 'lucide-react';
import type { UserProfile } from '../types';
import { API_ROOT_URL } from '../config/endpoints';
import { ApiRequestError, createApiRequestError, getErrorMessage } from '../services/apiError';
import { LessorAutosave, LessorSaveStatus } from '../services/lessorAutosave';
import { LessorBasics, LessorDetails, LessorDraft, LessorLocation, LessorPricing, ResidentialType, lessorDraftService } from '../services/lessorDraftService';
import { LessorLocalityOption, lessorLocationService } from '../services/lessorLocationService';
import { lessorMediaService } from '../services/lessorMediaService';
import { lessorSubmissionService } from '../services/lessorSubmissionService';
import { LessorMediaStep } from './LessorMediaStep';
import { LessorDetailsStep } from './LessorDetailsStep';
import { LessorPreviewStep } from './LessorPreviewStep';
import { LessorPortfolio } from './LessorPortfolio';
import { LessorListingView } from './LessorListingView';
import { bhkChoice, exactBhk, pricingReady } from '../utils/lessorConfiguration';

const TYPES: { value: ResidentialType; label: string }[] = [
  { value: 'FLAT', label: 'Flat' }, { value: 'HOUSE', label: 'House' },
  { value: 'STUDIO', label: 'Studio' }, { value: 'PENTHOUSE', label: 'Penthouse' },
  { value: 'SERVICED_APARTMENT', label: 'Serviced apartment' }
];
const BHK_OPTIONS = ['1RK', '1BHK', '2BHK', '3BHK', '4+'] as const;
const BUTTON = 'inline-flex min-h-11 items-center justify-center gap-2 rounded-xl bg-emerald-700 px-5 py-2.5 text-sm font-semibold text-white transition-colors hover:bg-emerald-800 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50';
const SECONDARY = 'inline-flex min-h-11 items-center justify-center gap-2 rounded-xl border border-slate-300 bg-white px-4 py-2.5 text-sm font-semibold text-slate-700 hover:border-slate-400 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500';
const FIELD = 'min-h-11 w-full rounded-xl border border-slate-300 bg-white px-3 text-base text-slate-900 outline-none focus:border-emerald-600 focus:ring-2 focus:ring-emerald-200';

async function capability(method: 'GET' | 'POST'): Promise<{ enabled: boolean }> {
  const token = localStorage.getItem('pathome_auth_token');
  const response = await fetch(`${API_ROOT_URL}/lessor/capability`, {
    method, headers: { Authorization: `Bearer ${token}`, Accept: 'application/json' }
  });
  if (!response.ok) throw await createApiRequestError(response, 'Unable to open your property workspace.');
  return response.json();
}

export function LessorWorkspace({ user, onRequestAuth }: { user: UserProfile | null; onRequestAuth: (draftId: string, submit: boolean) => void }) {
  const location = useLocation();
  const navigate = useNavigate();
  const [capabilityState, setCapabilityState] = useState<'loading' | 'inactive' | 'active' | 'guest' | 'error'>(user ? 'loading' : 'guest');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [selectedType, setSelectedType] = useState<ResidentialType | null>(null);
  const [guestResume, setGuestResume] = useState<LessorDraft | null>(null);
  const [guestExpired, setGuestExpired] = useState(false);
  const [ownerDraft, setOwnerDraft] = useState(false);
  const [transition, setTransition] = useState<'idle' | 'claiming' | 'promoting' | 'submitting' | 'failedClaim' | 'failedMedia' | 'failedSubmit'>('idle');
  const [previewRevision, setPreviewRevision] = useState(0);
  const claimBusy = useRef(false);
  const draftId = /^\/lessor\/drafts\/([^/]+)$/.exec(location.pathname)?.[1];
  const listingId = /^\/lessor\/listings\/(\d+)$/.exec(location.pathname)?.[1];
  const isNew = location.pathname === '/lessor/new';

  const finishSubmission = async (id: string) => {
    try {
      setTransition('promoting');
      await lessorMediaService.promote(id);
      setPreviewRevision(value => value + 1);
      setTransition('submitting');
      await lessorSubmissionService.submit(id);
      sessionStorage.removeItem('pathome_guest_submit_draft');
      localStorage.removeItem(`pathome_guest_step_${id}`);
      setTransition('idle');
      navigate('/lessor', { replace: true });
    } catch (cause) {
      setError(getErrorMessage(cause, 'Submission could not be completed.'));
      setTransition('failedSubmit');
    }
  };

  const promoteOnly = async (id: string) => {
    try {
      setTransition('promoting');
      await lessorMediaService.promote(id);
      setPreviewRevision(value => value + 1);
      setTransition('idle');
    } catch (cause) {
      setError(getErrorMessage(cause, 'Could not prepare your photos.'));
      setTransition('failedMedia');
    }
  };

  const claimDraft = async (id: string, submit: boolean) => {
    if (claimBusy.current) return;
    claimBusy.current = true; setError(''); setTransition('claiming');
    try {
      await lessorDraftService.claimGuest(id);
      setOwnerDraft(true);
      setCapabilityState('active');
      localStorage.removeItem('pathome_guest_draft_id');
      sessionStorage.removeItem('pathome_guest_save_draft');
      sessionStorage.removeItem('pathome_guest_submit_draft');
      if (submit) await finishSubmission(id);
      else await promoteOnly(id);
    } catch (cause) {
      setError(getErrorMessage(cause, 'Could not save this guest draft to your account.'));
      setTransition('failedClaim');
    } finally { claimBusy.current = false; }
  };

  useEffect(() => {
    let live = true;
    if (!user) {
      setCapabilityState('guest'); setOwnerDraft(false);
      if (!draftId) lessorDraftService.resumeGuest().then(value => {
        if (!live) return;
        setGuestResume(value);
        setGuestExpired(!value && !!localStorage.getItem('pathome_guest_draft_id'));
        if (value && isNew) navigate(`/lessor/drafts/${encodeURIComponent(value.draftId)}`, { replace: true });
      }).catch(cause => { if (live) setError(getErrorMessage(cause, 'Could not check your saved draft.')); });
      return () => { live = false; };
    }
    if (ownerDraft && draftId?.startsWith('guest-')) {
      setCapabilityState('active');
      return () => { live = false; };
    }
    if (draftId?.startsWith('guest-') && !ownerDraft) {
      const pendingSubmit = sessionStorage.getItem('pathome_guest_submit_draft') === draftId;
      const pendingSave = sessionStorage.getItem('pathome_guest_save_draft') === draftId;
      setCapabilityState('loading');
      lessorDraftService.get(draftId).then(() => {
        if (live) {
          sessionStorage.removeItem('pathome_guest_submit_draft');
          sessionStorage.removeItem('pathome_guest_save_draft');
          localStorage.removeItem('pathome_guest_draft_id');
          setOwnerDraft(true); setCapabilityState('active');
        }
      }).catch(cause => {
        if (!live) return;
        if (pendingSubmit || pendingSave) void claimDraft(draftId, pendingSubmit);
        else { setError(getErrorMessage(cause, 'This property draft is unavailable.')); setCapabilityState('error'); }
      });
      return () => { live = false; };
    }
    setCapabilityState('loading');
    capability('GET').then(async value => {
      if (!live) return;
      if (!value.enabled && (isNew || draftId)) await capability('POST');
      if (live) setCapabilityState(value.enabled || isNew || !!draftId ? 'active' : 'inactive');
    }).catch(cause => { if (live) { setError(getErrorMessage(cause, 'Unable to open your property workspace.')); setCapabilityState('error'); } });
    return () => { live = false; };
  }, [user?.id, draftId, isNew, ownerDraft]);

  const activate = async () => {
    setBusy(true); setError('');
    try { await capability('POST'); setCapabilityState('active'); }
    catch (cause) { setError(getErrorMessage(cause, 'Unable to enable property management.')); }
    finally { setBusy(false); }
  };

  const create = async () => {
    if (!selectedType || busy) return;
    setBusy(true); setError('');
    try {
      const draft = await lessorDraftService.create({ propertyType: selectedType, rentalMode: 'LONG_TERM_RENTAL', bhkCount: null }, !user);
      if (!user) localStorage.setItem('pathome_guest_draft_id', draft.draftId);
      navigate(`/lessor/drafts/${encodeURIComponent(draft.draftId)}`);
    } catch (cause) { setError(getErrorMessage(cause, 'Unable to start your draft.')); }
    finally { setBusy(false); }
  };

  return <main className="mx-auto w-full max-w-6xl flex-1 px-4 pb-[calc(2rem+env(safe-area-inset-bottom))] pt-6 sm:px-6 lg:pt-10">
    {capabilityState === 'loading' && <div className="flex min-h-48 items-center justify-center gap-3 text-slate-600"><LoaderCircle className="h-5 w-5 animate-spin"/>Opening your workspace…</div>}
    {capabilityState === 'error' && <div role="alert" className="mx-auto max-w-lg rounded-2xl border border-rose-200 bg-rose-50 p-6 text-rose-800"><p>{error}</p><button className={`${SECONDARY} mt-4`} onClick={() => window.location.reload()}>Retry</button></div>}
    {capabilityState === 'inactive' && <section className="mx-auto max-w-xl py-8 sm:py-16">
      <div className="mb-5 flex h-12 w-12 items-center justify-center rounded-2xl bg-emerald-100 text-emerald-800"><Building2/></div>
      <h1 className="font-['Outfit',sans-serif] text-3xl font-bold tracking-tight text-slate-950 sm:text-4xl">List your property</h1>
      <p className="mt-3 max-w-prose text-base leading-relaxed text-slate-600">Create a rental listing, save as you go, and submit it for review. Your tenant account stays available.</p>
      {error && <p role="alert" className="mt-5 text-sm text-rose-700">{error}</p>}
      <button type="button" disabled={busy} onClick={activate} className={`${BUTTON} mt-7`}>{busy ? 'Opening…' : 'Get started'}<ArrowRight className="h-4 w-4"/></button>
    </section>}
    {(capabilityState === 'active' || capabilityState === 'guest') && draftId && <LessorEditor key={draftId} userId={user?.id ?? null} guest={!ownerDraft && !user || !ownerDraft && draftId.startsWith('guest-')} draftId={decodeURIComponent(draftId)} onBack={() => navigate('/lessor')} onRequestAuth={onRequestAuth} previewRevision={previewRevision}/>}
    {capabilityState === 'active' && listingId && <LessorListingView key={listingId} listingId={Number(listingId)} onBack={() => navigate('/lessor')} onOpenDraft={id => navigate(`/lessor/drafts/${encodeURIComponent(id)}`)}/>}
    {(capabilityState === 'active' || capabilityState === 'guest') && isNew && <section className="mx-auto max-w-2xl">
      <button type="button" className={SECONDARY} onClick={() => navigate('/lessor')}><ArrowLeft className="h-4 w-4"/>{user ? 'My properties' : 'Your listing'}</button>
      <p className="mt-8 text-xs font-bold uppercase tracking-widest text-emerald-700">Step 1 of 6</p>
      <h1 className="mt-2 font-['Outfit',sans-serif] text-3xl font-bold text-slate-950">What kind of home is it?</h1>
      <p className="mt-2 text-sm text-slate-600">Choose the closest match. This listing is for a long-term rental.</p>
      <div className="mt-7 grid grid-cols-1 gap-3 min-[390px]:grid-cols-2">
        {TYPES.map(type => <button key={type.value} type="button" aria-pressed={selectedType === type.value} onClick={() => setSelectedType(type.value)} className={`min-h-14 rounded-xl border px-4 text-left text-sm font-semibold transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 ${selectedType === type.value ? 'border-emerald-700 bg-emerald-50 text-emerald-900' : 'border-slate-200 bg-white text-slate-700 hover:border-slate-400'}`}>{type.label}</button>)}
      </div>
      {error && <p role="alert" className="mt-5 text-sm text-rose-700">{error}</p>}
      <button type="button" disabled={!selectedType || busy} onClick={create} className={`${BUTTON} mt-7 w-full sm:w-auto`}>{busy ? 'Starting…' : 'Continue'}<ArrowRight className="h-4 w-4"/></button>
    </section>}
    {capabilityState === 'guest' && !draftId && !isNew && <section className="mx-auto max-w-xl py-8"><h1 className="font-['Outfit',sans-serif] text-3xl font-bold text-slate-950">List your property</h1><p className="mt-3 text-sm text-slate-600">No account needed to start. Sign in when you're ready to submit.</p>{guestExpired && <p role="status" className="mt-4 text-sm text-amber-800">Your previous guest draft is unavailable or has expired. You can start a new one.</p>}{error && <p role="alert" className="mt-4 text-sm text-rose-700">{error}</p>}{guestResume && <button className={`${BUTTON} mt-6`} onClick={() => navigate(`/lessor/drafts/${encodeURIComponent(guestResume.draftId)}`)}>Continue your property listing<ArrowRight className="h-4 w-4"/></button>}<button className={`${SECONDARY} mt-6 ${guestResume ? 'ml-2' : ''}`} onClick={() => navigate('/lessor/new')}>Start property listing</button></section>}
    {capabilityState === 'active' && !draftId && !listingId && !isNew && <LessorPortfolio
      onAdd={() => navigate('/lessor/new')}
      onOpenDraft={id => navigate(`/lessor/drafts/${encodeURIComponent(id)}`)}
      onOpenListing={id => navigate(`/lessor/listings/${id}`)}
    />}
    {transition !== 'idle' && <div role="status" aria-live="polite" className="fixed inset-0 z-40 flex items-center justify-center bg-slate-950/45 p-4"><div className="w-full max-w-sm rounded-2xl bg-white p-6 shadow-xl"><p className="text-base font-semibold text-slate-950">{transition === 'claiming' ? 'Saving your property…' : transition === 'promoting' ? 'Preparing your photos…' : transition === 'submitting' ? 'Submitting for review…' : transition === 'failedSubmit' ? "Your property is saved. We couldn't submit it yet." : transition === 'failedMedia' ? "Your property is saved. We couldn't prepare its photos yet." : 'We could not save this draft to your account.'}</p>{transition.startsWith('failed') && <><p role="alert" className="mt-2 break-words text-sm text-rose-700">{error}</p><div className="mt-5 flex flex-wrap gap-3"><button className={BUTTON} onClick={() => { if (draftId) void (transition === 'failedSubmit' ? finishSubmission(draftId) : transition === 'failedMedia' ? promoteOnly(draftId) : claimDraft(draftId, sessionStorage.getItem('pathome_guest_submit_draft') === draftId)); }}>{transition === 'failedSubmit' ? 'Retry submission' : transition === 'failedMedia' ? 'Retry photos' : 'Retry saving'}</button>{ownerDraft && <button className={SECONDARY} onClick={() => { setTransition('idle'); navigate('/lessor'); }}>My Properties</button>}</div></>}</div></div>}
  </main>;
}

function LessorEditor({ userId, draftId, onBack, guest, onRequestAuth, previewRevision }: { userId: number | null; draftId: string; onBack: () => void; guest: boolean; onRequestAuth: (draftId: string, submit: boolean) => void; previewRevision: number }) {
  const [draft, setDraft] = useState<LessorDraft | null>(null);
  const [basics, setBasics] = useState<LessorBasics | null>(null);
  const [pricing, setPricing] = useState<LessorPricing>({ monthlyRent: null, securityDeposit: null });
  const [propertyLocation, setPropertyLocation] = useState<LessorLocation>({ city: '', canonicalLocalityId: null, localityInput: '', address: '', landmark: '' });
  const [details, setDetails] = useState<LessorDetails>({ availableFrom: null, furnishingStatus: '', totalAreaSqFt: null, floorNumber: null, totalFloors: null, amenities: '', description: '' });
  const [cities, setCities] = useState<string[]>([]);
  const [suggestions, setSuggestions] = useState<LessorLocalityOption[]>([]);
  const [suggestionState, setSuggestionState] = useState<'idle' | 'loading' | 'error' | 'ready'>('idle');
  const [showExactBhk, setShowExactBhk] = useState(false);
  const [step, setStep] = useState<'basics' | 'pricing' | 'location' | 'media' | 'details' | 'preview'>(() => {
    if (sessionStorage.getItem('pathome_guest_submit_draft') === draftId) return 'preview';
    const saved = localStorage.getItem(`pathome_guest_step_${draftId}`);
    return saved === 'pricing' || saved === 'location' || saved === 'media' || saved === 'details' || saved === 'preview' ? saved : 'basics';
  });
  const [status, setStatus] = useState<LessorSaveStatus>('saved');
  const [error, setError] = useState('');
  const [expired, setExpired] = useState(false);
  const queue = useRef<LessorAutosave | null>(null);

  useEffect(() => { if (guest) localStorage.setItem(`pathome_guest_step_${draftId}`, step); }, [guest, draftId, step]);

  useEffect(() => {
    let live = true;
    setError('');
    lessorDraftService.get(draftId, guest).then(server => {
      if (!live) return;
      setDraft(server);
      const saver = new LessorAutosave(guest ? 0 : userId ?? 0, draftId, server.version, setStatus,
        updated => { if (live) setDraft(previous => previous ? { ...previous, version: updated.version, completionPercent: updated.completionPercent } : updated); },
        (id, section, version, value) => lessorDraftService.save(id, section, version, value, guest), !guest);
      queue.current = saver;
      const pending = saver.getPending();
      setBasics(pending.basics as LessorBasics || server.data.basics);
      const restoredBhk = (pending.basics as LessorBasics | undefined)?.bhkCount || server.data.basics.bhkCount;
      setShowExactBhk(Boolean(restoredBhk && /^\d+BHK$/.test(restoredBhk) && Number.parseInt(restoredBhk) >= 4));
      setPricing(pending.pricing as LessorPricing || server.data.pricing || { monthlyRent: null, securityDeposit: null });
      setPropertyLocation(pending.location as LessorLocation || server.data.location || { city: '', canonicalLocalityId: null, localityInput: '', address: '', landmark: '' });
      setDetails(pending.details as LessorDetails || server.data.details || { availableFrom: null, furnishingStatus: '', totalAreaSqFt: null, floorNumber: null, totalFloors: null, amenities: '', description: '' });
      setStatus(saver.getStatus());
    }).catch(cause => { if (live) {
      if (guest && cause instanceof ApiRequestError && cause.status === 404) {
        setExpired(true); setError('This guest draft is unavailable or has expired. You can start a new listing.');
      } else setError(getErrorMessage(cause, 'Unable to load this draft.'));
    } });
    return () => { live = false; queue.current?.dispose(); queue.current = null; };
  }, [draftId, guest, guest ? null : userId]);

  useEffect(() => {
    if (step !== 'location') return;
    let live = true;
    lessorLocationService.cities(guest).then(value => { if (live) setCities(value); })
      .catch(() => { if (live) setError('Supported cities could not be loaded. Retry this step.'); });
    return () => { live = false; };
  }, [step, guest]);

  useEffect(() => {
    if (step !== 'location' || !propertyLocation.city || propertyLocation.localityInput.trim().length < 2 || propertyLocation.canonicalLocalityId) {
      setSuggestions([]); setSuggestionState('idle'); return;
    }
    let live = true;
    const timer = window.setTimeout(() => {
      setSuggestionState('loading');
      lessorLocationService.suggestions(propertyLocation.city, propertyLocation.localityInput.trim(), guest)
        .then(value => { if (live) { setSuggestions(value); setSuggestionState('ready'); } })
        .catch(() => { if (live) { setSuggestions([]); setSuggestionState('error'); } });
    }, 250);
    return () => { live = false; window.clearTimeout(timer); };
  }, [step, propertyLocation.city, propertyLocation.localityInput, propertyLocation.canonicalLocalityId, guest]);

  const updateBasics = (value: LessorBasics) => { setBasics(value); queue.current?.change('basics', value); };
  const updatePricing = (value: LessorPricing) => { setPricing(value); queue.current?.change('pricing', value); };
  const updateLocation = (value: LessorLocation) => { setPropertyLocation(value); queue.current?.change('location', value); };
  const updateDetails = (value: LessorDetails) => { setDetails(value); queue.current?.change('details', value); };
  const requestAuth = async (submit: boolean) => {
    if (!await queue.current?.flush()) { setError('Retry saving your changes before signing in.'); return; }
    onRequestAuth(draftId, submit);
  };
  const next = async () => {
    setError('');
    if (step === 'basics' && !basics?.bhkCount) { setError('Choose the exact configuration.'); return; }
    if (step === 'pricing' && !pricingReady(pricing.monthlyRent, pricing.securityDeposit)) { setError('Add monthly rent and a deposit amount, including ₹0 if none.'); return; }
    if (step === 'location' && (!cities.includes(propertyLocation.city) || !propertyLocation.canonicalLocalityId || !propertyLocation.address.trim())) { setError('Choose a supported city and confirmed locality, then add the private address.'); return; }
    if (step === 'details' && !details.availableFrom) { setError('Add the availability date to continue.'); return; }
    if (!await queue.current?.flush()) { setError(status === 'conflict' ? 'A newer version exists. Review your other tab before continuing.' : guest ? 'Your changes are still in this tab. Retry the save before leaving.' : 'Your changes are saved on this device. Retry the server save to continue.'); return; }
    setStep(step === 'basics' ? 'pricing' : step === 'pricing' ? 'location' : step === 'location' ? 'media' : 'preview');
    window.scrollTo({ top: 0, behavior: 'instant' });
  };

  if (error && !draft) return <div role="alert" className="mx-auto max-w-xl rounded-xl border border-rose-200 bg-rose-50 p-5 text-rose-800">{error}<button className={`${SECONDARY} mt-4`} onClick={expired ? onBack : () => window.location.reload()}>{expired ? 'Back to property start' : 'Retry'}</button></div>;
  if (!draft || !basics) return <div className="flex min-h-48 items-center justify-center gap-3 text-slate-600"><LoaderCircle className="h-5 w-5 animate-spin"/>Opening your draft…</div>;
  const currentBhk = showExactBhk ? '4+' : bhkChoice(basics.bhkCount);
  return <section className="mx-auto max-w-2xl">
    <div className="flex flex-wrap items-center justify-between gap-3"><button className={SECONDARY} onClick={async () => { if (await queue.current?.flush()) onBack(); else setError('Retry saving before leaving this step.'); }}><ArrowLeft className="h-4 w-4"/>{guest ? 'Your listing' : 'My properties'}</button><span aria-live="polite" className={`text-xs font-semibold ${status === 'error' || status === 'conflict' ? 'text-rose-700' : 'text-slate-500'}`}>{status === 'saving' ? 'Saving…' : status === 'saved' ? 'Saved' : status === 'conflict' ? 'Conflict' : "Couldn't save — Retry"}</span></div>
    {guest && <div className="mt-5 flex flex-wrap items-center justify-between gap-2 rounded-xl border border-emerald-100 bg-emerald-50 px-4 py-3 text-sm text-emerald-950"><span>No account needed to start. Sign in when you're ready to submit.</span><button type="button" className="min-h-11 font-semibold underline underline-offset-2" onClick={() => { void requestAuth(false); }}>Sign in to save across devices</button></div>}
    {draft.revisionOfListingId && <p className="mt-5 text-sm text-slate-600">You are editing a revision. The approved property stays unchanged until these updates are reviewed.</p>}
    {draft.reviewNote && <div role="status" className="mt-4 rounded-xl border border-amber-200 bg-amber-50 p-4 text-sm text-amber-950"><p className="font-semibold">Reviewer note</p><p className="mt-1 whitespace-pre-wrap">{draft.reviewNote}</p></div>}
    <div className="mt-8 h-1.5 overflow-hidden rounded-full bg-slate-200"><div className="h-full bg-emerald-600 transition-[width] motion-reduce:transition-none" style={{ width: step === 'basics' ? '17%' : step === 'pricing' ? '34%' : step === 'location' ? '50%' : step === 'media' ? '67%' : step === 'details' ? '84%' : '100%' }}/></div>
    <p className="mt-5 text-xs font-bold uppercase tracking-widest text-emerald-700">{step === 'basics' ? 'Home details · Step 2 of 6' : step === 'pricing' ? 'Pricing · Step 3 of 6' : step === 'location' ? 'Location · Step 4 of 6' : step === 'media' ? 'Photos · Step 5 of 6' : step === 'details' ? 'Availability · Step 6 of 6' : 'Review'}</p>
    {step === 'basics' && <><h1 className="mt-2 font-['Outfit',sans-serif] text-3xl font-bold text-slate-950">Tell us about the home</h1><p className="mt-2 text-sm text-slate-600">You can adjust these details before submitting.</p>
      <label className="mt-7 block text-sm font-semibold text-slate-800" htmlFor="lessor-type">Property type</label><select id="lessor-type" className={`${FIELD} mt-2`} value={basics.propertyType} onBlur={() => { void queue.current?.flush(); }} onChange={event => updateBasics({ ...basics, propertyType: event.target.value as ResidentialType })}>{TYPES.map(type => <option key={type.value} value={type.value}>{type.label}</option>)}</select>
      <p className="mt-7 text-sm font-semibold text-slate-800">Configuration</p><div role="group" aria-label="Configuration" className="mt-2 grid grid-cols-3 gap-2 sm:grid-cols-5">{BHK_OPTIONS.map(option => <button key={option} type="button" aria-pressed={currentBhk === option} onClick={() => { setShowExactBhk(option === '4+'); updateBasics({ ...basics, bhkCount: exactBhk(option, 4) }); }} className={`min-h-11 rounded-xl border px-2 text-sm font-semibold focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 ${currentBhk === option ? 'border-emerald-700 bg-emerald-50 text-emerald-900' : 'border-slate-300 bg-white text-slate-700'}`}>{option === '4+' ? '4+ BHK' : option === '1RK' ? '1 RK' : option.replace('BHK', ' BHK')}</button>)}</div>
      {showExactBhk && <div className="mt-4"><label htmlFor="lessor-bedrooms" className="text-sm font-semibold text-slate-800">Exact number of bedrooms</label><input id="lessor-bedrooms" type="number" inputMode="numeric" min={4} max={99} className={`${FIELD} mt-2 max-w-40`} value={basics.bhkCount ? Number.parseInt(basics.bhkCount) : ''} onBlur={() => { void queue.current?.flush(); }} onChange={event => updateBasics({ ...basics, bhkCount: event.target.value === '' ? null : exactBhk('4+', Number(event.target.value)) })}/></div>}
    </>}
    {step === 'pricing' && <><h1 className="mt-2 font-['Outfit',sans-serif] text-3xl font-bold text-slate-950">Set your rent</h1><p className="mt-2 text-sm text-slate-600">Enter the monthly amount and the deposit you require.</p>
      <div className="mt-7 grid gap-5 sm:grid-cols-2"><div><label className="text-sm font-semibold text-slate-800" htmlFor="lessor-rent">Monthly rent</label><div className="relative mt-2"><span className="pointer-events-none absolute left-3 top-3 text-slate-500">₹</span><input id="lessor-rent" type="number" inputMode="decimal" min={1} className={`${FIELD} pl-7`} value={pricing.monthlyRent ?? ''} onBlur={() => { void queue.current?.flush(); }} onChange={event => updatePricing({ ...pricing, monthlyRent: event.target.value === '' ? null : Number(event.target.value) })}/></div></div><div><label className="text-sm font-semibold text-slate-800" htmlFor="lessor-deposit">Security deposit</label><div className="relative mt-2"><span className="pointer-events-none absolute left-3 top-3 text-slate-500">₹</span><input id="lessor-deposit" type="number" inputMode="decimal" min={0} className={`${FIELD} pl-7`} value={pricing.securityDeposit ?? ''} onBlur={() => { void queue.current?.flush(); }} onChange={event => updatePricing({ ...pricing, securityDeposit: event.target.value === '' ? null : Number(event.target.value) })}/></div><p className="mt-1 text-xs text-slate-500">Enter 0 if no deposit.</p></div></div>
    </>}
    {step === 'location' && <><h1 className="mt-2 font-['Outfit',sans-serif] text-3xl font-bold text-slate-950">Where is the home?</h1><p className="mt-2 text-sm text-slate-600">The full address stays private. Tenants see the locality and city.</p>
      <div className="mt-7 space-y-5"><div><label htmlFor="lessor-city" className="text-sm font-semibold text-slate-800">City</label><select id="lessor-city" className={`${FIELD} mt-2`} value={propertyLocation.city} onChange={event => updateLocation({ ...propertyLocation, city: event.target.value, canonicalLocalityId: null, localityInput: '' })} onBlur={() => { void queue.current?.flush(); }}><option value="">Choose city</option>{cities.map(city => <option key={city} value={city}>{city}</option>)}</select></div>
      <div><label htmlFor="lessor-locality" className="text-sm font-semibold text-slate-800">Locality</label><input id="lessor-locality" type="search" autoComplete="off" className={`${FIELD} mt-2`} value={propertyLocation.localityInput} disabled={!propertyLocation.city} onChange={event => updateLocation({ ...propertyLocation, localityInput: event.target.value, canonicalLocalityId: null })} onBlur={() => { void queue.current?.flush(); }} placeholder="Start typing your locality"/>
      {propertyLocation.canonicalLocalityId ? <p className="mt-2 flex items-center gap-2 text-sm font-medium text-emerald-800"><Check className="h-4 w-4"/>Confirmed: {propertyLocation.localityInput}, {propertyLocation.city}</p> : propertyLocation.localityInput.trim().length >= 2 && <div role="status" className="mt-2 rounded-xl border border-slate-200 bg-white p-2">{suggestionState === 'loading' && <p className="px-2 py-2 text-sm text-slate-500">Finding localities…</p>}{suggestionState === 'error' && <p className="px-2 py-2 text-sm text-rose-700">Could not search. Edit the text to retry.</p>}{suggestionState === 'ready' && suggestions.length === 0 && <p className="px-2 py-2 text-sm text-slate-600">No supported locality found. Your text is saved, but a confirmed locality is needed before submission.</p>}{suggestions.map(option => <button key={`${option.city}-${option.id}`} type="button" className="flex min-h-11 w-full items-center justify-between gap-3 rounded-lg px-3 text-left text-sm text-slate-800 hover:bg-emerald-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500" onMouseDown={event => event.preventDefault()} onClick={() => { if (option.city !== propertyLocation.city) { setError(`${option.name} is in ${option.city}. Change city to select it.`); return; } setError(''); updateLocation({ ...propertyLocation, canonicalLocalityId: option.id, localityInput: option.name }); setSuggestions([]); }}><span>{option.name}, {option.city}</span>{option.match === 'different_city' && <span className="shrink-0 text-xs font-semibold text-amber-700">Different city</span>}</button>)}</div>}</div>
      <div><label htmlFor="lessor-address" className="text-sm font-semibold text-slate-800">Street address</label><input id="lessor-address" className={`${FIELD} mt-2`} maxLength={500} value={propertyLocation.address} onChange={event => updateLocation({ ...propertyLocation, address: event.target.value })} onBlur={() => { void queue.current?.flush(); }} placeholder="Building, street and house number"/><p className="mt-1 text-xs text-slate-500">Only Pathome’s review team sees the full address.</p></div>
      <div><label htmlFor="lessor-landmark" className="text-sm font-semibold text-slate-800">Landmark <span className="font-normal text-slate-500">(optional)</span></label><input id="lessor-landmark" className={`${FIELD} mt-2`} maxLength={200} value={propertyLocation.landmark} onChange={event => updateLocation({ ...propertyLocation, landmark: event.target.value })} onBlur={() => { void queue.current?.flush(); }}/></div></div>
    </>}
    {step === 'media' && <LessorMediaStep draftId={draftId} guest={guest} onNext={() => { setStep('details'); window.scrollTo({ top: 0, behavior: 'instant' }); }}/>}
    {step === 'details' && <LessorDetailsStep value={details} onChange={updateDetails} onBlur={() => { void queue.current?.flush(); }}/>}
    {step === 'preview' && <LessorPreviewStep key={previewRevision} draftId={draftId} guest={guest} onGuestSubmit={() => { void requestAuth(true); }} onEdit={section => { setError(''); setStep(section); }} onDone={onBack}/>}
    {error && <p role="alert" className="mt-6 text-sm font-semibold text-rose-700">{error}</p>}
    {status === 'error' && <button className={`${SECONDARY} mt-4`} onClick={() => { void queue.current?.flush(); }}><RefreshCw className="h-4 w-4"/>Retry save</button>}
    {status === 'conflict' && <p className="mt-3 text-sm text-rose-700">Another tab saved a newer version. Your unsynced entries remain {guest ? 'in this tab' : 'on this device'}. Copy them before reloading this draft.</p>}
    {step !== 'preview' && <div className="mt-8 flex flex-wrap gap-3"><button type="button" className={SECONDARY} onClick={() => { setError(''); setStep(step === 'location' ? 'pricing' : step === 'media' ? 'location' : step === 'details' ? 'media' : 'basics'); }}>Back</button>{step !== 'media' && <button type="button" disabled={status === 'conflict'} className={BUTTON} onClick={() => { void next(); }}>Continue<ArrowRight className="h-4 w-4"/></button>}</div>}
  </section>;
}
