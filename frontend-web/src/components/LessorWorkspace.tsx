import React, { useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { useNotification } from '../context/NotificationContext';
import { motion, AnimatePresence, useReducedMotion } from 'framer-motion';
import {
  ArrowLeft,
  ArrowRight,
  Building2,
  Home,
  Building,
  Sparkles,
  Hotel,
  Check,
  LoaderCircle,
  RefreshCw
} from 'lucide-react';
import type { UserProfile } from '../types';
import { API_ROOT_URL } from '../config/endpoints';
import { ApiRequestError, createApiRequestError, getErrorMessage } from '../services/apiError';
import { LessorAutosave, LessorSaveStatus } from '../services/lessorAutosave';
import {
  LessorBasics,
  LessorDetails,
  LessorDraft,
  LessorLocation,
  LessorPricing,
  ResidentialType,
  lessorDraftService
} from '../services/lessorDraftService';
import { LessorLocalityOption, lessorLocationService } from '../services/lessorLocationService';
import { LessorMediaItem, lessorMediaService } from '../services/lessorMediaService';
import { lessorSubmissionService } from '../services/lessorSubmissionService';
import { lessorContactService } from '../services/lessorContactService';
import { LessorContactModal } from './LessorContactModal';
import { LessorMediaStep } from './LessorMediaStep';
import { LessorDetailsStep } from './LessorDetailsStep';
import { LessorPreviewStep } from './LessorPreviewStep';
import { LessorPortfolio } from './LessorPortfolio';
import { LessorListingView } from './LessorListingView';
import { LessorOnboardingHeader } from './LessorOnboardingHeader';
import { LessorProgressBar, OnboardingStepKey } from './LessorProgressBar';
import { LessorLivePreview } from './LessorLivePreview';
import { bhkChoice, exactBhk, pricingReady } from '../utils/lessorConfiguration';
import { changeLocationCity, changeLocalityText, chooseLocalityOption, locationValidationError,
  useLocalityForReview } from '../utils/lessorLocationState';

interface PropertyTypeOption {
  value: ResidentialType;
  label: string;
  description: string;
  icon: React.ComponentType<{ className?: string }>;
}

const PROPERTY_TYPES: PropertyTypeOption[] = [
  { value: 'FLAT', label: 'Flat', description: 'Apartment home', icon: Building2 },
  { value: 'HOUSE', label: 'House', description: 'Independent home', icon: Home },
  { value: 'STUDIO', label: 'Studio', description: 'Single-room home', icon: Building },
  { value: 'PENTHOUSE', label: 'Penthouse', description: 'Top-floor residence', icon: Sparkles },
  { value: 'SERVICED_APARTMENT', label: 'Serviced apartment', description: 'Furnished living', icon: Hotel }
];

const BHK_OPTIONS = ['1RK', '1BHK', '2BHK', '3BHK', '4+'] as const;
const BUTTON = 'inline-flex min-h-11 items-center justify-center gap-2 rounded-xl bg-emerald-700 px-5 py-2.5 text-sm font-semibold text-white transition-colors hover:bg-emerald-800 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50';
const SECONDARY = 'inline-flex min-h-11 items-center justify-center gap-2 rounded-xl border border-slate-300 bg-white px-4 py-2.5 text-sm font-semibold text-slate-700 hover:border-slate-400 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500';
const FIELD = 'min-h-11 w-full rounded-xl border border-slate-300 bg-white px-3 text-base text-slate-900 outline-none focus:border-emerald-600 focus:ring-2 focus:ring-emerald-200';

async function capability(method: 'GET' | 'POST'): Promise<{ enabled: boolean }> {
  const token = localStorage.getItem('pathome_auth_token');
  const response = await fetch(`${API_ROOT_URL}/lessor/capability`, {
    method,
    headers: { Authorization: `Bearer ${token}`, Accept: 'application/json' }
  });
  if (!response.ok) throw await createApiRequestError(response, 'Unable to open your property workspace.');
  return response.json();
}

export function LessorWorkspace({
  user,
  onRequestAuth
}: {
  user: UserProfile | null;
  onRequestAuth: (draftId: string, submit: boolean) => void;
}) {
  const location = useLocation();
  const navigate = useNavigate();
  const [capabilityState, setCapabilityState] = useState<'loading' | 'inactive' | 'active' | 'guest' | 'error'>(
    user ? 'loading' : 'guest'
  );
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [selectedType, setSelectedType] = useState<ResidentialType | null>(null);
  const [guestResume, setGuestResume] = useState<LessorDraft | null>(null);
  const [guestExpired, setGuestExpired] = useState(false);
  const [ownerDraft, setOwnerDraft] = useState(false);
  const [transition, setTransition] = useState<
    'idle' | 'claiming' | 'promoting' | 'submitting' | 'failedClaim' | 'failedMedia' | 'failedSubmit'
  >('idle');
  const [previewRevision, setPreviewRevision] = useState(0);
  const [showWorkspaceContactModal, setShowWorkspaceContactModal] = useState(false);
  const [pendingContactDraftId, setPendingContactDraftId] = useState<string | null>(null);
  const [workspaceContactInitial, setWorkspaceContactInitial] = useState<{ name: string; phone: string }>({ name: '', phone: '' });
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

  const handleWorkspaceContactSuccess = async () => {
    setShowWorkspaceContactModal(false);
    if (pendingContactDraftId) {
      const id = pendingContactDraftId;
      setPendingContactDraftId(null);
      await finishSubmission(id);
    }
  };

  const handleWorkspaceContactCancel = () => {
    setShowWorkspaceContactModal(false);
    const id = pendingContactDraftId;
    setPendingContactDraftId(null);
    setTransition('idle');
    if (id) {
      navigate(`/lessor/drafts/${encodeURIComponent(id)}`, { replace: true });
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
    claimBusy.current = true;
    setError('');
    setTransition('claiming');
    try {
      await lessorDraftService.claimGuest(id);
      setOwnerDraft(true);
      setCapabilityState('active');
      localStorage.removeItem('pathome_guest_draft_id');
      sessionStorage.removeItem('pathome_guest_save_draft');
      sessionStorage.removeItem('pathome_guest_submit_draft');
      if (submit) {
        try {
          const contact = await lessorContactService.getContact();
          if (!contact.complete) {
            setPendingContactDraftId(id);
            setWorkspaceContactInitial({
              name: contact.fullName || user?.fullName || '',
              phone: contact.phoneNumber || ''
            });
            setShowWorkspaceContactModal(true);
            setTransition('idle');
            return;
          }
        } catch {
          // If check fails, finishSubmission will handle the error appropriately
        }
        await finishSubmission(id);
      } else {
        await promoteOnly(id);
      }
    } catch (cause) {
      setError(getErrorMessage(cause, 'Could not save this guest draft to your account.'));
      setTransition('failedClaim');
    } finally {
      claimBusy.current = false;
    }
  };

  useEffect(() => {
    let live = true;
    if (!user) {
      setCapabilityState('guest');
      setOwnerDraft(false);
      if (!draftId)
        lessorDraftService
          .resumeGuest()
          .then(value => {
            if (!live) return;
            setGuestResume(value);
            setGuestExpired(!value && !!localStorage.getItem('pathome_guest_draft_id'));
            if (value && isNew) navigate(`/lessor/drafts/${encodeURIComponent(value.draftId)}`, { replace: true });
          })
          .catch(cause => {
            if (live) setError(getErrorMessage(cause, 'Could not check your saved draft.'));
          });
      return () => {
        live = false;
      };
    }
    if (ownerDraft && draftId?.startsWith('guest-')) {
      setCapabilityState('active');
      return () => {
        live = false;
      };
    }
    if (draftId?.startsWith('guest-') && !ownerDraft) {
      const pendingSubmit = sessionStorage.getItem('pathome_guest_submit_draft') === draftId;
      const pendingSave = sessionStorage.getItem('pathome_guest_save_draft') === draftId;
      setCapabilityState('loading');
      lessorDraftService
        .get(draftId)
        .then(() => {
          if (live) {
            sessionStorage.removeItem('pathome_guest_submit_draft');
            sessionStorage.removeItem('pathome_guest_save_draft');
            localStorage.removeItem('pathome_guest_draft_id');
            setOwnerDraft(true);
            setCapabilityState('active');
          }
        })
        .catch(cause => {
          if (!live) return;
          if (pendingSubmit || pendingSave) void claimDraft(draftId, pendingSubmit);
          else {
            setError(getErrorMessage(cause, 'This property draft is unavailable.'));
            setCapabilityState('error');
          }
        });
      return () => {
        live = false;
      };
    }
    setCapabilityState('loading');
    capability('GET')
      .then(async value => {
        if (!live) return;
        if (!value.enabled && (isNew || draftId)) await capability('POST');
        if (live) setCapabilityState(value.enabled || isNew || !!draftId ? 'active' : 'inactive');
      })
      .catch(cause => {
        if (live) {
          setError(getErrorMessage(cause, 'Unable to open your property workspace.'));
          setCapabilityState('error');
        }
      });
    return () => {
      live = false;
    };
  }, [user?.id, draftId, isNew, ownerDraft]);

  const activate = async () => {
    setBusy(true);
    setError('');
    try {
      await capability('POST');
      setCapabilityState('active');
    } catch (cause) {
      setError(getErrorMessage(cause, 'Unable to enable property management.'));
    } finally {
      setBusy(false);
    }
  };

  const create = async () => {
    if (!selectedType || busy) return;
    setBusy(true);
    setError('');
    try {
      const draft = await lessorDraftService.create(
        { propertyType: selectedType, rentalMode: 'LONG_TERM_RENTAL', bhkCount: null },
        !user
      );
      if (!user) localStorage.setItem('pathome_guest_draft_id', draft.draftId);
      navigate(`/lessor/drafts/${encodeURIComponent(draft.draftId)}`);
    } catch (cause) {
      setError(getErrorMessage(cause, 'Unable to start your draft.'));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="flex min-h-screen flex-col bg-slate-50">
      {/* ONBOARDING HEADER FOR /lessor/new */}
      {isNew && (
        <LessorOnboardingHeader
          status={null}
          guest={!user}
          canDiscard={Boolean(selectedType)}
          hasServerDraft={false}
          onDiscard={() => {
            setSelectedType(null);
            navigate(user ? '/lessor' : '/');
          }}
          onExit={() => {
            navigate(user ? '/lessor' : '/');
            return true;
          }}
          onExitToLanding={() => navigate('/')}
          currentStepLabel="Property Type"
        />
      )}

      <main className="mx-auto w-full max-w-6xl flex-1 px-4 pb-[calc(2.5rem+env(safe-area-inset-bottom))] pt-4 sm:px-6 lg:pt-8">
        {capabilityState === 'loading' && (
          <div className="flex min-h-48 items-center justify-center gap-3 text-slate-600">
            <LoaderCircle className="h-5 w-5 animate-spin" />
            Opening your workspace…
          </div>
        )}

        {capabilityState === 'error' && (
          <div role="alert" className="mx-auto max-w-lg rounded-2xl border border-rose-200 bg-rose-50 p-6 text-rose-800">
            <p>{error}</p>
            <button className={`${SECONDARY} mt-4`} onClick={() => window.location.reload()}>
              Retry
            </button>
          </div>
        )}

        {capabilityState === 'inactive' && (
          <section className="mx-auto max-w-xl py-8 sm:py-16">
            <div className="mb-5 flex h-12 w-12 items-center justify-center rounded-2xl bg-emerald-100 text-emerald-800">
              <Building2 />
            </div>
            <h1 className="font-['Outfit',sans-serif] text-3xl font-bold tracking-tight text-slate-950 sm:text-4xl">
              List your property
            </h1>
            <p className="mt-3 max-w-prose text-base leading-relaxed text-slate-600">
              Create a rental listing, save as you go, and submit it for review. Your tenant account stays available.
            </p>
            {error && <p role="alert" className="mt-5 text-sm text-rose-700">{error}</p>}
            <button type="button" disabled={busy} onClick={activate} className={`${BUTTON} mt-7`}>
              {busy ? 'Opening…' : 'Get started'}
              <ArrowRight className="h-4 w-4" />
            </button>
          </section>
        )}

        {/* ACTIVE DRAFT EDITOR */}
        {(capabilityState === 'active' || capabilityState === 'guest') && draftId && (
          <LessorEditor
            key={draftId}
            userId={user?.id ?? null}
            guest={(!ownerDraft && !user) || (!ownerDraft && draftId.startsWith('guest-'))}
            draftId={decodeURIComponent(draftId)}
            onBack={() => navigate(user ? '/lessor' : '/')}
            onRequestAuth={onRequestAuth}
            previewRevision={previewRevision}
            onDiscarded={() => {
              setGuestResume(null);
              setGuestExpired(false);
            }}
          />
        )}

        {/* PUBLISHED LISTING VIEW */}
        {capabilityState === 'active' && listingId && (
          <LessorListingView
            key={listingId}
            listingId={Number(listingId)}
            onBack={() => navigate('/lessor')}
            onOpenDraft={id => navigate(`/lessor/drafts/${encodeURIComponent(id)}`)}
          />
        )}

        {/* STEP 1: WHAT KIND OF HOME IS IT? (/lessor/new) */}
        {(capabilityState === 'active' || capabilityState === 'guest') && isNew && (
          <section className="space-y-6">
            {/* PROGRESS BREADCRUMB */}
            <div className="mx-auto max-w-4xl pb-4">
              <LessorProgressBar currentStep="type" />
            </div>

            {/* 2-COLUMN DESKTOP LAYOUT */}
            <div className="lg:grid lg:grid-cols-12 lg:gap-8 xl:gap-10">
              {/* LEFT: STEP 1 FORM */}
              <div className="lg:col-span-7 xl:col-span-7">
                <div className="rounded-2xl border border-slate-200/90 bg-white p-6 sm:p-8 shadow-xs">
                  <div className="flex items-center justify-between">
                    <button
                      type="button"
                      className="inline-flex items-center gap-1.5 text-xs font-semibold text-slate-600 hover:text-slate-900 transition-colors"
                      onClick={() => navigate(user ? '/lessor' : '/')}
                    >
                      <ArrowLeft className="h-4 w-4" />
                      Back
                    </button>
                    <span className="text-xs font-bold uppercase tracking-wider text-emerald-700">
                      Step 1 of 7
                    </span>
                  </div>

                  <h1 className="mt-4 font-['Outfit',sans-serif] text-2xl sm:text-3xl font-bold tracking-tight text-slate-950">
                    What kind of home is it?
                  </h1>
                  <p className="mt-2 text-sm text-slate-600 leading-relaxed">
                    Choose the closest match. This listing is for a long-term rental in supported localities.
                  </p>

                  {/* SELECTABLE PROPERTY TYPE CARDS */}
                  <div role="radiogroup" aria-label="Property type" className="mt-7 grid grid-cols-1 gap-3 sm:grid-cols-2">
                    {PROPERTY_TYPES.map(type => {
                      const isSelected = selectedType === type.value;
                      const Icon = type.icon;
                      return (
                        <button
                          key={type.value}
                          type="button"
                          role="radio"
                          aria-checked={isSelected}
                          onClick={() => setSelectedType(type.value)}
                          className={`group relative flex min-h-[96px] flex-col justify-between rounded-2xl border-2 p-4 text-left transition-all duration-150 motion-reduce:transition-none focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 cursor-pointer ${
                            isSelected
                              ? 'border-emerald-600 bg-emerald-50/70 shadow-xs ring-1 ring-emerald-600/30'
                              : 'border-slate-200 bg-white hover:border-slate-300 hover:bg-slate-50/60 shadow-xs'
                          }`}
                        >
                          <div className="flex items-start justify-between">
                            <div
                              className={`flex h-9 w-9 items-center justify-center rounded-xl transition-colors ${
                                isSelected
                                  ? 'bg-emerald-600 text-white'
                                  : 'bg-slate-100 text-slate-600 group-hover:bg-slate-200/70'
                              }`}
                            >
                              <Icon className="h-5 w-5" />
                            </div>
                            {isSelected && (
                              <div className="flex h-5 w-5 items-center justify-center rounded-full bg-emerald-600 text-white">
                                <Check className="h-3 w-3 stroke-[3]" />
                              </div>
                            )}
                          </div>
                          <div className="mt-3">
                            <span className="block text-base font-semibold text-slate-950">
                              {type.label}
                            </span>
                            <span
                              className={`block text-xs mt-0.5 ${
                                isSelected ? 'text-emerald-800 font-medium' : 'text-slate-500'
                              }`}
                            >
                              {type.description}
                            </span>
                          </div>
                        </button>
                      );
                    })}
                  </div>

                  {error && <p role="alert" className="mt-5 text-sm text-rose-700">{error}</p>}

                  <div className="mt-8 flex items-center justify-between pt-4 border-t border-slate-100">
                    <button
                      type="button"
                      className={SECONDARY}
                      onClick={() => navigate(user ? '/lessor' : '/')}
                    >
                      <ArrowLeft className="h-4 w-4" />
                      Back
                    </button>
                    <button
                      type="button"
                      disabled={!selectedType || busy}
                      onClick={create}
                      className={`${BUTTON} transition-all duration-150 ${
                        !selectedType ? 'opacity-40 cursor-not-allowed' : 'shadow-md shadow-emerald-700/20'
                      }`}
                    >
                      {busy ? (
                        <>
                          <LoaderCircle className="h-4 w-4 animate-spin" />
                          Starting…
                        </>
                      ) : (
                        <>
                          Continue
                          <ArrowRight className="h-4 w-4" />
                        </>
                      )}
                    </button>
                  </div>
                </div>
              </div>

              {/* RIGHT: DESKTOP LIVE PREVIEW */}
              <div className="hidden lg:col-span-5 lg:block xl:col-span-5">
                <LessorLivePreview
                  propertyType={selectedType}
                  bhkCount={null}
                  monthlyRent={null}
                  securityDeposit={null}
                  mediaCount={0}
                />
              </div>
            </div>
          </section>
        )}

        {/* GUEST WELCOME/START PAGE */}
        {capabilityState === 'guest' && !draftId && !isNew && (
          <section className="mx-auto max-w-xl py-8">
            <h1 className="font-['Outfit',sans-serif] text-3xl font-bold text-slate-950">
              List your property
            </h1>
            <p className="mt-3 text-sm text-slate-600">
              No account needed to start. Sign in when you're ready to submit.
            </p>
            {guestExpired && (
              <p role="status" className="mt-4 text-sm text-amber-800">
                Your previous guest draft is unavailable or has expired. You can start a new one.
              </p>
            )}
            {error && <p role="alert" className="mt-4 text-sm text-rose-700">{error}</p>}
            {guestResume && (
              <button
                className={`${BUTTON} mt-6`}
                onClick={() => navigate(`/lessor/drafts/${encodeURIComponent(guestResume.draftId)}`)}
              >
                Continue your property listing
                <ArrowRight className="h-4 w-4" />
              </button>
            )}
            <button
              className={`${SECONDARY} mt-6 ${guestResume ? 'ml-2' : ''}`}
              onClick={() => navigate('/lessor/new')}
            >
              Start property listing
            </button>
          </section>
        )}

        {/* AUTHENTICATED PORTFOLIO */}
        {capabilityState === 'active' && !draftId && !listingId && !isNew && (
          <LessorPortfolio
            onAdd={() => navigate('/lessor/new')}
            onOpenDraft={id => navigate(`/lessor/drafts/${encodeURIComponent(id)}`)}
            onOpenListing={id => navigate(`/lessor/listings/${id}`)}
          />
        )}

        {/* TRANSITION OVERLAY */}
        {transition !== 'idle' && (
          <div role="status" aria-live="polite" className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/45 p-4 backdrop-blur-xs">
            <div className="w-full max-w-sm rounded-2xl bg-white p-6 shadow-xl">
              <p className="text-base font-semibold text-slate-950">
                {transition === 'claiming'
                  ? 'Saving your property…'
                  : transition === 'promoting'
                  ? 'Preparing your photos…'
                  : transition === 'submitting'
                  ? 'Submitting for review…'
                  : transition === 'failedSubmit'
                  ? "Your property is saved. We couldn't submit it yet."
                  : transition === 'failedMedia'
                  ? "Your property is saved. We couldn't prepare its photos yet."
                  : 'We could not save this draft to your account.'}
              </p>
              {transition.startsWith('failed') && (
                <>
                  <p role="alert" className="mt-2 break-words text-sm text-rose-700">{error}</p>
                  <div className="mt-5 flex flex-wrap gap-3">
                    <button
                      className={BUTTON}
                      onClick={() => {
                        if (draftId)
                          void (transition === 'failedSubmit'
                            ? finishSubmission(draftId)
                            : transition === 'failedMedia'
                            ? promoteOnly(draftId)
                            : claimDraft(draftId, sessionStorage.getItem('pathome_guest_submit_draft') === draftId));
                      }}
                    >
                      {transition === 'failedSubmit'
                        ? 'Retry submission'
                        : transition === 'failedMedia'
                        ? 'Retry photos'
                        : 'Retry saving'}
                    </button>
                    {ownerDraft && (
                      <button
                        className={SECONDARY}
                        onClick={() => {
                          setTransition('idle');
                          navigate('/lessor');
                        }}
                      >
                        My Properties
                      </button>
                    )}
                  </div>
                </>
              )}
            </div>
          </div>
        )}
      </main>
      <LessorContactModal
        isOpen={showWorkspaceContactModal}
        initialFullName={workspaceContactInitial.name}
        initialPhoneNumber={workspaceContactInitial.phone}
        onSuccess={() => void handleWorkspaceContactSuccess()}
        onCancel={handleWorkspaceContactCancel}
      />
    </div>
  );
}

const STEP_LABELS: Record<'basics' | 'pricing' | 'location' | 'media' | 'details' | 'preview', string> = {
  basics: 'Configuration',
  pricing: 'Pricing',
  location: 'Location',
  media: 'Photos',
  details: 'Availability',
  preview: 'Review'
};

function LessorEditor({
  userId,
  draftId,
  onBack,
  guest,
  onRequestAuth,
  previewRevision,
  onDiscarded
}: {
  userId: number | null;
  draftId: string;
  onBack: () => void;
  guest: boolean;
  onRequestAuth: (draftId: string, submit: boolean) => void;
  previewRevision: number;
  onDiscarded: () => void;
}) {
  const navigate = useNavigate();
  const { notifySuccess } = useNotification();
  const [draft, setDraft] = useState<LessorDraft | null>(null);
  const [basics, setBasics] = useState<LessorBasics | null>(null);
  const [pricing, setPricing] = useState<LessorPricing>({ monthlyRent: null, securityDeposit: null });
  const [propertyLocation, setPropertyLocation] = useState<LessorLocation>({
    city: '',
    canonicalLocalityId: null,
    localityInput: '',
    address: '',
    landmark: ''
  });
  const [details, setDetails] = useState<LessorDetails>({
    availableFrom: null,
    furnishingStatus: '',
    totalAreaSqFt: null,
    floorNumber: null,
    totalFloors: null,
    amenities: '',
    description: ''
  });
  const [mediaItems, setMediaItems] = useState<LessorMediaItem[]>([]);
  const [cities, setCities] = useState<string[]>([]);
  const [suggestions, setSuggestions] = useState<LessorLocalityOption[]>([]);
  const [suggestionState, setSuggestionState] = useState<'idle' | 'loading' | 'error' | 'ready'>('idle');
  const [activeSuggestion, setActiveSuggestion] = useState(-1);
  const [suggestionDismissed, setSuggestionDismissed] = useState(false);
  const [locationFieldError, setLocationFieldError] = useState<'city' | 'locality' | 'address' | null>(null);
  const [showExactBhk, setShowExactBhk] = useState(false);
  const [step, setStep] = useState<'basics' | 'pricing' | 'location' | 'media' | 'details' | 'preview'>(() => {
    if (sessionStorage.getItem('pathome_guest_submit_draft') === draftId) return 'preview';
    const saved = localStorage.getItem(`pathome_guest_step_${draftId}`);
    return saved === 'pricing' || saved === 'location' || saved === 'media' || saved === 'details' || saved === 'preview'
      ? saved
      : 'basics';
  });
  const [direction, setDirection] = useState<number>(1);
  const [status, setStatus] = useState<LessorSaveStatus | null>(null);
  const [error, setError] = useState('');
  const [expired, setExpired] = useState(false);
  const queue = useRef<LessorAutosave | null>(null);
  const localityInputRef = useRef<HTMLInputElement>(null);
  const prefersReducedMotion = useReducedMotion();

  useEffect(() => {
    if (guest) localStorage.setItem(`pathome_guest_step_${draftId}`, step);
  }, [guest, draftId, step]);

  useEffect(() => {
    let live = true;
    setError('');
    lessorDraftService
      .get(draftId, guest)
      .then(server => {
        if (!live) return;
        setDraft(server);
        const saver = new LessorAutosave(
          guest ? 0 : userId ?? 0,
          draftId,
          server.version,
          setStatus,
          updated => {
            if (live)
              setDraft(previous =>
                previous
                  ? { ...previous, version: updated.version, completionPercent: updated.completionPercent }
                  : updated
              );
          },
          (id, section, version, value) => lessorDraftService.save(id, section, version, value, guest),
          !guest
        );
        queue.current = saver;
        const pending = saver.getPending();
        setBasics((pending.basics as LessorBasics) || server.data.basics);
        const restoredBhk = (pending.basics as LessorBasics | undefined)?.bhkCount || server.data.basics.bhkCount;
        setShowExactBhk(Boolean(restoredBhk && /^\d+BHK$/.test(restoredBhk) && Number.parseInt(restoredBhk) >= 4));
        setPricing((pending.pricing as LessorPricing) || server.data.pricing || { monthlyRent: null, securityDeposit: null });
        setPropertyLocation(
          (pending.location as LessorLocation) ||
            server.data.location || { city: '', canonicalLocalityId: null, localityInput: '', address: '', landmark: '' }
        );
        setDetails(
          (pending.details as LessorDetails) ||
            server.data.details || {
              availableFrom: null,
              furnishingStatus: '',
              totalAreaSqFt: null,
              floorNumber: null,
              totalFloors: null,
              amenities: '',
              description: ''
            }
        );
        setStatus(saver.getStatus());
      })
      .catch(cause => {
        if (live) {
          if (guest && cause instanceof ApiRequestError && cause.status === 404) {
            setExpired(true);
            setError('This guest draft is unavailable or has expired. You can start a new listing.');
          } else setError(getErrorMessage(cause, 'Unable to load this draft.'));
        }
      });
    return () => {
      live = false;
      queue.current?.dispose();
      queue.current = null;
    };
  }, [draftId, guest, guest ? null : userId]);

  useEffect(() => {
    if (step !== 'location') return;
    let live = true;
    lessorLocationService
      .cities(guest)
      .then(value => {
        if (live) setCities(value);
      })
      .catch(() => {
        if (live) setError('Supported cities could not be loaded. Retry this step.');
      });
    return () => {
      live = false;
    };
  }, [step, guest]);

  useEffect(() => {
    if (
      step !== 'location' ||
      !propertyLocation.city ||
      propertyLocation.localityInput.trim().length < 2 ||
      propertyLocation.resolutionType || propertyLocation.canonicalLocalityId || suggestionDismissed
    ) {
      setSuggestions([]);
      setSuggestionState('idle');
      return;
    }
    let live = true;
    const controller = new AbortController();
    setSuggestionState('loading');
    setSuggestions([]);
    setActiveSuggestion(-1);
    const timer = window.setTimeout(() => {
      lessorLocationService
        .suggestions(propertyLocation.city, propertyLocation.localityInput.trim(), guest, controller.signal)
        .then(value => {
          if (live) {
            setSuggestions(value);
            setSuggestionState('ready');
          }
        })
        .catch(() => {
          if (live) {
            setSuggestions([]);
            setSuggestionState('error');
          }
        });
    }, 250);
    return () => {
      live = false;
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [step, propertyLocation.city, propertyLocation.localityInput, propertyLocation.canonicalLocalityId,
      propertyLocation.resolutionType, guest, suggestionDismissed]);

  const chooseLocality = (option: LessorLocalityOption) => {
    const selected = chooseLocalityOption(propertyLocation, option);
    if (!selected) {
      setError(option.city !== propertyLocation.city
        ? `${option.name} is in ${option.city}. Change city to select it.`
        : 'This suggestion is unavailable. Continue with this locality for review.');
      return;
    }
    setError('');
    setLocationFieldError(null);
    updateLocation(selected);
    setSuggestions([]);
    localityInputRef.current?.focus();
  };

  const updateBasics = (value: LessorBasics) => {
    setBasics(value);
    queue.current?.change('basics', value);
  };
  const updatePricing = (value: LessorPricing) => {
    setPricing(value);
    queue.current?.change('pricing', value);
  };
  const updateLocation = (value: LessorLocation) => {
    setPropertyLocation(value);
    queue.current?.change('location', value);
  };
  const updateDetails = (value: LessorDetails) => {
    setDetails(value);
    queue.current?.change('details', value);
  };

  const requestAuth = async (submit: boolean) => {
    if (!(await queue.current?.flush())) {
      setError('Retry saving your changes before signing in.');
      return;
    }
    onRequestAuth(draftId, submit);
  };

  const next = async () => {
    setError('');
    setLocationFieldError(null);
    if (step === 'basics' && !basics?.bhkCount) {
      setError('Choose the exact configuration.');
      return;
    }
    if (step === 'pricing' && !pricingReady(pricing.monthlyRent, pricing.securityDeposit)) {
      setError('Add monthly rent and a deposit amount, including ₹0 if none.');
      return;
    }
    if (step === 'location') {
      const locationError = locationValidationError(propertyLocation, cities);
      if (locationError) {
        setError(locationError);
        setLocationFieldError(locationError.includes('city') ? 'city'
          : locationError.includes('street address') ? 'address' : 'locality');
        return;
      }
    }
    if (step === 'details' && !details.availableFrom) {
      setError('Add the availability date to continue.');
      return;
    }
    if (!(await queue.current?.flush())) {
      setError(
        status === 'conflict'
          ? 'A newer version exists. Review your other tab before continuing.'
          : guest
          ? 'Your changes are still in this tab. Retry the save before leaving.'
          : 'Your changes are saved on this device. Retry the server save to continue.'
      );
      return;
    }
    setDirection(1);
    setStep(
      step === 'basics'
        ? 'pricing'
        : step === 'pricing'
        ? 'location'
        : step === 'location'
        ? 'media'
        : step === 'details'
        ? 'preview'
        : 'preview'
    );
    window.scrollTo({ top: 0, behavior: 'instant' });
  };

  const stepBack = () => {
    setError('');
    setDirection(-1);
    setStep(
      step === 'location'
        ? 'pricing'
        : step === 'media'
        ? 'location'
        : step === 'details'
        ? 'media'
        : step === 'preview'
        ? 'details'
        : 'basics'
    );
    window.scrollTo({ top: 0, behavior: 'instant' });
  };

  const handleExit = async () => {
    const ok = await queue.current?.flush();
    if (ok) {
      onBack();
      return true;
    }
    return false;
  };

  const handleDiscard = async () => {
    await lessorDraftService.discard(draftId, guest);
    queue.current?.abandon();
    localStorage.removeItem(`pathome_lessor_unsynced_${userId ?? 0}_${draftId}`);
    localStorage.removeItem(`pathome_guest_step_${draftId}`);
    if (guest) {
      localStorage.removeItem('pathome_guest_draft_id');
      sessionStorage.removeItem('pathome_guest_submit_draft');
    }
    onDiscarded();
    const type = PROPERTY_TYPES.find(option => option.value === basics?.propertyType)?.label.toLowerCase() || 'property';
    const property = [basics?.bhkCount, type].filter(Boolean).join(' ');
    const place = [propertyLocation.localityInput, propertyLocation.city].filter(Boolean).join(', ');
    const description = place ? `${property} in ${place}` : property;
    notifySuccess(
      draft?.revisionOfListingId ? 'Changes discarded' : 'Property draft discarded',
      draft?.revisionOfListingId
        ? `Changes to your ${description} were discarded. Your published property remains available.`
        : `Your ${description} draft was discarded.`
    );
    navigate('/lessor', { replace: true });
  };

  if (error && !draft)
    return (
      <div role="alert" className="mx-auto max-w-xl rounded-2xl border border-rose-200 bg-rose-50 p-6 text-rose-800">
        <p className="font-semibold">{error}</p>
        <button className={`${SECONDARY} mt-4`} onClick={expired ? onBack : () => window.location.reload()}>
          {expired ? 'Back to property start' : 'Retry'}
        </button>
      </div>
    );

  if (!draft || !basics)
    return (
      <div className="flex min-h-48 items-center justify-center gap-3 text-slate-600">
        <LoaderCircle className="h-5 w-5 animate-spin" />
        Opening your draft…
      </div>
    );

  const currentBhk = showExactBhk ? '4+' : bhkChoice(basics.bhkCount);
  const coverUrl =
    mediaItems.find(m => m.cover && m.contentType.startsWith('image/'))?.url ||
    mediaItems.find(m => m.contentType.startsWith('image/'))?.url ||
    null;

  const stepVariants = {
    enter: (dir: number) => ({
      opacity: 0,
      x: prefersReducedMotion ? 0 : dir > 0 ? 16 : -16
    }),
    center: {
      opacity: 1,
      x: 0,
      transition: {
        duration: prefersReducedMotion ? 0 : 0.2,
        ease: 'easeInOut' as const
      }
    },
    exit: (dir: number) => ({
      opacity: 0,
      x: prefersReducedMotion ? 0 : dir > 0 ? -16 : 16,
      transition: {
        duration: prefersReducedMotion ? 0 : 0.16,
        ease: 'easeInOut' as const
      }
    })
  };

  return (
    <div className="space-y-6">
      {/* ONBOARDING HEADER */}
      <LessorOnboardingHeader
        status={status}
        guest={guest}
        onExit={handleExit}
        onExitToLanding={onBack}
        onRetrySave={() => {
          void queue.current?.flush();
        }}
        onRequestAuth={() => {
          void requestAuth(false);
        }}
        currentStepLabel={STEP_LABELS[step]}
        canDiscard={draft.status === 'DRAFT'}
        isRevision={Boolean(draft.revisionOfListingId)}
        hasServerDraft={true}
        onDiscard={handleDiscard}
      />

      {/* GUIDED PROGRESS EXPERIENCE */}
      <div className="mx-auto max-w-4xl pb-2">
        <LessorProgressBar currentStep={step as OnboardingStepKey} />
      </div>

      {/* GUEST BANNER */}
      {guest && (
        <div className="mx-auto max-w-4xl rounded-2xl border border-emerald-200/80 bg-emerald-50/70 p-3.5 sm:p-4 text-xs sm:text-sm text-emerald-950 flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-2">
            <Sparkles className="h-4 w-4 text-emerald-700 shrink-0" />
            <span>No account needed to start. Sign in when you're ready to submit.</span>
          </div>
          <button
            type="button"
            className="font-semibold text-emerald-800 underline underline-offset-2 hover:text-emerald-950 transition-colors cursor-pointer"
            onClick={() => {
              void requestAuth(false);
            }}
          >
            Sign in to save across devices
          </button>
        </div>
      )}

      {draft.revisionOfListingId && (
        <div className="mx-auto max-w-4xl rounded-xl border border-slate-200 bg-white p-3 text-xs text-slate-600">
          You are editing a revision. The approved property stays unchanged until these updates are reviewed.
        </div>
      )}

      {draft.reviewNote && (
        <div role="status" className="mx-auto max-w-4xl rounded-xl border border-amber-200 bg-amber-50 p-4 text-sm text-amber-950">
          <p className="font-semibold">Reviewer note</p>
          <p className="mt-1 whitespace-pre-wrap">{draft.reviewNote}</p>
        </div>
      )}

      {/* DESKTOP LAYOUT (2 COLUMNS FOR STEPS 2-6, FULL-WIDTH FOR REVIEW) */}
      <div className={step === 'preview' ? 'mx-auto max-w-3xl' : 'lg:grid lg:grid-cols-12 lg:gap-8 xl:gap-10'}>
        {/* LEFT COLUMN: ACTIVE STEP FORM */}
        <div className={step === 'preview' ? 'w-full' : 'lg:col-span-7 xl:col-span-7'}>
          <div className="rounded-2xl border border-slate-200/90 bg-white p-6 sm:p-8 shadow-xs">
            <AnimatePresence mode="wait" custom={direction}>
              <motion.div
                key={step}
                custom={direction}
                variants={stepVariants}
                initial="enter"
                animate="center"
                exit="exit"
              >
                {step === 'basics' && (
                  <>
                    <span className="text-xs font-bold uppercase tracking-wider text-emerald-700">
                      Step 2 of 7 · Configuration
                    </span>
                    <h1 className="mt-2 font-['Outfit',sans-serif] text-2xl sm:text-3xl font-bold tracking-tight text-slate-950">
                      Tell us about the home
                    </h1>
                    <p className="mt-2 text-sm text-slate-600">
                      Specify the configuration and type. You can adjust these details before submitting.
                    </p>

                    <label className="mt-7 block text-sm font-semibold text-slate-800" htmlFor="lessor-type">
                      Property type
                    </label>
                    <select
                      id="lessor-type"
                      className={`${FIELD} mt-2`}
                      value={basics.propertyType}
                      onBlur={() => {
                        void queue.current?.flush();
                      }}
                      onChange={event =>
                        updateBasics({ ...basics, propertyType: event.target.value as ResidentialType })
                      }
                    >
                      {PROPERTY_TYPES.map(type => (
                        <option key={type.value} value={type.value}>
                          {type.label} ({type.description})
                        </option>
                      ))}
                    </select>

                    <p className="mt-7 text-sm font-semibold text-slate-800">Configuration</p>
                    <div role="group" aria-label="Configuration" className="mt-2 grid grid-cols-3 gap-2 sm:grid-cols-5">
                      {BHK_OPTIONS.map(option => (
                        <button
                          key={option}
                          type="button"
                          aria-pressed={currentBhk === option}
                          onClick={() => {
                            setShowExactBhk(option === '4+');
                            updateBasics({ ...basics, bhkCount: exactBhk(option, 4) });
                          }}
                          className={`min-h-11 rounded-xl border px-2 text-sm font-semibold transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 cursor-pointer ${
                            currentBhk === option
                              ? 'border-emerald-700 bg-emerald-50 text-emerald-900 shadow-xs'
                              : 'border-slate-300 bg-white text-slate-700 hover:border-slate-400'
                          }`}
                        >
                          {option === '4+' ? '4+ BHK' : option === '1RK' ? '1 RK' : option.replace('BHK', ' BHK')}
                        </button>
                      ))}
                    </div>

                    {showExactBhk && (
                      <div className="mt-4">
                        <label htmlFor="lessor-bedrooms" className="text-sm font-semibold text-slate-800">
                          Exact number of bedrooms
                        </label>
                        <input
                          id="lessor-bedrooms"
                          type="number"
                          inputMode="numeric"
                          min={4}
                          max={99}
                          className={`${FIELD} mt-2 max-w-40`}
                          value={basics.bhkCount ? Number.parseInt(basics.bhkCount) : ''}
                          onBlur={() => {
                            void queue.current?.flush();
                          }}
                          onChange={event =>
                            updateBasics({
                              ...basics,
                              bhkCount: event.target.value === '' ? null : exactBhk('4+', Number(event.target.value))
                            })
                          }
                        />
                      </div>
                    )}
                  </>
                )}

                {step === 'pricing' && (
                  <>
                    <span className="text-xs font-bold uppercase tracking-wider text-emerald-700">
                      Step 3 of 7 · Pricing
                    </span>
                    <h1 className="mt-2 font-['Outfit',sans-serif] text-2xl sm:text-3xl font-bold tracking-tight text-slate-950">
                      Set your rent
                    </h1>
                    <p className="mt-2 text-sm text-slate-600">
                      Enter the monthly amount and the deposit you require.
                    </p>

                    <div className="mt-7 grid gap-5 sm:grid-cols-2">
                      <div>
                        <label className="text-sm font-semibold text-slate-800" htmlFor="lessor-rent">
                          Monthly rent
                        </label>
                        <div className="relative mt-2">
                          <span className="pointer-events-none absolute left-3 top-3 text-slate-500">₹</span>
                          <input
                            id="lessor-rent"
                            type="number"
                            inputMode="decimal"
                            min={1}
                            className={`${FIELD} pl-7`}
                            value={pricing.monthlyRent ?? ''}
                            onBlur={() => {
                              void queue.current?.flush();
                            }}
                            onChange={event =>
                              updatePricing({
                                ...pricing,
                                monthlyRent: event.target.value === '' ? null : Number(event.target.value)
                              })
                            }
                          />
                        </div>
                      </div>
                      <div>
                        <label className="text-sm font-semibold text-slate-800" htmlFor="lessor-deposit">
                          Security deposit
                        </label>
                        <div className="relative mt-2">
                          <span className="pointer-events-none absolute left-3 top-3 text-slate-500">₹</span>
                          <input
                            id="lessor-deposit"
                            type="number"
                            inputMode="decimal"
                            min={0}
                            className={`${FIELD} pl-7`}
                            value={pricing.securityDeposit ?? ''}
                            onBlur={() => {
                              void queue.current?.flush();
                            }}
                            onChange={event =>
                              updatePricing({
                                ...pricing,
                                securityDeposit: event.target.value === '' ? null : Number(event.target.value)
                              })
                            }
                          />
                        </div>
                        <p className="mt-1 text-xs text-slate-500">Enter 0 if no deposit required.</p>
                      </div>
                    </div>
                  </>
                )}

                {step === 'location' && (
                  <>
                    <span className="text-xs font-bold uppercase tracking-wider text-emerald-700">
                      Step 4 of 7 · Location
                    </span>
                    <h1 className="mt-2 font-['Outfit',sans-serif] text-2xl sm:text-3xl font-bold tracking-tight text-slate-950">
                      Where is the home?
                    </h1>
                    <p className="mt-2 text-sm text-slate-600">
                      The full address stays private. Tenants see only the locality and city.
                    </p>

                    <div className="mt-7 space-y-5">
                      <div>
                        <label htmlFor="lessor-city" className="text-sm font-semibold text-slate-800">
                          City
                        </label>
                        <select
                          id="lessor-city"
                          aria-invalid={locationFieldError === 'city'}
                          aria-describedby={locationFieldError === 'city' ? 'lessor-step-error' : undefined}
                          className={`${FIELD} mt-2`}
                          value={propertyLocation.city}
                          onChange={event => {
                            setSuggestionDismissed(false);
                            setLocationFieldError(null);
                            updateLocation(changeLocationCity(propertyLocation, event.target.value));
                          }}
                          onBlur={() => {
                            void queue.current?.flush();
                          }}
                        >
                          <option value="">Choose city</option>
                          {cities.map(city => (
                            <option key={city} value={city}>
                              {city}
                            </option>
                          ))}
                        </select>
                      </div>

                      <div>
                        <label htmlFor="lessor-locality" className="text-sm font-semibold text-slate-800">
                          Locality
                        </label>
                        <input
                          id="lessor-locality"
                          ref={localityInputRef}
                          aria-invalid={locationFieldError === 'locality'}
                          aria-describedby={locationFieldError === 'locality' ? 'lessor-step-error' : undefined}
                          type="search"
                          autoComplete="off"
                          className={`${FIELD} mt-2`}
                          value={propertyLocation.localityInput}
                          disabled={!propertyLocation.city}
                          maxLength={120}
                          role="combobox"
                          aria-autocomplete="list"
                          aria-expanded={suggestions.length > 0 && !suggestionDismissed}
                          aria-controls={suggestions.length > 0 ? 'lessor-locality-options' : undefined}
                          aria-activedescendant={activeSuggestion >= 0 && suggestions[activeSuggestion] && !suggestionDismissed
                            ? `lessor-locality-option-${activeSuggestion}` : undefined}
                          onKeyDown={event => {
                            if (event.key === 'Escape') { setSuggestionDismissed(true); setSuggestions([]); return; }
                            if (event.key === 'ArrowDown' && suggestions.length) {
                              event.preventDefault(); setActiveSuggestion(value => Math.min(value + 1, suggestions.length - 1));
                            } else if (event.key === 'ArrowUp' && suggestions.length) {
                              event.preventDefault(); setActiveSuggestion(value => Math.max(value - 1, 0));
                            } else if (event.key === 'Enter' && activeSuggestion >= 0 && suggestions[activeSuggestion]) {
                              event.preventDefault(); chooseLocality(suggestions[activeSuggestion]);
                            }
                          }}
                          onChange={event => {
                            setSuggestionDismissed(false);
                            setLocationFieldError(null);
                            updateLocation(changeLocalityText(propertyLocation, event.target.value));
                          }}
                          onBlur={() => {
                            void queue.current?.flush();
                          }}
                          placeholder="Start typing your locality (e.g. Vijay Nagar)"
                        />
                        {propertyLocation.resolutionType || propertyLocation.canonicalLocalityId ? (
                          <div role="status" className="mt-2.5 flex min-w-0 items-center gap-2 rounded-xl border border-emerald-200/80 bg-emerald-50 px-3.5 py-2 text-sm font-semibold text-emerald-950">
                            <Check className="h-4 w-4 text-emerald-700 stroke-[3]" />
                            <span className="min-w-0 break-words">
                              {propertyLocation.localityInput}, {propertyLocation.city}
                              {propertyLocation.resolutionType === 'MANUAL_PENDING' || propertyLocation.resolutionType === 'EXTERNAL_RESOLVED'
                                ? ' · We’ll verify this locality during review.' : ' · Locality selected'}
                            </span>
                          </div>
                        ) : (
                          propertyLocation.localityInput.trim().length >= 1 && !suggestionDismissed && (
                            <div className="mt-2 h-36 overflow-y-auto rounded-xl border border-slate-200 bg-white p-2 shadow-sm"
                              onKeyDown={event => {
                                if (event.key === 'Escape') {
                                  setSuggestionDismissed(true); setSuggestions([]); localityInputRef.current?.focus();
                                }
                              }}>
                              {suggestionState === 'loading' && (
                                <p role="status" className="px-2 py-2 text-sm text-slate-500">Finding localities…</p>
                              )}
                              {suggestionState === 'error' && (
                                <p role="status" className="px-2 py-2 text-sm text-slate-600">
                                  Suggestions are unavailable right now. You can continue with this locality for review.
                                </p>
                              )}
                              {suggestionState === 'ready' && suggestions.length === 0 && (
                                <p role="status" className="px-2 py-2 text-sm text-slate-600">
                                  Couldn’t find an exact match.
                                </p>
                              )}
                              {suggestions.length > 0 && <div id="lessor-locality-options" role="listbox" aria-label="Locality suggestions">{suggestions.map((option, index) => (
                                <button
                                  key={`${option.city}-${option.id ?? option.providerPlaceId}`}
                                  id={`lessor-locality-option-${index}`}
                                  role="option"
                                  aria-selected={activeSuggestion === index}
                                  type="button"
                                  className={`flex min-h-11 w-full items-center justify-between gap-3 rounded-lg px-3 text-left text-sm text-slate-800 hover:bg-emerald-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 cursor-pointer ${activeSuggestion === index ? 'bg-emerald-50' : ''}`}
                                  onMouseDown={event => event.preventDefault()}
                                  onClick={() => chooseLocality(option)}
                                >
                                  <span className="min-w-0 break-words">
                                    <span className="block font-medium">{option.name}</span>
                                    <span className="block text-xs text-slate-500">{option.city}, India</span>
                                  </span>
                                  {option.match === 'different_city' && (
                                    <span className="shrink-0 text-xs font-semibold text-amber-700">
                                      Different city
                                    </span>
                                  )}
                                </button>
                              ))}</div>}
                              <button type="button" className="mt-1 flex min-h-11 w-full min-w-0 items-center break-words rounded-lg px-3 text-left text-sm font-semibold text-emerald-800 hover:bg-emerald-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500" onClick={() => {
                                updateLocation(useLocalityForReview(propertyLocation));
                                setSuggestions([]); setError(''); setLocationFieldError(null);
                                localityInputRef.current?.focus();
                              }}>Use “{propertyLocation.localityInput.trim()}”</button>
                              <p className="px-3 pb-1 text-xs text-slate-500">We’ll verify this locality during property review.</p>
                            </div>
                          )
                        )}
                      </div>

                      <div>
                        <label htmlFor="lessor-address" className="text-sm font-semibold text-slate-800">
                          Street address
                        </label>
                        <input
                          id="lessor-address"
                          aria-invalid={locationFieldError === 'address'}
                          aria-describedby={locationFieldError === 'address' ? 'lessor-step-error' : undefined}
                          className={`${FIELD} mt-2`}
                          maxLength={500}
                          value={propertyLocation.address}
                          onChange={event => {
                            setLocationFieldError(null);
                            updateLocation({ ...propertyLocation, address: event.target.value });
                          }}
                          onBlur={() => {
                            void queue.current?.flush();
                          }}
                          placeholder="Building name, flat number, street"
                        />
                        <p className="mt-1 text-xs text-slate-500">
                          Only Pathome's review team sees the full address.
                        </p>
                      </div>

                      <div>
                        <label htmlFor="lessor-landmark" className="text-sm font-semibold text-slate-800">
                          Landmark <span className="font-normal text-slate-500">(optional)</span>
                        </label>
                        <input
                          id="lessor-landmark"
                          className={`${FIELD} mt-2`}
                          maxLength={200}
                          value={propertyLocation.landmark}
                          onChange={event =>
                            updateLocation({ ...propertyLocation, landmark: event.target.value })
                          }
                          onBlur={() => {
                            void queue.current?.flush();
                          }}
                          placeholder="Nearby recognizable spot"
                        />
                      </div>
                    </div>
                  </>
                )}

                {step === 'media' && (
                  <LessorMediaStep
                    draftId={draftId}
                    guest={guest}
                    onMediaChange={items => setMediaItems(items)}
                    onNext={() => {
                      setDirection(1);
                      setStep('details');
                      window.scrollTo({ top: 0, behavior: 'instant' });
                    }}
                  />
                )}

                {step === 'details' && (
                  <>
                    <LessorDetailsStep
                      value={details}
                      onChange={updateDetails}
                      onBlur={() => {
                        void queue.current?.flush();
                      }}
                    />
                    <p className="mt-4 text-xs font-semibold text-emerald-800">
                      Next up: Your property is ready to preview.
                    </p>
                  </>
                )}

                {step === 'preview' && (
                  <LessorPreviewStep
                    key={previewRevision}
                    draftId={draftId}
                    guest={guest}
                    onGuestSubmit={() => {
                      void requestAuth(true);
                    }}
                    onEdit={section => {
                      setError('');
                      setDirection(-1);
                      setStep(section);
                    }}
                    onDone={onBack}
                  />
                )}
              </motion.div>
            </AnimatePresence>

            {error && <p id="lessor-step-error" role="alert" className="mt-6 text-sm font-semibold text-rose-700">{error}</p>}

            {status === 'error' && (
              <button
                type="button"
                className={`${SECONDARY} mt-4`}
                onClick={() => {
                  void queue.current?.flush();
                }}
              >
                <RefreshCw className="h-4 w-4" />
                Retry save
              </button>
            )}

            {status === 'conflict' && (
              <p className="mt-3 text-sm text-rose-700">
                Another tab saved a newer version. Your unsynced entries remain{' '}
                {guest ? 'in this tab' : 'on this device'}. Copy them before reloading this draft.
              </p>
            )}

            {/* STEP NAVIGATION BUTTONS (BACK & CONTINUE) */}
            {step !== 'preview' && (
              <div className="mt-8 flex items-center justify-between border-t border-slate-100 pt-5">
                <button
                  type="button"
                  className={SECONDARY}
                  onClick={stepBack}
                >
                  <ArrowLeft className="h-4 w-4" />
                  Back
                </button>
                {step !== 'media' && (
                  <button
                    type="button"
                    disabled={status === 'conflict'}
                    className={BUTTON}
                    onClick={() => {
                      void next();
                    }}
                  >
                    Continue
                    <ArrowRight className="h-4 w-4" />
                  </button>
                )}
              </div>
            )}
          </div>
        </div>

        {/* RIGHT COLUMN: DESKTOP LIVE PREVIEW PANEL (HIDDEN ON REVIEW STEP & MOBILE) */}
        {step !== 'preview' && (
          <div className="hidden lg:col-span-5 lg:block xl:col-span-5">
            <LessorLivePreview
              propertyType={basics.propertyType}
              bhkCount={basics.bhkCount}
              monthlyRent={pricing.monthlyRent}
              securityDeposit={pricing.securityDeposit}
              locality={propertyLocation.localityInput}
              city={propertyLocation.city}
              coverUrl={coverUrl}
              mediaCount={mediaItems.length}
              availableFrom={details.availableFrom}
              furnishingStatus={details.furnishingStatus}
              totalAreaSqFt={details.totalAreaSqFt}
            />
          </div>
        )}
      </div>
    </div>
  );
}
