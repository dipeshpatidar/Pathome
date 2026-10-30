import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
  ArrowDown, ArrowUp, CheckCircle2, ImagePlus, LoaderCircle, Save, Send, Star,
  Trash2, TriangleAlert
} from 'lucide-react';
import { getErrorMessage } from '../../services/apiError';
import {
  backofficePropertyService,
  newOperationId,
  type PropertyDetail,
  type RoomTag,
  type StaffMedia,
  type StaffRevision,
  type StaffSnapshot
} from '../../services/backofficePropertyService';
import { emitPropertyCurrentChanged } from '../../utils/propertyCurrentEvents';
import {
  operationIdForIntent,
  operationIntentKey,
  type OperationIntent
} from '../../utils/operationIntent.ts';
import { captureOperationsSession } from '../../utils/operationsSession.ts';
import {
  ExclusiveOperationGate,
  isUncertainCommandFailure,
  statusOf,
  type OperationOwner
} from '../../utils/operationOwnership.ts';
import {
  applySavedEditorSection,
  applyRecoveredMedia,
  dirtyEditorSections,
  editorSectionMatches,
  editorSectionValues,
  isEditorSectionDirty,
  type BackofficeSectionKey
} from '../../utils/backofficeEditorState.ts';

const FIELD = 'min-h-11 w-full rounded-lg border border-[#dddcd5] bg-white px-3 py-2 text-base text-[#29322b] outline-none transition focus:border-[#718b73] focus:ring-2 focus:ring-[#e6ece4]';
const PRIMARY = 'inline-flex min-h-11 items-center justify-center gap-2 rounded-lg bg-[#355c49] px-4 py-2 text-sm font-semibold text-white transition hover:bg-[#284a38] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#8aa08b] disabled:cursor-not-allowed disabled:opacity-50';
const SECONDARY = 'inline-flex min-h-11 items-center justify-center gap-2 rounded-lg border border-[#dddcd5] bg-white px-4 py-2 text-sm font-semibold text-[#3f5d47] transition hover:bg-[#f5f6f2] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#8aa08b] disabled:cursor-not-allowed disabled:opacity-50';
const DANGER = 'inline-flex min-h-11 items-center justify-center gap-2 rounded-lg border border-rose-200 bg-white px-4 py-2 text-sm font-semibold text-rose-700 transition hover:bg-rose-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-rose-400 disabled:cursor-not-allowed disabled:opacity-50';
const TAGS: RoomTag[] = ['GENERAL', 'LIVING_ROOM', 'MASTER_BEDROOM', 'BEDROOM', 'KITCHEN',
  'BATHROOM', 'BALCONY', 'EXTERIOR', 'AMENITIES', 'FLOOR_PLAN'];
const EDITOR_SECTIONS: ReadonlyArray<{
  key: BackofficeSectionKey;
  id: string;
  eyebrow: string;
  label: string;
  title: string;
}> = [
  { key: 'basics', id: 'bo-section-basics', eyebrow: '01 · Identity and description', label: 'Identity and description', title: 'How renters understand this home' },
  { key: 'commercial', id: 'bo-section-commercial', eyebrow: '02 · Commercial terms', label: 'Commercial terms', title: 'Rent, deposit and availability' },
  { key: 'details', id: 'bo-section-details', eyebrow: '03 · Home details', label: 'Home details', title: 'Space, fixtures and preferences' },
  { key: 'address', id: 'bo-section-address', eyebrow: '04 · Presentation address', label: 'Presentation address', title: 'Private address shown in approved contexts' },
  { key: 'media', id: 'bo-section-media', eyebrow: '05 · Media', label: 'Media', title: 'Property photos and walkthrough' }
];
const REVIEW_SECTION_ID = 'bo-section-review';
const sectionLabel = (key: BackofficeSectionKey) =>
  EDITOR_SECTIONS.find(section => section.key === key)?.label ?? 'This section';

const nullableNumber = (value: string): number | null => value.trim() === '' ? null : Number(value);
const nullableInteger = (value: string): number | null => {
  const parsed = nullableNumber(value);
  return parsed === null ? null : Math.trunc(parsed);
};

function Section({ id, title, eyebrow, state, stateTone = 'neutral', children }: {
  id: string;
  title: string;
  eyebrow: string;
  state?: string;
  stateTone?: 'saved' | 'unsaved' | 'saving' | 'neutral';
  children: React.ReactNode;
}) {
  return <section id={id} tabIndex={-1} className="backoffice-card backoffice-editor-section">
    <header className="backoffice-editor-section-head">
      <div>
        <p className="backoffice-eyebrow">{eyebrow}</p>
        <h2 className="backoffice-section-title">{title}</h2>
      </div>
      {state && <span className={`backoffice-editor-state backoffice-editor-state--${stateTone}`}>{state}</span>}
    </header>
    {children}
  </section>;
}

function Field({ label, htmlFor, hint, children }: {
  label: string; htmlFor: string; hint?: string; children: React.ReactNode;
}) {
  return <label htmlFor={htmlFor} className="grid min-w-0 gap-1.5 text-sm font-semibold text-[#4d594f]">
    <span>{label}</span>
    {children}
    {hint && <span className="text-xs font-normal leading-relaxed text-[#7d867d]">{hint}</span>}
  </label>;
}

export function BackofficePropertyEditor({ detail, revision, onRevision, onPublished, onDiscarded }: {
  detail: PropertyDetail;
  revision: StaffRevision;
  onRevision: (revision: StaffRevision) => void;
  onPublished: () => void;
  onDiscarded: () => void;
}) {
  const [draft, setDraft] = useState<StaffSnapshot>(revision.content);
  const [savedContent, setSavedContent] = useState<StaffSnapshot>(revision.content);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [activeSection, setActiveSection] = useState<string>(EDITOR_SECTIONS[0].id);
  const [sectionFeedback, setSectionFeedback] = useState<Partial<Record<BackofficeSectionKey, {
    tone: 'saved' | 'error';
    message: string;
  }>>>({});
  const [publishReason, setPublishReason] = useState('');
  const [confirmPublish, setConfirmPublish] = useState(false);
  const [failedUpload, setFailedUpload] = useState<{ mediaId: string; file: File; message: string } | null>(null);
  const [confirmDiscard, setConfirmDiscard] = useState(false);
  const [discardReason, setDiscardReason] = useState('');
  const fileInput = useRef<HTMLInputElement>(null);
  const publishIntent = useRef<OperationIntent | null>(null);
  const operationGate = useRef(new ExclusiveOperationGate());
  const editorGeneration = useRef(0);
  const mounted = useRef(true);

  const currentSessionIdentity = () => captureOperationsSession()?.identity ?? '';
  const beginOperation = (label: string): OperationOwner | null => {
    const owner = operationGate.current.begin(
      currentSessionIdentity(), detail.listingId.toString(), revision.draftId, editorGeneration.current);
    if (!owner) return null;
    setBusy(label);
    setError('');
    setNotice('');
    return owner;
  };
  const ownsOperation = (owner: OperationOwner): boolean =>
    mounted.current && operationGate.current.owns(owner)
    && owner.sessionIdentity === currentSessionIdentity();
  const finishOperation = (owner: OperationOwner): void => {
    if (operationGate.current.finish(owner)) setBusy(null);
  };

  useEffect(() => {
    mounted.current = true;
    editorGeneration.current += 1;
    operationGate.current.invalidate();
    setBusy(null);
    setError('');
    setNotice('');
    setFailedUpload(null);
    setConfirmPublish(false);
    setConfirmDiscard(false);
    return () => {
      mounted.current = false;
      editorGeneration.current += 1;
      operationGate.current.invalidate();
    };
  }, [detail.listingId, revision.draftId]);

  useEffect(() => {
    setDraft(revision.content);
    setSavedContent(revision.content);
    setSectionFeedback({});
    setActiveSection(EDITOR_SECTIONS[0].id);
    let live = true;
    void backofficePropertyService.listMedia(detail.listingId, revision.draftId)
      .then(rows => {
        if (!live) return;
        const recovered = rows.map(row => ({
          sourceMediaAssetId: null,
          mediaId: row.mediaId,
          url: row.url,
          mediaType: row.mediaType,
          roomTag: row.roomTag,
          caption: row.caption,
          sortOrder: row.sortOrder,
          cover: row.cover,
          uploadStatus: row.uploadStatus
        }));
        const addMissingMedia = (snapshot: StaffSnapshot): StaffSnapshot => {
          const existing = new Set(snapshot.media.map(item => item.mediaId));
          return {
            ...snapshot,
            media: [...snapshot.media, ...recovered.filter(row => !existing.has(row.mediaId))]
          };
        };
        setDraft(addMissingMedia);
        setSavedContent(addMissingMedia(revision.content));
      }).catch(() => {
        // The revision payload still provides the previously saved membership.
      });
    return () => { live = false; };
  }, [detail.listingId, revision.draftId]);

  useEffect(() => {
    const ids = [...EDITOR_SECTIONS.map(section => section.id), REVIEW_SECTION_ID];
    const visible = new Map<string, number>();
    const observer = new IntersectionObserver(entries => {
      entries.forEach(entry => {
        if (entry.isIntersecting) visible.set(entry.target.id, entry.intersectionRatio);
        else visible.delete(entry.target.id);
      });
      const current = [...visible.entries()].sort((left, right) => right[1] - left[1])[0]?.[0];
      if (current) setActiveSection(current);
    }, { rootMargin: '-90px 0px -55% 0px', threshold: [0, .25, .6] });
    ids.forEach(id => {
      const element = document.getElementById(id);
      if (element) observer.observe(element);
    });
    return () => observer.disconnect();
  }, []);

  const update = <K extends keyof StaffSnapshot>(key: K, value: StaffSnapshot[K]) =>
    setDraft(current => ({ ...current, [key]: value }));

  const save = async (
    section: BackofficeSectionKey,
    operation: () => Promise<StaffRevision>
  ) => {
    const owner = beginOperation(section);
    if (!owner) return;
    const label = sectionLabel(section);
    const submitted = editorSectionValues(draft, section);
    setSectionFeedback(current => ({ ...current, [section]: { tone: 'saved', message: `Saving ${label.toLowerCase()}…` } }));
    try {
      const saved = await operation();
      if (!ownsOperation(owner)) return;
      onRevision(saved);
      setSavedContent(saved.content);
      setDraft(current => editorSectionMatches(current, section, submitted)
        ? applySavedEditorSection(current, saved.content, section)
        : current);
      setSectionFeedback(current => ({
        ...current,
        [section]: { tone: 'saved', message: `${label} saved.` }
      }));
      setNotice(`${label} saved.`);
    } catch (cause) {
      if (!ownsOperation(owner)) return;
      const statusCode = statusOf(cause);
      const message = statusCode === 409
        ? 'This staff draft changed elsewhere. Your edits are still here; reload the latest property before saving again.'
        : statusCode
          ? getErrorMessage(cause, `${label} could not be saved.`)
          : `${label} was not confirmed by the server. Your edits are still here; check the current property before trying again.`;
      setSectionFeedback(current => ({
        ...current,
        [section]: { tone: 'error', message: 'Not saved. Check the message above.' }
      }));
      setError(message);
    } finally {
      finishOperation(owner);
    }
  };

  const saveBasics = () => save('basics', () =>
    backofficePropertyService.saveBasics(detail.listingId, revision.draftId, revision.version, {
      title: draft.title,
      description: draft.description,
      bhkCount: draft.bhkCount,
      propertyType: draft.propertyType
    }));

  const saveCommercial = () => save('commercial', () =>
    backofficePropertyService.saveCommercial(detail.listingId, revision.draftId, revision.version, {
      monthlyRent: draft.monthlyRent,
      securityDeposit: draft.securityDeposit,
      availableFrom: draft.availableFrom,
      maintenanceCharge: draft.maintenanceCharge,
      brokerageAmount: draft.brokerageAmount,
      brokerageDays: draft.brokerageDays,
      securityDepositMonths: draft.securityDepositMonths
    }));

  const saveDetails = () => save('details', () =>
    backofficePropertyService.saveDetails(detail.listingId, revision.draftId, revision.version, {
      totalAreaSqFt: draft.totalAreaSqFt,
      floorNumber: draft.floorNumber,
      totalFloors: draft.totalFloors,
      furnishingStatus: draft.furnishingStatus,
      amenities: draft.amenities,
      bathroomCount: draft.bathroomCount,
      vastuFacing: draft.vastuFacing,
      bachelorAllowed: draft.bachelorAllowed,
      preferredTenant: draft.preferredTenant
    }));

  const saveAddress = () => save('address', () =>
    backofficePropertyService.saveAddress(detail.listingId, revision.draftId, revision.version, {
      address: draft.address,
      landmark: draft.landmark,
      colony: draft.colony,
      pincode: draft.pincode
    }));

  const saveMedia = () => save('media', () =>
    backofficePropertyService.saveMedia(detail.listingId, revision.draftId, revision.version,
      draft.media.map((item, index) => ({
        mediaId: item.mediaId,
        roomTag: item.roomTag,
        caption: item.caption,
        sortOrder: index,
        cover: item.cover
      }))));

  const upload = async (file: File, mediaId = newOperationId()) => {
    const owner = beginOperation('Media upload');
    if (!owner) return;
    try {
      const uploaded = await backofficePropertyService.uploadMedia(
        detail.listingId, revision.draftId, revision.version, mediaId, file);
      if (!ownsOperation(owner)) return;
      setDraft(current => ({
        ...current,
        media: [...current.media, {
          sourceMediaAssetId: null,
          mediaId: uploaded.mediaId,
          url: uploaded.url,
          mediaType: uploaded.mediaType,
          roomTag: uploaded.roomTag,
          caption: uploaded.caption,
          sortOrder: uploaded.sortOrder,
          cover: uploaded.cover,
          uploadStatus: uploaded.uploadStatus
        }]
      }));
      setFailedUpload(null);
      setNotice('Media uploaded. Choose its cover, room label and position, then save media presentation.');
    } catch (cause) {
      if (!ownsOperation(owner)) return;
      const message = getErrorMessage(cause, 'The media file could not be uploaded.');
      setFailedUpload({ mediaId, file, message });
      setError(message);
    } finally {
      if (ownsOperation(owner) && fileInput.current) fileInput.current.value = '';
      finishOperation(owner);
    }
  };

  const setMedia = (index: number, change: Partial<StaffMedia>) =>
    setDraft(current => ({
      ...current,
      media: current.media.map((item, itemIndex) => itemIndex === index ? { ...item, ...change } : item)
    }));

  const makeCover = (index: number) =>
    setDraft(current => ({
      ...current,
      media: current.media.map((item, itemIndex) => ({
        ...item,
        cover: itemIndex === index && item.mediaType === 'IMAGE'
      }))
    }));

  const recoverUpload = async (mediaId: string) => {
    const owner = beginOperation('Media recovery');
    if (!owner) return;
    try {
      const recovered = await backofficePropertyService.recoverMedia(
        detail.listingId, revision.draftId, revision.version, mediaId);
      if (!ownsOperation(owner)) return;
      setDraft(current => applyRecoveredMedia(current, mediaId, recovered));
      setNotice('Media upload recovered. Review its cover, room label and position, then save media presentation.');
    } catch (cause) {
      if (!ownsOperation(owner)) return;
      setError(getErrorMessage(cause, 'The upload could not be recovered. Choose the original file again.'));
    } finally {
      finishOperation(owner);
    }
  };

  const move = (index: number, direction: -1 | 1) => {
    const target = index + direction;
    if (target < 0 || target >= draft.media.length) return;
    setDraft(current => {
      const media = [...current.media];
      [media[index], media[target]] = [media[target], media[index]];
      return { ...current, media };
    });
  };

  const publish = async () => {
    const reason = publishReason.trim();
    if (!reason) return;
    if (dirty || !revision.preview.ready) {
      setError(dirty
        ? 'Save the remaining sections before publishing.'
        : 'Resolve the publication checks before publishing.');
      return;
    }
    const owner = beginOperation('Publish');
    if (!owner) return;
    const intentKey = operationIntentKey([
      currentSessionIdentity(),
      detail.listingId,
      revision.draftId,
      revision.version,
      detail.version,
      reason
    ]);
    publishIntent.current = operationIdForIntent(publishIntent.current, intentKey);
    const submittedIntent = publishIntent.current;
    try {
      const result = await backofficePropertyService.publish(
        detail.listingId, revision.draftId, revision.version, detail.version, reason,
        submittedIntent.id);
      if (!ownsOperation(owner)) return;
      publishIntent.current = null;
      setNotice(`Published content version ${result.publicationVersion}.`);
      emitPropertyCurrentChanged({ listingId: result.listingId, reason: 'PUBLISHED' });
      setConfirmPublish(false);
      onPublished();
    } catch (cause) {
      if (!ownsOperation(owner)) return;
      if (isUncertainCommandFailure(statusOf(cause))) {
        try {
          const outcome = await backofficePropertyService.commandOutcome(
            detail.listingId, submittedIntent.id);
          if (!ownsOperation(owner)) return;
          publishIntent.current = null;
          setNotice(`Published content version ${outcome.resultVersion}.`);
          emitPropertyCurrentChanged({ listingId: outcome.listingId, reason: 'PUBLISHED' });
          setConfirmPublish(false);
          onPublished();
          return;
        } catch {
          if (!ownsOperation(owner)) return;
        }
        setError('The publication outcome could not be confirmed. Do not submit a second publication; check this property and its activity before trying again.');
      } else {
        setError(getErrorMessage(cause,
          'The property was not published. Reload the current property before trying again.'));
      }
      setConfirmPublish(true);
    } finally {
      finishOperation(owner);
    }
  };

  const discard = async () => {
    if (!discardReason.trim()) return;
    const owner = beginOperation('Discard draft');
    if (!owner) return;
    try {
      await backofficePropertyService.discard(detail.listingId, revision.draftId, discardReason);
      if (!ownsOperation(owner)) return;
      setConfirmDiscard(false);
      onDiscarded();
    } catch (cause) {
      if (!ownsOperation(owner)) return;
      setError(isUncertainCommandFailure(statusOf(cause))
        ? 'The discard outcome could not be confirmed. Reload the current property before trying again.'
        : getErrorMessage(cause, 'The staff draft could not be discarded.'));
    } finally {
      finishOperation(owner);
    }
  };

  const coverCount = draft.media.filter(item => item.cover && item.mediaType === 'IMAGE').length;
  const dirtySections = dirtyEditorSections(draft, savedContent);
  const dirty = dirtySections.length > 0;
  const changes = useMemo(() => revision.changes, [revision.changes]);
  const sectionState = (section: BackofficeSectionKey) => {
    if (busy === section) return { label: 'Saving…', tone: 'saving' as const };
    const feedback = sectionFeedback[section];
    if (feedback?.tone === 'error' && isEditorSectionDirty(draft, savedContent, section)) {
      return { label: 'Not saved', tone: 'unsaved' as const };
    }
    if (isEditorSectionDirty(draft, savedContent, section)) {
      return { label: 'Unsaved changes', tone: 'unsaved' as const };
    }
    return { label: 'Saved', tone: 'saved' as const };
  };
  const scrollToEditorSection = (id: string) => {
    const target = document.getElementById(id);
    if (!target) return;
    setActiveSection(id);
    const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    target.scrollIntoView({ behavior: reduce ? 'auto' : 'smooth', block: 'start' });
    target.focus({ preventScroll: true });
  };
  const busyMessage = busy === 'Media upload'
    ? 'Uploading media…'
    : busy === 'Media recovery'
      ? 'Checking the media upload…'
      : busy === 'Publish'
        ? 'Publishing property changes…'
        : busy === 'Discard draft'
          ? 'Discarding the staff draft…'
          : busy
            ? `Saving ${sectionLabel(busy as BackofficeSectionKey).toLowerCase()}…`
            : notice;

  return <div className="backoffice-editor">
    <div className="backoffice-editor-status" role="region" aria-label="Property change status">
      <div className="backoffice-editor-status-meta">
        <span className={`backoffice-status backoffice-status--${detail.status.toLowerCase()}`}>
          {detail.status.replace(/_/g, ' ')}
        </span>
        <strong>Staff changes</strong>
        <span aria-live="polite">
          {busyMessage || (dirty
            ? `${dirtySections.length} section${dirtySections.length === 1 ? '' : 's'} with changes not saved`
            : 'All sections saved')}
        </span>
      </div>
      <button type="button" className={SECONDARY} onClick={() => scrollToEditorSection(REVIEW_SECTION_ID)}>
        <Send className="h-4 w-4" />Review and publish
      </button>
    </div>
    {error && <div role="alert" className="backoffice-alert backoffice-alert--error">
      <TriangleAlert aria-hidden="true" /><p>{error}</p>
    </div>}

    <div className="backoffice-editor-layout">
      <nav className="backoffice-editor-rail" aria-label="Editor sections">
        {EDITOR_SECTIONS.map((section, index) => {
          const state = sectionState(section.key);
          return <button key={section.key} type="button"
            aria-current={activeSection === section.id ? 'true' : undefined}
            aria-label={`${section.label}, ${state.label}`}
            onClick={() => scrollToEditorSection(section.id)}>
            <span className="backoffice-editor-rail-number">{String(index + 1).padStart(2, '0')}</span>
            <span>{section.label}</span>
            <span className={`backoffice-editor-rail-state backoffice-editor-rail-state--${state.tone}`}>
              {state.label}
            </span>
            <span className={`backoffice-editor-rail-dot backoffice-editor-rail-dot--${state.tone}`} aria-hidden="true" />
          </button>;
        })}
        <button type="button" aria-current={activeSection === REVIEW_SECTION_ID ? 'true' : undefined}
          onClick={() => scrollToEditorSection(REVIEW_SECTION_ID)}>
          <span className="backoffice-editor-rail-number">06</span>
          <span>Review and publish</span>
        </button>
      </nav>

      <div className="backoffice-editor-sections">
    <Section id={EDITOR_SECTIONS[0].id} eyebrow={EDITOR_SECTIONS[0].eyebrow}
      title={EDITOR_SECTIONS[0].title} state={sectionState('basics').label}
      stateTone={sectionState('basics').tone}>
      <div className="backoffice-form-grid">
        <Field label="Property title" htmlFor="bo-title" hint="Leave empty to use the generated BHK, home type and locality title.">
          <input id="bo-title" className={FIELD} value={draft.title ?? ''} maxLength={255}
            onChange={event => update('title', event.target.value)} />
          <span className="text-xs font-normal text-[#7d867d]">
            Current provenance: {draft.titleProvenance === 'EXPLICIT'
              ? 'explicit staff title'
              : draft.titleProvenance === 'GENERATED'
                ? 'generated title'
                : 'legacy provenance unavailable'}
          </span>
        </Field>
        <Field label="Home type" htmlFor="bo-type">
          <select id="bo-type" className={FIELD} value={draft.propertyType}
            onChange={event => update('propertyType', event.target.value as StaffSnapshot['propertyType'])}>
            {['FLAT', 'HOUSE', 'STUDIO', 'PENTHOUSE', 'SERVICED_APARTMENT'].map(value =>
              <option key={value} value={value}>{value.replace(/_/g, ' ').toLowerCase()}</option>)}
          </select>
        </Field>
        <Field label="Exact configuration" htmlFor="bo-bhk">
          <input id="bo-bhk" className={FIELD} value={draft.bhkCount} maxLength={4}
            onChange={event => update('bhkCount', event.target.value.toUpperCase())} />
        </Field>
        <Field label="Description" htmlFor="bo-description">
          <textarea id="bo-description" className={`${FIELD} min-h-36 resize-y`} value={draft.description ?? ''}
            maxLength={4000} onChange={event => update('description', event.target.value || null)} />
        </Field>
      </div>
      <div className="backoffice-editor-section-foot">
        <span role="status" className="backoffice-editor-save-message">
          {sectionFeedback.basics?.message}
        </span>
        <button className={PRIMARY} disabled={Boolean(busy) || !isEditorSectionDirty(draft, savedContent, 'basics')}
          onClick={saveBasics}>
          {busy === 'basics' ? <LoaderCircle className="h-4 w-4 animate-spin motion-reduce:animate-none" /> : <Save className="h-4 w-4" />}
          Save basics
        </button>
      </div>
    </Section>

    <Section id={EDITOR_SECTIONS[1].id} eyebrow={EDITOR_SECTIONS[1].eyebrow}
      title={EDITOR_SECTIONS[1].title} state={sectionState('commercial').label}
      stateTone={sectionState('commercial').tone}>
      <div className="backoffice-form-grid">
        <Field label="Monthly rent (₹)" htmlFor="bo-rent">
          <input id="bo-rent" type="number" min="0.01" step="0.01" className={FIELD} value={draft.monthlyRent ?? ''}
            onChange={event => update('monthlyRent', Number(event.target.value))} />
        </Field>
        <Field label="Security deposit (₹)" htmlFor="bo-deposit" hint="Zero is a real value; blank is not valid here.">
          <input id="bo-deposit" type="number" min="0" step="0.01" className={FIELD} value={draft.securityDeposit ?? ''}
            onChange={event => update('securityDeposit', Number(event.target.value))} />
        </Field>
        <Field label="Available from" htmlFor="bo-available">
          <input id="bo-available" type="date" className={FIELD} value={draft.availableFrom ?? ''}
            onChange={event => update('availableFrom', event.target.value)} />
        </Field>
        <Field label="Maintenance (₹, optional)" htmlFor="bo-maintenance">
          <input id="bo-maintenance" type="number" min="0" step="0.01" className={FIELD}
            value={draft.maintenanceCharge ?? ''}
            onChange={event => update('maintenanceCharge', nullableNumber(event.target.value))} />
        </Field>
        <Field label="Brokerage amount (₹, optional)" htmlFor="bo-brokerage">
          <input id="bo-brokerage" type="number" min="0" step="0.01" className={FIELD}
            value={draft.brokerageAmount ?? ''}
            onChange={event => update('brokerageAmount', nullableNumber(event.target.value))} />
        </Field>
        <Field label="Brokerage days (optional)" htmlFor="bo-brokerage-days">
          <input id="bo-brokerage-days" type="number" min="0" step="1" className={FIELD}
            value={draft.brokerageDays ?? ''}
            onChange={event => update('brokerageDays', nullableInteger(event.target.value))} />
        </Field>
        <Field label="Deposit months (optional)" htmlFor="bo-deposit-months">
          <input id="bo-deposit-months" type="number" min="0" step="1" className={FIELD}
            value={draft.securityDepositMonths ?? ''}
            onChange={event => update('securityDepositMonths', nullableInteger(event.target.value))} />
        </Field>
      </div>
      <div className="backoffice-editor-section-foot">
        <span role="status" className="backoffice-editor-save-message">
          {sectionFeedback.commercial?.message}
        </span>
        <button className={PRIMARY} disabled={Boolean(busy) || !isEditorSectionDirty(draft, savedContent, 'commercial')}
          onClick={saveCommercial}>
          {busy === 'commercial' ? <LoaderCircle className="h-4 w-4 animate-spin" /> : <Save className="h-4 w-4" />}
          Save commercial details
        </button>
      </div>
    </Section>

    <Section id={EDITOR_SECTIONS[2].id} eyebrow={EDITOR_SECTIONS[2].eyebrow}
      title={EDITOR_SECTIONS[2].title} state={sectionState('details').label}
      stateTone={sectionState('details').tone}>
      <div className="backoffice-form-grid">
        <Field label="Total area (sq ft)" htmlFor="bo-area">
          <input id="bo-area" type="number" min="0.01" step="0.01" className={FIELD}
            value={draft.totalAreaSqFt ?? ''}
            onChange={event => update('totalAreaSqFt', nullableNumber(event.target.value))} />
        </Field>
        <Field label="Floor" htmlFor="bo-floor" hint="Ground floor is 0.">
          <input id="bo-floor" type="number" min="0" step="1" className={FIELD} value={draft.floorNumber ?? ''}
            onChange={event => update('floorNumber', nullableInteger(event.target.value))} />
        </Field>
        <Field label="Total floors" htmlFor="bo-total-floors">
          <input id="bo-total-floors" type="number" min="1" step="1" className={FIELD}
            value={draft.totalFloors ?? ''}
            onChange={event => update('totalFloors', nullableInteger(event.target.value))} />
        </Field>
        <Field label="Bathrooms" htmlFor="bo-bathrooms">
          <input id="bo-bathrooms" type="number" min="0" step="1" className={FIELD}
            value={draft.bathroomCount ?? ''}
            onChange={event => update('bathroomCount', nullableInteger(event.target.value))} />
        </Field>
        <Field label="Furnishing" htmlFor="bo-furnishing">
          <select id="bo-furnishing" className={FIELD} value={draft.furnishingStatus ?? ''}
            onChange={event => update('furnishingStatus', event.target.value || null)}>
            <option value="">Not supplied</option>
            <option value="UNFURNISHED">Unfurnished</option>
            <option value="SEMI_FURNISHED">Semi furnished</option>
            <option value="FULLY_FURNISHED">Fully furnished</option>
          </select>
        </Field>
        <Field label="Vastu facing" htmlFor="bo-facing">
          <input id="bo-facing" className={FIELD} value={draft.vastuFacing ?? ''} maxLength={255}
            onChange={event => update('vastuFacing', event.target.value || null)} />
        </Field>
        <Field label="Bachelor preference" htmlFor="bo-bachelor">
          <select id="bo-bachelor" className={FIELD}
            value={draft.bachelorAllowed === null ? '' : String(draft.bachelorAllowed)}
            onChange={event => update('bachelorAllowed', event.target.value === '' ? null : event.target.value === 'true')}>
            <option value="">Not supplied</option>
            <option value="true">Allowed</option>
            <option value="false">Not allowed</option>
          </select>
        </Field>
        <Field label="Preferred tenant" htmlFor="bo-preferred-tenant">
          <input id="bo-preferred-tenant" className={FIELD} value={draft.preferredTenant ?? ''} maxLength={255}
            onChange={event => update('preferredTenant', event.target.value || null)} />
        </Field>
        <Field label="Amenities" htmlFor="bo-amenities">
          <textarea id="bo-amenities" className={`${FIELD} min-h-32 resize-y`} value={draft.amenities ?? ''}
            maxLength={2000} onChange={event => update('amenities', event.target.value || null)} />
        </Field>
      </div>
      <div className="backoffice-editor-section-foot">
        <span role="status" className="backoffice-editor-save-message">
          {sectionFeedback.details?.message}
        </span>
        <button className={PRIMARY} disabled={Boolean(busy) || !isEditorSectionDirty(draft, savedContent, 'details')}
          onClick={saveDetails}>
          {busy === 'details' ? <LoaderCircle className="h-4 w-4 animate-spin" /> : <Save className="h-4 w-4" />}
          Save home details
        </button>
      </div>
    </Section>

    <Section id={EDITOR_SECTIONS[3].id} eyebrow={EDITOR_SECTIONS[3].eyebrow}
      title={EDITOR_SECTIONS[3].title} state={sectionState('address').label}
      stateTone={sectionState('address').tone}>
      <div className="backoffice-form-grid">
        <Field label="Address" htmlFor="bo-address">
          <textarea id="bo-address" className={`${FIELD} min-h-24 resize-y`} value={draft.address}
            maxLength={500} onChange={event => update('address', event.target.value)} />
        </Field>
        <Field label="Landmark" htmlFor="bo-landmark">
          <input id="bo-landmark" className={FIELD} value={draft.landmark ?? ''} maxLength={200}
            onChange={event => update('landmark', event.target.value || null)} />
        </Field>
        <Field label="Colony" htmlFor="bo-colony">
          <input id="bo-colony" className={FIELD} value={draft.colony ?? ''} maxLength={255}
            onChange={event => update('colony', event.target.value || null)} />
        </Field>
        <Field label="Pincode" htmlFor="bo-pincode">
          <input id="bo-pincode" className={FIELD} inputMode="numeric" pattern="[1-9][0-9]{5}"
            value={draft.pincode ?? ''} maxLength={6}
            onChange={event => update('pincode', event.target.value.replace(/\D/g, '') || null)} />
        </Field>
      </div>
      <p className="backoffice-footnote">City and canonical locality stay server-controlled and cannot be changed here.</p>
      <div className="backoffice-editor-section-foot">
        <span role="status" className="backoffice-editor-save-message">
          {sectionFeedback.address?.message}
        </span>
        <button className={PRIMARY} disabled={Boolean(busy) || !isEditorSectionDirty(draft, savedContent, 'address')}
          onClick={saveAddress}>
          {busy === 'address' ? <LoaderCircle className="h-4 w-4 animate-spin" /> : <Save className="h-4 w-4" />}
          Save presentation address
        </button>
      </div>
    </Section>

    <Section id={EDITOR_SECTIONS[4].id} eyebrow={EDITOR_SECTIONS[4].eyebrow}
      title={EDITOR_SECTIONS[4].title} state={sectionState('media').label}
      stateTone={sectionState('media').tone}>
      <div className="backoffice-media-note">
        <ImagePlus aria-hidden="true" />
        <div><strong>{coverCount === 1 ? 'Cover selected' : 'Choose exactly one cover photo'}</strong>
          <p>Upload, order and describe media. Existing resources remain in history when removed from the current selection.</p></div>
      </div>
      <label className={`${SECONDARY} w-fit cursor-pointer`} htmlFor="bo-media-upload">
        {busy === 'Media upload' ? <LoaderCircle className="h-4 w-4 animate-spin" /> : <ImagePlus className="h-4 w-4" />}
        Add photo or video
        <input ref={fileInput} id="bo-media-upload" type="file" className="sr-only"
          accept="image/jpeg,image/png,image/webp,image/heic,image/heif,video/mp4,video/quicktime"
          onChange={event => { const file = event.target.files?.[0]; if (file) void upload(file); }} />
      </label>
      <div className="backoffice-media-list">
        {failedUpload && <article className="backoffice-media-row backoffice-media-row--failed">
          <div className="backoffice-media-thumb"><TriangleAlert aria-hidden="true" /></div>
          <div className="min-w-0 flex-1">
            <strong>Upload needs attention</strong>
            <p className="backoffice-footnote">{failedUpload.file.name}: {failedUpload.message}</p>
          </div>
          <button type="button" className={SECONDARY} disabled={Boolean(busy)}
            onClick={() => { void upload(failedUpload.file, failedUpload.mediaId); }}>
            Retry upload
          </button>
        </article>}
        {draft.media.map((item, index) => <article key={item.mediaId} className="backoffice-media-row">
          <div className="backoffice-media-thumb">
            {item.url && item.mediaType === 'IMAGE'
              ? <img src={item.url} alt="" loading="lazy" />
              : <span>{item.mediaType === 'IMAGE' ? 'No photo' : 'Walkthrough'}</span>}
          </div>
          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-center gap-2">
              <span className="backoffice-chip">Position {index + 1}</span>
              {item.cover && <span className="backoffice-chip backoffice-chip--gold"><Star className="h-3 w-3" />Cover</span>}
              {item.uploadStatus !== 'UPLOADED' && <span className="backoffice-chip backoffice-chip--warn">{item.uploadStatus}</span>}
            </div>
            <div className="mt-2 grid gap-2 sm:grid-cols-[minmax(0,180px)_1fr]">
              <select aria-label={`Room label for media ${index + 1}`} className={FIELD}
                value={item.roomTag} onChange={event => setMedia(index, { roomTag: event.target.value as RoomTag })}>
                {TAGS.map(tag => <option key={tag} value={tag}>{tag.replace(/_/g, ' ').toLowerCase()}</option>)}
              </select>
              <input aria-label={`Caption for media ${index + 1}`} className={FIELD} maxLength={255}
                placeholder="Optional caption" value={item.caption ?? ''}
                onChange={event => setMedia(index, { caption: event.target.value || null })} />
            </div>
          </div>
          <div className="backoffice-media-controls">
            {item.uploadStatus !== 'UPLOADED' && <button type="button" className={SECONDARY}
              disabled={Boolean(busy)} onClick={() => { void recoverUpload(item.mediaId); }}>
              Recover upload
            </button>}
            <button type="button" className={SECONDARY} disabled={item.mediaType !== 'IMAGE' || item.cover}
              onClick={() => makeCover(index)}>Use as cover</button>
            <button type="button" className={SECONDARY} aria-label="Move media up" disabled={index === 0}
              onClick={() => move(index, -1)}><ArrowUp className="h-4 w-4" /></button>
            <button type="button" className={SECONDARY} aria-label="Move media down" disabled={index === draft.media.length - 1}
              onClick={() => move(index, 1)}><ArrowDown className="h-4 w-4" /></button>
            <button type="button" className={DANGER} aria-label="Remove media from this publication"
              onClick={() => setDraft(current => ({ ...current, media: current.media.filter((_, i) => i !== index) }))}>
              <Trash2 className="h-4 w-4" />
            </button>
          </div>
        </article>)}
      </div>
      <div className="backoffice-editor-section-foot">
        <span role="status" className="backoffice-editor-save-message">
          {sectionFeedback.media?.message}
        </span>
        <button className={PRIMARY}
          disabled={Boolean(busy) || coverCount !== 1 || !isEditorSectionDirty(draft, savedContent, 'media')}
          onClick={saveMedia}>
          {busy === 'media' ? <LoaderCircle className="h-4 w-4 animate-spin" /> : <Save className="h-4 w-4" />}
          Save media presentation
        </button>
      </div>
    </Section>

    <Section id={REVIEW_SECTION_ID} eyebrow="06 · Review and publish"
      title="Review saved changes before publishing">
      <div className="backoffice-review-summary">
        <span><strong>{changes.length}</strong> field groups differ</span>
        <span><strong>{revision.preview.blockers.length}</strong> checks need attention</span>
        <span><strong>{dirtySections.length}</strong> unsaved section{dirtySections.length === 1 ? '' : 's'}</span>
      </div>
      {dirty && <div className="backoffice-alert backoffice-alert--warn" role="status"><TriangleAlert />
        <div>
          <strong>Some changes are not part of this review yet</strong>
          <p>Save these sections before publishing if you want those changes included:</p>
          <ul>{dirtySections.map(section => <li key={section}>{sectionLabel(section)}</li>)}</ul>
        </div>
      </div>}
      {revision.preview.blockers.length > 0 && <div className="backoffice-alert backoffice-alert--warn">
        <TriangleAlert aria-hidden="true" />
        <div><strong>This property is not ready to publish</strong><ul>{revision.preview.blockers.map(item => <li key={item}>{item}</li>)}</ul></div>
      </div>}
      {revision.preview.warnings.map(item => <p key={item} className="backoffice-footnote">{item}</p>)}
      <div className="backoffice-change-list">
        {changes.length === 0 && <p className="text-sm text-[#70796f]">No saved content differs from the live property.</p>}
        {changes.map(change => <article key={change.field} className="backoffice-change">
          <p>{change.label}</p>
          <div><span>{change.before}</span><span aria-hidden="true">→</span><strong>{change.after}</strong></div>
        </article>)}
      </div>
      {!confirmPublish ? <div className="backoffice-actions">
        <button className={PRIMARY} disabled={Boolean(busy) || dirty || !revision.preview.ready}
          onClick={() => setConfirmPublish(true)}>
          <Send className="h-4 w-4" />Publish updates
        </button>
      </div> : <div className="backoffice-confirm" role="group" aria-labelledby="bo-publish-confirm">
        <h3 id="bo-publish-confirm">Publish these reviewed changes?</h3>
        <p>The current {detail.status.toLowerCase()} visibility stays unchanged. A paused property will not resume.</p>
        <Field label="Publication reason" htmlFor="bo-publish-reason">
          <input id="bo-publish-reason" autoFocus className={FIELD} value={publishReason} maxLength={500}
            onChange={event => setPublishReason(event.target.value)} placeholder="e.g. Corrected monthly rent" />
        </Field>
        <div className="backoffice-actions">
          <button className={PRIMARY}
            disabled={Boolean(busy) || dirty || !revision.preview.ready || !publishReason.trim()}
            onClick={publish}>
            {busy === 'Publish' ? <LoaderCircle className="h-4 w-4 animate-spin" /> : <CheckCircle2 className="h-4 w-4" />}
            Confirm publication
          </button>
          <button className={SECONDARY} disabled={Boolean(busy)} onClick={() => setConfirmPublish(false)}>Cancel</button>
        </div>
      </div>}
    </Section>
    <section className="backoffice-card backoffice-editor-section">
      <p className="backoffice-eyebrow">DRAFT LIFECYCLE</p>
      <h2 className="backoffice-section-title">Discard this unfinished staff draft</h2>
      <p className="backoffice-footnote">The live property remains unchanged. The discarded draft and its activity remain in history.</p>
      {!confirmDiscard ? <div className="backoffice-actions">
        <button className={DANGER} disabled={Boolean(busy)} onClick={() => setConfirmDiscard(true)}>
          <Trash2 className="h-4 w-4" />Discard staff draft
        </button>
      </div> : <div className="backoffice-confirm">
        <label htmlFor="bo-discard-reason" className="grid gap-1.5 text-sm font-semibold text-[#4d594f]">
          <span>Discard reason (required)</span>
          <input id="bo-discard-reason" autoFocus className={FIELD} maxLength={500}
            value={discardReason} onChange={event => setDiscardReason(event.target.value)} />
        </label>
        <div className="backoffice-actions">
          <button className={DANGER} disabled={Boolean(busy) || !discardReason.trim()} onClick={discard}>
            {busy === 'Discard draft' ? <LoaderCircle className="h-4 w-4 animate-spin" /> : <Trash2 className="h-4 w-4" />}
            Confirm discard
          </button>
          <button className={SECONDARY} disabled={Boolean(busy)}
            onClick={() => { setConfirmDiscard(false); setDiscardReason(''); }}>Cancel</button>
        </div>
      </div>}
    </section>
      </div>
    </div>
  </div>;
}

