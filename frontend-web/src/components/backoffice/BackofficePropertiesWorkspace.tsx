import React, { useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import {
  ArrowLeft, ArrowRight, Building2, Clock3, ExternalLink, FilePenLine,
  History, LoaderCircle, Pause, Search, ShieldCheck, TriangleAlert
} from 'lucide-react';
import { getErrorMessage } from '../../services/apiError';
import { backofficePropertyService, type Activity, type PropertyDetail,
  type PropertySummary, type StaffRevision } from '../../services/backofficePropertyService';
import type { PropertyWorkspaceCapability } from '../../hooks/usePropertyWorkspaceCapability';
import { BackofficePropertyEditor } from './BackofficePropertyEditor';
import './backoffice.css';
import { emitPropertyCurrentChanged } from '../../utils/propertyCurrentEvents';
import {
  operationIdForIntent,
  operationIntentKey,
  type OperationIntent
} from '../../utils/operationIntent.ts';
import { captureOperationsSession, subscribeOperationsSession } from '../../utils/operationsSession.ts';
import {
  isProtectedDenialStatus,
  isUncertainCommandFailure,
  statusOf
} from '../../utils/operationOwnership.ts';
import {
  describeMediaChange,
  formatActivityEvidenceValue,
  type MediaResourceDifference,
  summarizeMediaChange
} from '../../utils/backofficeActivityPresentation.ts';

const FIELD = 'min-h-11 w-full rounded-lg border border-[#dddcd5] bg-white px-3 text-base text-[#29322b] outline-none transition focus:border-[#718b73] focus:ring-2 focus:ring-[#e6ece4]';
const PRIMARY = 'inline-flex min-h-11 items-center justify-center gap-2 rounded-lg bg-[#355c49] px-4 text-sm font-semibold text-white hover:bg-[#284a38] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#8aa08b] disabled:cursor-not-allowed disabled:opacity-50';
const SECONDARY = 'inline-flex min-h-11 items-center justify-center gap-2 rounded-lg border border-[#dddcd5] bg-white px-4 text-sm font-semibold text-[#3f5d47] hover:bg-[#f5f6f2] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#8aa08b] disabled:cursor-not-allowed disabled:opacity-50';
const MONEY = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 });
const DATE = new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium', timeStyle: 'short' });

const formatDate = (value: string | null) => value ? DATE.format(new Date(value)) : 'Not available';
const detailValue = (value: unknown): string => {
  if (value === null || value === undefined || value === '') return 'Not set';
  if (Array.isArray(value)) return `${value.length} item(s)`;
  if (typeof value === 'object') return 'Updated';
  return String(value);
};

export function BackofficePropertiesWorkspace({ capability }: {
  capability: PropertyWorkspaceCapability;
}) {
  const location = useLocation();
  const navigate = useNavigate();
  const listingMatch = /^\/backoffice\/properties\/(\d+)$/.exec(location.pathname);
  const listingId = listingMatch ? Number(listingMatch[1]) : null;
  const [query, setQuery] = useState('');
  const [items, setItems] = useState<PropertySummary[]>([]);
  const [listPage, setListPage] = useState(0);
  const [hasMore, setHasMore] = useState(false);
  const [listState, setListState] = useState<'loading' | 'ready' | 'error'>('loading');
  const [listError, setListError] = useState('');
  const [detail, setDetail] = useState<PropertyDetail | null>(null);
  const [revision, setRevision] = useState<StaffRevision | null>(null);
  const [detailState, setDetailState] = useState<'loading' | 'ready' | 'error'>('loading');
  const [detailError, setDetailError] = useState('');
  const [tab, setTab] = useState<'overview' | 'edit' | 'history'>('overview');
  const [activity, setActivity] = useState<Activity[]>([]);
  const [activityState, setActivityState] = useState<'idle' | 'loading' | 'ready' | 'error'>('idle');
  const [activityHasMore, setActivityHasMore] = useState(false);
  const [activityCursor, setActivityCursor] = useState<string | null>(null);
  const [activityLoadingMore, setActivityLoadingMore] = useState(false);
  const [activityError, setActivityError] = useState('');
  const [activityReload, setActivityReload] = useState(0);
  const [notice, setNotice] = useState('');
  const [busyAction, setBusyAction] = useState('');
  const [lifecycleAction, setLifecycleAction] = useState<'pause' | 'archive' | null>(null);
  const [lifecycleReason, setLifecycleReason] = useState('');
  const [supersedeReason, setSupersedeReason] = useState('');
  const [confirmSupersede, setConfirmSupersede] = useState(false);
  const [sessionEpoch, setSessionEpoch] = useState(0);
  const requestRevision = useRef(0);
  const lifecycleIntent = useRef<OperationIntent | null>(null);
  const viewEpoch = useRef(0);
  const activityEpoch = useRef(0);
  const activityInFlight = useRef(false);
  const activityHeading = useRef<HTMLHeadingElement>(null);
  const busyActionRef = useRef(false);

  const loadList = async (page: number, reset = false) => {
    if (!capability.canEnter) return;
    const revisionId = ++requestRevision.current;
    setListState('loading');
    setListError('');
    try {
      const result = await backofficePropertyService.list(page, query);
      if (revisionId !== requestRevision.current) return;
      setItems(current => reset || page === 0 ? result.items : [...current, ...result.items]);
      setListPage(page);
      setHasMore(result.hasMore);
      setListState('ready');
    } catch (cause) {
      if (revisionId !== requestRevision.current) return;
      setListError(getErrorMessage(cause, 'Properties could not be loaded.'));
      setListState('error');
    }
  };

  const loadDetail = async () => {
    if (listingId === null || !capability.canEnter) return;
    const operationEpoch = viewEpoch.current;
    const requestedListingId = listingId;
    setDetailState('loading');
    setDetailError('');
    try {
      const current = await backofficePropertyService.detail(requestedListingId);
      if (operationEpoch !== viewEpoch.current) return;
      setDetail(current);
      if (current.openRevisionDraftId && current.openRevisionStatus === 'DRAFT' && capability.canEdit) {
        try {
          const nextRevision = await backofficePropertyService.revision(
            requestedListingId, current.openRevisionDraftId);
          if (operationEpoch !== viewEpoch.current) return;
          setRevision(nextRevision);
          setTab('edit');
        } catch {
          if (operationEpoch !== viewEpoch.current) return;
          setRevision(null);
        }
      } else {
        setRevision(null);
      }
      setDetailState('ready');
    } catch (cause) {
      if (operationEpoch !== viewEpoch.current) return;
      setDetailError(getErrorMessage(cause, 'This property could not be opened.'));
      setDetailState('error');
    }
  };

  useEffect(() => {
    return subscribeOperationsSession(() => {
      viewEpoch.current += 1;
      requestRevision.current += 1;
      setDetail(null);
      setRevision(null);
      setActivity([]);
      setActivityCursor(null);
      setActivityHasMore(false);
      setSessionEpoch(value => value + 1);
    });
  }, []);

  useEffect(() => {
    viewEpoch.current += 1;
    busyActionRef.current = false;
    lifecycleIntent.current = null;
    setBusyAction('');
    setDetailError('');
    setNotice('');
    setActivityLoadingMore(false);
    setActivityError('');
    if (!listingId) {
      setDetail(null);
      setRevision(null);
      void loadList(0, true);
    } else {
      setDetail(null);
      setRevision(null);
      void loadDetail();
    }
  }, [listingId, capability.canEnter, sessionEpoch]);

  useEffect(() => {
    if (tab !== 'history' || listingId === null || !capability.canAudit) {
      setActivity([]);
      setActivityCursor(null);
      setActivityHasMore(false);
      setActivityLoadingMore(false);
      setActivityError('');
      setActivityState('idle');
      return;
    }
    const requestEpoch = ++activityEpoch.current;
    activityInFlight.current = true;
    setActivity([]);
    setActivityCursor(null);
    setActivityHasMore(false);
      setActivityLoadingMore(false);
    setActivityError('');
    setActivityState('loading');
    backofficePropertyService.activity(listingId).then(result => {
      if (requestEpoch !== activityEpoch.current) return;
      setActivity(result.items);
      setActivityCursor(result.nextCursor);
      setActivityHasMore(result.hasMore && Boolean(result.nextCursor));
      setActivityState('ready');
    }).catch(cause => {
      if (requestEpoch !== activityEpoch.current) return;
      setActivityError(getErrorMessage(cause, 'Activity history could not be loaded.'));
      setActivityState('error');
    }).finally(() => {
      if (requestEpoch === activityEpoch.current) activityInFlight.current = false;
    });
    return () => { activityEpoch.current += 1; activityInFlight.current = false; };
  }, [tab, listingId, capability.canAudit, activityReload, sessionEpoch]);

  const loadOlderActivity = async () => {
    if (listingId === null || !capability.canAudit || !activityHasMore
        || !activityCursor || activityInFlight.current) return;
    const requestEpoch = ++activityEpoch.current;
    activityInFlight.current = true;
    setActivityLoadingMore(true);
    setActivityError('');
    try {
      const result = await backofficePropertyService.activity(listingId, activityCursor);
      if (requestEpoch !== activityEpoch.current) return;
      setActivity(current => {
        const seen = new Set(current.map(item => item.eventId));
        return [...current, ...result.items.filter(item => !seen.has(item.eventId))];
      });
      setActivityCursor(result.nextCursor);
      setActivityHasMore(result.hasMore && Boolean(result.nextCursor));
      setActivityState('ready');
      if (!result.hasMore || !result.nextCursor) {
        window.requestAnimationFrame(() => {
          if (requestEpoch === activityEpoch.current) activityHeading.current?.focus();
        });
      }
    } catch (cause) {
      if (requestEpoch !== activityEpoch.current) return;
      const status = typeof cause === 'object' && cause !== null && 'status' in cause
        ? Number((cause as { status?: unknown }).status) : 0;
      if (isProtectedDenialStatus(status)) {
        setActivity([]);
        setActivityCursor(null);
        setActivityHasMore(false);
        setActivityState('error');
        setActivityError('Activity history access is no longer available.');
      } else {
        setActivityError(getErrorMessage(cause, 'Older activity could not be loaded.'));
      }
    } finally {
      if (requestEpoch === activityEpoch.current) {
        activityInFlight.current = false;
        setActivityLoadingMore(false);
      }
    }
  };

  const startRevision = async () => {
    if (!detail || busyActionRef.current) return;
    const operationEpoch = viewEpoch.current;
    busyActionRef.current = true;
    setBusyAction('revision');
    setDetailError('');
    try {
      const created = await backofficePropertyService.startRevision(detail.listingId);
      if (operationEpoch !== viewEpoch.current) return;
      setRevision(created);
      setTab('edit');
      setNotice('Staff revision started. Save each section you change before publishing.');
    } catch (cause) {
      if (operationEpoch !== viewEpoch.current) return;
      setDetailError(getErrorMessage(cause, 'A staff revision could not be started.'));
    } finally {
      if (operationEpoch === viewEpoch.current) {
        busyActionRef.current = false;
        setBusyAction('');
      }
    }
  };

  const corrective = async (version: number) => {
    if (!detail || busyActionRef.current) return;
    const operationEpoch = viewEpoch.current;
    busyActionRef.current = true;
    setBusyAction('corrective');
    setDetailError('');
    try {
      const created = await backofficePropertyService.correctiveRevision(detail.listingId, version);
      if (operationEpoch !== viewEpoch.current) return;
      setRevision(created);
      setTab('edit');
      setNotice(`Corrective revision started from publication version ${version}.`);
    } catch (cause) {
      if (operationEpoch === viewEpoch.current)
      setDetailError(getErrorMessage(cause, 'The earlier version could not be opened for correction.'));
    } finally {
      if (operationEpoch === viewEpoch.current) {
        busyActionRef.current = false;
        setBusyAction('');
      }
    }
  };

  const supersedeStaffDraft = async () => {
    if (!detail?.openRevisionDraftId || detail.openRevisionVersion === null
        || busyActionRef.current || !supersedeReason.trim()) return;
    const operationEpoch = viewEpoch.current;
    busyActionRef.current = true;
    setBusyAction('supersede');
    setDetailError('');
    try {
      const created = await backofficePropertyService.supersede(
        detail.listingId, detail.openRevisionDraftId, detail.openRevisionVersion, supersedeReason);
      if (operationEpoch !== viewEpoch.current) return;
      setRevision(created);
      setConfirmSupersede(false);
      setSupersedeReason('');
      setTab('edit');
      setNotice('The earlier staff draft was retained as superseded history and a new revision was started.');
    } catch (cause) {
      if (operationEpoch !== viewEpoch.current) return;
      setDetailError(getErrorMessage(cause, 'The staff draft could not be superseded.'));
    } finally {
      if (operationEpoch === viewEpoch.current) {
        busyActionRef.current = false;
        setBusyAction('');
      }
    }
  };

  const lifecycle = async (action: 'pause' | 'archive') => {
    if (!detail || busyActionRef.current || !capability.canLifecycle || !lifecycleReason.trim()) return;
    const verb = action === 'pause' ? 'pause' : 'archive';
    const operationEpoch = viewEpoch.current;
    const reason = lifecycleReason.trim();
    busyActionRef.current = true;
    setBusyAction(verb);
    setDetailError('');
    try {
      const intentKey = operationIntentKey([
        captureOperationsSession()?.identity,
        detail.listingId,
        detail.version,
        action,
        reason
      ]);
      lifecycleIntent.current = operationIdForIntent(lifecycleIntent.current, intentKey);
      const submittedIntent = lifecycleIntent.current;
      await backofficePropertyService.lifecycle(detail.listingId, detail.version, action,
        reason, submittedIntent.id);
      if (operationEpoch !== viewEpoch.current) return;
      lifecycleIntent.current = null;
      emitPropertyCurrentChanged({
        listingId: detail.listingId,
        reason: action === 'pause' ? 'PAUSED' : 'ARCHIVED'
      });
      setNotice(`Property ${action === 'pause' ? 'paused' : 'archived'} successfully.`);
      setLifecycleAction(null);
      setLifecycleReason('');
      await loadDetail();
      if (operationEpoch !== viewEpoch.current) return;
      setTab('history');
    } catch (cause) {
      if (operationEpoch !== viewEpoch.current) return;
      if (isUncertainCommandFailure(statusOf(cause)) && lifecycleIntent.current) {
        try {
          const outcome = await backofficePropertyService.commandOutcome(
            detail.listingId, lifecycleIntent.current.id);
          if (operationEpoch !== viewEpoch.current) return;
          lifecycleIntent.current = null;
          emitPropertyCurrentChanged({
            listingId: outcome.listingId,
            reason: outcome.workflowStatus === 'PAUSED' ? 'PAUSED' : 'ARCHIVED'
          });
          setNotice(`Property lifecycle confirmed as ${outcome.workflowStatus.toLowerCase().replace(/_/g, ' ')}.`);
          setLifecycleAction(null);
          setLifecycleReason('');
          await loadDetail();
          if (operationEpoch !== viewEpoch.current) return;
          setTab('history');
          return;
        } catch {
          if (operationEpoch !== viewEpoch.current) return;
        }
      }
      setDetailError(isUncertainCommandFailure(statusOf(cause))
        ? 'The lifecycle outcome could not be confirmed. Do not submit the same action again; reload the property before deciding.'
        : getErrorMessage(cause, `The property could not be ${verb === 'pause' ? 'paused' : 'archived'}.`));
    } finally {
      if (operationEpoch === viewEpoch.current) {
        busyActionRef.current = false;
        setBusyAction('');
      }
    }
  };

  if (!capability.canEnter) {
    if (capability.state === 'checking') return <BackofficeStatus title="Checking property access"
      message="Confirming your current City-scoped property duties." busy />;
    if (capability.state === 'unavailable') return <BackofficeStatus title="Property access is temporarily unavailable"
      message="No property data is shown until current authority can be verified." action="Retry" onAction={capability.reload} />;
    return <BackofficeStatus title="Property workspace unavailable"
      message="Your account does not have a current City-scoped property duty." />;
  }

  if (!listingId) {
    return <main className="backoffice-shell" aria-labelledby="backoffice-inventory-title">
      <header className="backoffice-hero">
        <div>
          <p className="backoffice-eyebrow"><ShieldCheck />AUTHORIZED PROPERTY OPERATIONS</p>
          <h1 id="backoffice-inventory-title">Properties, with <em>complete context.</em></h1>
          <p>Edit approved facts, review exact changes and publish directly with accountable history.</p>
        </div>
        <div className="backoffice-duty-pills" aria-label="Current duties">
          {capability.canEdit && <span>Edit and publish</span>}
          {capability.canAudit && <span>Audit history</span>}
          {capability.canLifecycle && <span>Lifecycle</span>}
        </div>
      </header>
      <section className="backoffice-inventory">
        <div className="backoffice-inventory-head">
          <div><p className="backoffice-eyebrow">CITY-SCOPED INVENTORY</p><h2>Authorized properties</h2></div>
          <form className="backoffice-search" onSubmit={event => { event.preventDefault(); void loadList(0, true); }}>
            <label htmlFor="backoffice-search" className="sr-only">Search authorized properties</label>
            <Search aria-hidden="true" />
            <input id="backoffice-search" className={FIELD} value={query} maxLength={120}
              onChange={event => setQuery(event.target.value)} placeholder="Title, City or locality" />
            <button className={SECONDARY}>Search</button>
          </form>
        </div>
        {listState === 'loading' && items.length === 0 && <div className="backoffice-loading">
          <LoaderCircle className="h-5 w-5 animate-spin motion-reduce:animate-none" />Loading authorized properties…
        </div>}
        {listState === 'error' && <div className="backoffice-alert backoffice-alert--error">
          <TriangleAlert /><div><strong>Properties could not be loaded</strong><p>{listError}</p>
            <button className={SECONDARY} onClick={() => { void loadList(0, true); }}>Try again</button></div>
        </div>}
        {listState === 'ready' && items.length === 0 && <div className="backoffice-empty">
          <Building2 /><h2>No authorized properties found</h2>
          <p>{query ? 'Try a broader search.' : 'No property is currently inside your City duties.'}</p>
        </div>}
        {items.length > 0 && <div className="backoffice-property-grid">
          {items.map(item => <article key={item.listingId} className="backoffice-property-card">
            <div className="backoffice-property-cover">
              {item.coverUrl ? <img src={item.coverUrl} alt="" loading="lazy" /> : <Building2 aria-hidden="true" />}
              <span className={`backoffice-status backoffice-status--${item.status.toLowerCase()}`}>{item.status.replace(/_/g, ' ')}</span>
            </div>
            <div className="backoffice-property-body">
              <p>{[item.locality, item.city].filter(Boolean).join(', ')}</p>
              <h3>{item.title}</h3>
              <div className="backoffice-property-meta">
                <strong>{MONEY.format(item.monthlyRent)}<small> / month</small></strong>
                <span>v{item.version}</span>
              </div>
              {item.openRevisionDraftId && <p className="backoffice-revision-note">
                <FilePenLine />{item.openRevisionStatus === 'DRAFT' ? 'Staff draft in progress' : `Revision ${item.openRevisionStatus?.toLowerCase()}`}
              </p>}
              <button className={SECONDARY} onClick={() => navigate(`/backoffice/properties/${item.listingId}`)}>
                Open property <ArrowRight className="h-4 w-4" />
              </button>
            </div>
          </article>)}
        </div>}
        {hasMore && <button className={`${SECONDARY} mt-5`} disabled={listState === 'loading'}
          onClick={() => { void loadList(listPage + 1); }}>{listState === 'loading' ? 'Loading…' : 'Show more properties'}</button>}
      </section>
    </main>;
  }

  if (detailState === 'loading' && !detail) return <BackofficeStatus title="Opening property"
    message="Loading the current authoritative property and history context." busy />;
  if (detailState === 'error' || !detail) return <BackofficeStatus title="Property could not be opened"
    message={detailError || 'The property is not available in your authorized City.'}
    action="Back to properties" onAction={() => navigate('/backoffice/properties')} />;

  const editorGeneration = viewEpoch.current;
  const editorOwnsCompletion = (listing: number, draftId: string) =>
    editorGeneration === viewEpoch.current
    && detail.listingId === listing
    && revision?.draftId === draftId;

  return <main className="backoffice-shell" aria-labelledby="backoffice-property-title">
    <button className={SECONDARY} onClick={() => navigate('/backoffice/properties')}>
      <ArrowLeft className="h-4 w-4" />All properties
    </button>
    <header className="backoffice-detail-head">
      <div>
        <p className="backoffice-eyebrow">{[detail.locality, detail.city].join(' · ')}</p>
        <h1 id="backoffice-property-title">{detail.content.title}</h1>
        <div className="backoffice-detail-meta">
          <span className={`backoffice-status backoffice-status--${detail.status.toLowerCase()}`}>{detail.status.replace(/_/g, ' ')}</span>
          <span>Property v{detail.version}</span>
          <span>Content v{detail.publicationVersion}</span>
          <span>Last content publication {formatDate(detail.lastContentPublishedAt)}</span>
        </div>
      </div>
      <div className="backoffice-detail-actions">
        {capability.canEdit && !revision && !detail.openRevisionDraftId
          && ['PUBLISHED', 'PAUSED'].includes(detail.status)
          && <button className={PRIMARY} disabled={Boolean(busyAction)} onClick={startRevision}>
            {busyAction === 'revision' ? <LoaderCircle className="h-4 w-4 animate-spin" /> : <FilePenLine className="h-4 w-4" />}
            Start staff revision
          </button>}
        {detail.canSupersedeRevision && <button className={SECONDARY} disabled={Boolean(busyAction)}
          onClick={() => setConfirmSupersede(true)}>
          <FilePenLine className="h-4 w-4" />Supersede staff draft
        </button>}
        {capability.canLifecycle && detail.status === 'PUBLISHED'
          && <button className={SECONDARY} disabled={Boolean(busyAction)} onClick={() => setLifecycleAction('pause')}>
            <Pause className="h-4 w-4" />Pause
          </button>}
        {capability.canLifecycle && ['PUBLISHED', 'PAUSED'].includes(detail.status)
          && <button className="backoffice-danger" disabled={Boolean(busyAction)} onClick={() => setLifecycleAction('archive')}>
            Archive property
          </button>}
      </div>
    </header>
    {notice && <div role="status" className="backoffice-alert backoffice-alert--success"><p>{notice}</p></div>}
    {detailError && <div role="alert" className="backoffice-alert backoffice-alert--error"><TriangleAlert /><p>{detailError}</p></div>}
    {confirmSupersede && <section className="backoffice-confirm" aria-labelledby="backoffice-supersede-title">
      <h3 id="backoffice-supersede-title">Supersede the existing staff draft?</h3>
      <p>The earlier draft remains in immutable revision history. The new draft starts from the current published property and belongs to you.</p>
      <label htmlFor="backoffice-supersede-reason" className="grid gap-1.5 text-sm font-semibold text-[#4d594f]">
        <span>Supersession reason (required)</span>
        <input id="backoffice-supersede-reason" autoFocus className={FIELD} maxLength={500}
          value={supersedeReason} onChange={event => setSupersedeReason(event.target.value)} />
      </label>
      <div className="backoffice-actions">
        <button className={PRIMARY} disabled={Boolean(busyAction) || !supersedeReason.trim()}
          onClick={() => { void supersedeStaffDraft(); }}>
          {busyAction === 'supersede' ? <LoaderCircle className="h-4 w-4 animate-spin" /> : <FilePenLine className="h-4 w-4" />}
          Confirm supersession
        </button>
        <button className={SECONDARY} disabled={Boolean(busyAction)}
          onClick={() => { setConfirmSupersede(false); setSupersedeReason(''); }}>Cancel</button>
      </div>
    </section>}
    {lifecycleAction && <section className="backoffice-confirm" aria-labelledby="backoffice-lifecycle-confirm">
      <h3 id="backoffice-lifecycle-confirm">
        {lifecycleAction === 'pause' ? 'Pause this property?' : 'Archive this property?'}
      </h3>
      <p>{lifecycleAction === 'pause'
        ? 'The property becomes unavailable for current public discovery and intake. You can resume review separately.'
        : 'The property becomes unavailable without deleting its lessor, media, visit, entitlement or audit records. A pending lessor removal request must be decided through the removal workflow.'}</p>
      <label htmlFor="backoffice-lifecycle-reason" className="grid gap-1.5 text-sm font-semibold text-[#4d594f]">
        <span>Reason (required)</span>
        <textarea id="backoffice-lifecycle-reason" autoFocus className={`${FIELD} min-h-24`}
          maxLength={500} value={lifecycleReason}
          onChange={event => setLifecycleReason(event.target.value)} />
      </label>
      <div className="backoffice-actions">
        <button className={lifecycleAction === 'archive' ? 'backoffice-danger' : PRIMARY}
          disabled={Boolean(busyAction) || !lifecycleReason.trim()}
          onClick={() => { void lifecycle(lifecycleAction); }}>
          {busyAction ? <LoaderCircle className="h-4 w-4 animate-spin" /> : null}
          Confirm {lifecycleAction}
        </button>
        <button className={SECONDARY} disabled={Boolean(busyAction)}
          onClick={() => { setLifecycleAction(null); setLifecycleReason(''); }}>Cancel</button>
      </div>
    </section>}

    <nav className="backoffice-tabs" aria-label="Property workspace sections">
      <button aria-current={tab === 'overview' ? 'page' : undefined} onClick={() => setTab('overview')}>Overview</button>
      {capability.canEdit && revision && <button aria-current={tab === 'edit' ? 'page' : undefined}
        onClick={() => setTab('edit')}>Edit and review</button>}
      {capability.canAudit && <button aria-current={tab === 'history' ? 'page' : undefined}
        onClick={() => setTab('history')}>Activity history</button>}
    </nav>

    {tab === 'overview' && <section className="backoffice-overview-grid">
      <article className="backoffice-card backoffice-overview-facts">
        <p className="backoffice-eyebrow">CURRENT PUBLISHED CONTENT</p>
        <h2>{detail.content.bhkCount} · {detail.content.propertyType.replace(/_/g, ' ').toLowerCase()}</h2>
        <dl>
          <div><dt>Monthly rent</dt><dd>{MONEY.format(detail.content.monthlyRent)}</dd></div>
          <div><dt>Security deposit</dt><dd>{MONEY.format(detail.content.securityDeposit)}</dd></div>
          <div><dt>Available from</dt><dd>{detail.content.availableFrom}</dd></div>
          <div><dt>Area</dt><dd>{detail.content.totalAreaSqFt ? `${detail.content.totalAreaSqFt} sq ft` : 'Not supplied'}</dd></div>
          <div><dt>Furnishing</dt><dd>{detail.content.furnishingStatus?.replace(/_/g, ' ').toLowerCase() || 'Not supplied'}</dd></div>
          <div><dt>Media</dt><dd>{detail.content.media.length} item(s)</dd></div>
        </dl>
        <p className="backoffice-footnote">Private presentation address is available only in the authorized editor. Canonical City, locality and ownership are server-controlled.</p>
      </article>
      <aside className="backoffice-card">
        <p className="backoffice-eyebrow">AUTHORITY AND STATUS</p>
        <h2>Accountable by design</h2>
        <ul className="backoffice-principles">
          <li><ShieldCheck />City scope is rechecked on every mutation.</li>
          <li><History />Publication versions preserve exact before and after values.</li>
          <li><Clock3 />A pause state remains paused after publication.</li>
        </ul>
        {detail.openRevisionDraftId && <div className="backoffice-revision-banner">
          <FilePenLine />
          <div><strong>{detail.openRevisionStatus === 'DRAFT' ? 'A staff draft is open' : 'A revision is already pending'}</strong>
            <p>{detail.openRevisionStatus === 'DRAFT'
              ? 'Continue the existing revision. A second writer cannot overwrite it.'
              : 'Lessor or review work must finish before a new staff revision can start.'}</p></div>
        </div>}
      </aside>
    </section>}

    {tab === 'edit' && revision && capability.canEdit
      && <BackofficePropertyEditor detail={detail} revision={revision}
        onRevision={saved => {
          if (editorOwnsCompletion(detail.listingId, saved.draftId)) setRevision(saved);
        }}
        onPublished={() => {
          if (!editorOwnsCompletion(detail.listingId, revision.draftId)) return;
          setTab('history'); setNotice('Property content published successfully.'); void loadDetail();
        }}
        onDiscarded={() => {
          if (!editorOwnsCompletion(detail.listingId, revision.draftId)) return;
          setRevision(null); setTab('overview');
          setNotice('Staff draft discarded. The live property was not changed.'); void loadDetail();
        }} />}

    {tab === 'history' && <section className="backoffice-card">
      <p className="backoffice-eyebrow">SAVED PROPERTY CHANGES</p>
      <h2 ref={activityHeading} tabIndex={-1}>Every change, with who and when</h2>
      {activityState === 'loading' && <div className="backoffice-loading"><LoaderCircle className="h-5 w-5 animate-spin" />Loading activity…</div>}
      {activityState === 'error' && <div className="backoffice-alert backoffice-alert--error"><TriangleAlert />
        <div><p>{activityError}</p><button className={SECONDARY}
          onClick={() => setActivityReload(value => value + 1)}>Retry activity</button></div></div>}
      {activityError && activityState !== 'error' && <div className="backoffice-alert backoffice-alert--error">
        <TriangleAlert /><div><p>{activityError}</p><button className={SECONDARY}
          disabled={activityLoadingMore} onClick={() => { void loadOlderActivity(); }}>
          Retry older activity
        </button></div></div>}
      {activityState === 'ready' && activity.length === 0 && <div className="backoffice-empty"><History />
        <h3>No recorded staff activity yet</h3><p>New revisions and publications will appear here.</p></div>}
      <ol className="backoffice-timeline">
        {activity.map(item => <li key={item.eventId}>
          <span className={`backoffice-timeline-mark backoffice-timeline-mark--${item.kind.toLowerCase()}`} aria-hidden="true" />
          <div>
            <div className="backoffice-timeline-head"><strong>{item.summary}</strong>
              {item.version !== null && <span>{item.kind === 'PUBLICATION' ? 'Content' : 'Draft'} v{item.version}</span>}</div>
            <p>{formatDate(item.occurredAt)} · {item.actorDisplayName}</p>
            {Object.keys(item.details).length > 0 && <details className="backoffice-activity-details">
              <summary>{item.kind === 'PUBLICATION' ? 'View exact before and after changes' : 'View recorded details'}</summary>
              <dl>
                {Object.entries(item.details).map(([field, value]) => {
                  const change = value && typeof value === 'object'
                    ? value as { before?: unknown; after?: unknown } : {};
                  if (field === 'media') {
                    const evidence = describeMediaChange(change.before, change.after);
                    return <div key={field}><dt>Media</dt>
                      <dd><p>{summarizeMediaChange(change.before, change.after).text}</p>
                        {evidence.length > 0 && <ul className="backoffice-media-history" aria-label="Exact media changes">
                          {evidence.map(resource => <MediaHistoryEvidence key={resource.identity}
                            resource={resource} />)}
                        </ul>}
                      </dd></div>;
                  }
                  if (!value || typeof value !== 'object') {
                    return <div key={field}>
                      <dt>{field.replace(/([A-Z])/g, ' $1').replace(/_/g, ' ')}</dt>
                      <dd>{detailValue(value)}</dd>
                    </div>;
                  }
                  return <div key={field}><dt>{field.replace(/([A-Z])/g, ' $1').replace(/_/g, ' ')}</dt>
                    <dd>{detailValue(change.before)} → <strong>{detailValue(change.after)}</strong></dd></div>;
                })}
              </dl>
            </details>}
            {item.kind === 'PUBLICATION' && capability.canEdit && detail.status !== 'ARCHIVED'
              && <button className={SECONDARY} disabled={Boolean(busyAction)}
                onClick={() => { if (item.version !== null) void corrective(item.version); }}>
                <FilePenLine className="h-4 w-4" />Use this version for a corrective revision
              </button>}
          </div>
        </li>)}
      </ol>
      {activityState === 'ready' && activityHasMore && <div className="backoffice-history-more">
        <button className={SECONDARY} disabled={activityLoadingMore}
          onClick={() => { void loadOlderActivity(); }}>
          {activityLoadingMore ? 'Loading older activity…' : 'Load older activity'}
        </button>
      </div>}
    </section>}
  </main>;
}

function MediaHistoryEvidence({ resource }: { resource: MediaResourceDifference }) {
  return <li>
    <strong>{resource.label}</strong>
    <span>{resource.kind === 'ADDED' ? 'Added' : resource.kind === 'REMOVED' ? 'Removed' : 'Changed'}</span>
    <dl>
      {resource.changes.map(change => <div key={change.field}>
        <dt>{change.label}</dt>
        <dd>{formatActivityEvidenceValue(change.field, change.before)}
          {' → '}<strong>{formatActivityEvidenceValue(change.field, change.after)}</strong></dd>
      </div>)}
    </dl>
  </li>;
}

function BackofficeStatus({ title, message, busy = false, action, onAction }: {
  title: string; message: string; busy?: boolean; action?: string; onAction?: () => void;
}) {
  return <main className="backoffice-shell">
    <section className="backoffice-status-card" aria-live="polite">
      {busy ? <LoaderCircle className="h-8 w-8 animate-spin motion-reduce:animate-none" />
        : <ShieldCheck className="h-8 w-8" />}
      <h1>{title}</h1><p>{message}</p>
      {action && onAction && <button className={PRIMARY} onClick={onAction}>{action}</button>}
    </section>
  </main>;
}

