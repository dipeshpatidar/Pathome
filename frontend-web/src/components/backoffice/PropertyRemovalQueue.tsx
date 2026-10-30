import React, { useEffect, useRef, useState } from 'react';
import {
  Archive, CalendarClock, CheckCircle2, LoaderCircle, RefreshCw, ShieldAlert,
  TriangleAlert, XCircle
} from 'lucide-react';
import { getErrorMessage } from '../../services/apiError';
import {
  propertyRemovalService,
  type RemovalAdminDetail,
  type RemovalQueueItem,
  type RemovalStatus
} from '../../services/propertyRemovalService';
import type { RemovalAdmissionQueue } from '../../hooks/usePropertyRemovalAuthority';
import './backoffice.css';
import { emitPropertyCurrentChanged } from '../../utils/propertyCurrentEvents';
import { isLatestRequest } from '../../utils/requestEpoch.ts';
import {
  operationIdForIntent,
  operationIntentKey,
  type OperationIntent
} from '../../utils/operationIntent.ts';
import { captureOperationsSession, subscribeOperationsSession } from '../../utils/operationsSession.ts';
import {
  ExclusiveOperationGate,
  isProtectedDenialStatus,
  isUncertainCommandFailure,
  statusOf
} from '../../utils/operationOwnership.ts';

const DATE = new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium', timeStyle: 'short' });
const PRIMARY = 'inline-flex min-h-11 items-center justify-center gap-2 rounded-lg bg-[#355c49] px-4 text-sm font-semibold text-white hover:bg-[#284a38] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#8aa08b] disabled:opacity-50';
const SECONDARY = 'inline-flex min-h-11 items-center justify-center gap-2 rounded-lg border border-[#dddcd5] bg-white px-4 text-sm font-semibold text-[#3f5d47] hover:bg-[#f5f6f2] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#8aa08b] disabled:opacity-50';

export function PropertyRemovalQueue({
  initialAdmission = null
}: {
  initialAdmission?: RemovalAdmissionQueue | null;
}) {
  const initialSession = captureOperationsSession();
  const useInitialAdmission = Boolean(
    initialAdmission
    && initialSession
    && initialAdmission.sessionIdentity === initialSession.identity
  );
  const [status, setStatus] = useState<RemovalStatus | ''>('PENDING');
  const [items, setItems] = useState<RemovalQueueItem[]>(
    useInitialAdmission ? initialAdmission!.items : []
  );
  const [page, setPage] = useState(useInitialAdmission ? initialAdmission!.page : 0);
  const [hasMore, setHasMore] = useState(
    useInitialAdmission ? initialAdmission!.hasMore : false
  );
  const [state, setState] = useState<'loading' | 'ready' | 'error'>(
    useInitialAdmission ? 'ready' : 'loading'
  );
  const [error, setError] = useState('');
  const [detail, setDetail] = useState<RemovalAdminDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [decision, setDecision] = useState<'APPROVED' | 'REJECTED' | null>(null);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState('');
  const [sessionEpoch, setSessionEpoch] = useState(0);
  const dialog = useRef<HTMLDialogElement>(null);
  const decisionIntent = useRef<OperationIntent | null>(null);
  const queueRequestEpoch = useRef(0);
  const queueAbort = useRef<AbortController | null>(null);
  const detailRequestEpoch = useRef(0);
  const detailAbort = useRef<AbortController | null>(null);
  const detailOpener = useRef<HTMLButtonElement | null>(null);
  const decisionOpener = useRef<HTMLButtonElement | null>(null);
  const queueTitle = useRef<HTMLHeadingElement>(null);
  const pageStatus = useRef<HTMLParagraphElement>(null);
  const restorePageFocus = useRef(false);
  const detailTitle = useRef<HTMLHeadingElement>(null);
  const decisionGate = useRef(new ExclusiveOperationGate());
  const queueGeneration = useRef(0);
  const skipInitialAdmissionLoad = useRef(useInitialAdmission);

  const load = async (requestedStatus: RemovalStatus | '' = status,
                     requestedPage = page, replace = false,
                     expectedGeneration = queueGeneration.current) => {
    const responseEpoch = ++queueRequestEpoch.current;
    queueAbort.current?.abort();
    const controller = new AbortController();
    queueAbort.current = controller;
    const generation = expectedGeneration;
    setState('loading');
    setError('');
    if (replace) setItems([]);
    try {
      const result = await propertyRemovalService.adminQueue(
        requestedStatus, requestedPage, null, controller.signal);
      if (controller.signal.aborted
          || !isLatestRequest(responseEpoch, queueRequestEpoch.current)
          || generation !== queueGeneration.current) return;
      setItems(result.items);
      setPage(result.page);
      setHasMore(result.hasMore);
      setState('ready');
      if (restorePageFocus.current) {
        restorePageFocus.current = false;
        window.requestAnimationFrame(() => pageStatus.current?.focus());
      }
    } catch (cause) {
      if (controller.signal.aborted
          || !isLatestRequest(responseEpoch, queueRequestEpoch.current)
          || generation !== queueGeneration.current) return;
      if (isProtectedDenialStatus(statusOf(cause))) {
        setItems([]);
        setPage(0);
        setHasMore(false);
        setDetail(null);
      }
      setError(getErrorMessage(cause, 'Removal requests could not be loaded.'));
      setState('error');
    } finally {
      if (queueAbort.current === controller) queueAbort.current = null;
    }
  };

  useEffect(() => {
    return subscribeOperationsSession(() => {
      queueRequestEpoch.current += 1;
      queueAbort.current?.abort();
      detailRequestEpoch.current += 1;
      detailAbort.current?.abort();
      decisionIntent.current = null;
      decisionGate.current.invalidate();
      queueGeneration.current += 1;
      setBusy(false);
      setDetail(null);
      setDecision(null);
      setItems([]);
      setPage(0);
      setHasMore(false);
      setState('loading');
      setSessionEpoch(value => value + 1);
    });
  }, []);

  useEffect(() => () => {
    detailRequestEpoch.current += 1;
    detailAbort.current?.abort();
  }, []);

  useEffect(() => {
    if (skipInitialAdmissionLoad.current) {
      skipInitialAdmissionLoad.current = false;
      return;
    }
    void load(status, page, page === 0);
    return () => {
      queueRequestEpoch.current += 1;
      queueAbort.current?.abort();
    };
  }, [status, page, sessionEpoch]);

  const invalidateDecisionOwner = () => {
    decisionGate.current.invalidate();
    decisionIntent.current = null;
    setBusy(false);
  };

  useEffect(() => {
    const element = dialog.current;
    if (!element) return;
    if (decision && !element.open) {
      element.showModal();
    }
    if (!decision && element.open) {
      element.close();
      window.requestAnimationFrame(() => decisionOpener.current?.focus());
    }
  }, [decision]);

  const selectStatus = (nextStatus: RemovalStatus | '') => {
    if (nextStatus === status) return;
    queueGeneration.current += 1;
    queueRequestEpoch.current += 1;
    queueAbort.current?.abort();
    detailRequestEpoch.current += 1;
    detailAbort.current?.abort();
    invalidateDecisionOwner();
    setDetail(null);
    setDetailLoading(false);
    setDecision(null);
    setReason('');
    setError('');
    setNotice('');
    setPage(0);
    setHasMore(false);
    setItems([]);
    setStatus(nextStatus);
  };

  const open = async (item: RemovalQueueItem, opener: HTMLButtonElement) => {
    if (detail || decisionGate.current.isActive) {
      invalidateDecisionOwner();
      detailRequestEpoch.current += 1;
      setDecision(null);
      setBusy(false);
    }
    const responseEpoch = ++detailRequestEpoch.current;
    detailAbort.current?.abort();
    const controller = new AbortController();
    detailAbort.current = controller;
    const generation = queueGeneration.current;
    detailOpener.current = opener;
    setDetailLoading(true);
    setError('');
    setNotice('');
    try {
      const nextDetail = await propertyRemovalService.adminDetail(
        item.requestId, controller.signal);
      if (controller.signal.aborted
          || !isLatestRequest(responseEpoch, detailRequestEpoch.current)
          || generation !== queueGeneration.current) return;
      setDetail(nextDetail);
      window.requestAnimationFrame(() => detailTitle.current?.focus());
    } catch (cause) {
      if (controller.signal.aborted
          || !isLatestRequest(responseEpoch, detailRequestEpoch.current)
          || generation !== queueGeneration.current) return;
      if (isProtectedDenialStatus(statusOf(cause))) {
        setDetail(null);
        setItems([]);
        setPage(0);
        setHasMore(false);
      }
      setError(getErrorMessage(cause, 'The removal request could not be opened.'));
    } finally {
      if (!controller.signal.aborted
          && isLatestRequest(responseEpoch, detailRequestEpoch.current)) {
        setDetailLoading(false);
      }
      if (detailAbort.current === controller) detailAbort.current = null;
    }
  };

  const closeDetail = () => {
    if (decisionGate.current.isActive) invalidateDecisionOwner();
    detailRequestEpoch.current += 1;
    detailAbort.current?.abort();
    setDetail(null);
    setDetailLoading(false);
    window.requestAnimationFrame(() => detailOpener.current?.focus());
  };

  const openDecision = (
    nextDecision: 'APPROVED' | 'REJECTED',
    opener: HTMLButtonElement
  ) => {
    decisionOpener.current = opener;
    setReason('');
    setDecision(nextDecision);
  };

  const closeDecision = () => {
    if (busy) return;
    setDecision(null);
  };

  const submitDecision = async () => {
    if (!detail || !decision || !reason.trim()) return;
    const sessionIdentity = captureOperationsSession()?.identity ?? '';
    const owner = decisionGate.current.begin(sessionIdentity, 'removal-admin',
      `${detail.request.requestId}:${detail.request.requestVersion}`, queueGeneration.current);
    if (!owner) return;
    const focusGeneration = queueGeneration.current;
    const submittedDecision = decision;
    const submittedDetail = detail;
    const submittedStatus = status;
    const submittedGeneration = queueGeneration.current;
    setBusy(true);
    setError('');
    try {
      const intentKey = operationIntentKey([
        sessionIdentity,
        submittedDetail.request.requestId,
        submittedDetail.request.requestVersion,
        submittedDetail.request.currentListingVersion,
        submittedDecision,
        reason.trim()
      ]);
      decisionIntent.current = operationIdForIntent(decisionIntent.current, intentKey);
      await propertyRemovalService.decide(
        submittedDetail, submittedDecision, reason, decisionIntent.current.id);
      if (!decisionGate.current.owns(owner)
          || sessionIdentity !== (captureOperationsSession()?.identity ?? '')) return;
      decisionIntent.current = null;
      if (submittedDecision === 'APPROVED') {
        emitPropertyCurrentChanged({ listingId: submittedDetail.request.listingId, reason: 'ARCHIVED' });
      }
      const focusAfterDecision = queueTitle.current;
      setDecision(null);
      setReason('');
      setDetail(null);
      setNotice(submittedDecision === 'APPROVED'
        ? 'Removal approved. The property is archived and its history remains available under its existing authorization.'
        : 'Removal rejected. The property remains in its current lifecycle state.');
      setPage(0);
      await load(submittedStatus, 0, true, submittedGeneration);
      window.requestAnimationFrame(() => {
        if (focusGeneration === queueGeneration.current) focusAfterDecision?.focus();
      });
    } catch (cause) {
      if (!decisionGate.current.owns(owner)
          || sessionIdentity !== (captureOperationsSession()?.identity ?? '')) return;
      const statusCode = statusOf(cause);
      if (isUncertainCommandFailure(statusCode)) {
        setError('The decision outcome could not be confirmed. Do not submit a second decision; reload this request before continuing.');
      } else {
        setError(getErrorMessage(cause, 'The decision was not completed.'));
      }
      if (isProtectedDenialStatus(statusCode)) {
        setDetail(null);
        setDecision(null);
        setItems([]);
        setPage(0);
        setHasMore(false);
      }
      if (statusCode === 409) {
        try {
          const refreshed = await propertyRemovalService.adminDetail(submittedDetail.request.requestId);
          if (decisionGate.current.owns(owner)) setDetail(refreshed);
        } catch {
          // The reviewed reason remains in the form for explicit manual reconfirmation.
        }
      }
    } finally {
      if (decisionGate.current.finish(owner)) setBusy(false);
    }
  };

  return <section className="backoffice-shell" aria-labelledby="removal-queue-title">
    <header className="backoffice-hero">
      <div>
        <p className="backoffice-eyebrow"><ShieldAlert />ADMIN REMOVAL REVIEW</p>
        <h1 id="removal-queue-title">Decisions with <em>preserved history.</em></h1>
        <p>A lessor can request removal, but only a currently eligible legacy Admin can approve the archive. Existing appointments and business records are not cancelled.</p>
      </div>
    </header>
    <section className="backoffice-inventory">
      <div className="backoffice-inventory-head">
        <div><p className="backoffice-eyebrow">REQUEST QUEUE</p><h2 ref={queueTitle} tabIndex={-1}>Property removal requests</h2></div>
        <div className="backoffice-removal-filters" role="group" aria-label="Filter by status">
          {(['PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN', ''] as const).map(value =>
            <button key={value || 'ALL'} type="button" aria-pressed={status === value}
              onClick={() => selectStatus(value)}>{value || 'All'}</button>)}
        </div>
      </div>
      {notice && <div role="status" className="backoffice-alert backoffice-alert--success"><CheckCircle2 /><p>{notice}</p></div>}
      {error && <div role="alert" className="backoffice-alert backoffice-alert--error"><TriangleAlert /><div><p>{error}</p>
        <button className={SECONDARY} onClick={() => { void load(status, page, false); }}><RefreshCw className="h-4 w-4" />Reload current state</button></div></div>}
      {(state === 'loading' || detailLoading) && <div className="backoffice-loading">
        <LoaderCircle className="h-5 w-5 animate-spin motion-reduce:animate-none" />Loading current request context…
      </div>}
      {state === 'ready' && items.length === 0 && !detailLoading && <div className="backoffice-empty">
        <Archive /><h2>No {status ? status.toLowerCase() : ''} requests</h2>
        <p>New lessor removal requests will appear here.</p>
      </div>}
      {items.length > 0 && <div className="backoffice-removal-list">
        {items.map(item => <button type="button" key={item.requestId}
          onClick={event => { void open(item, event.currentTarget); }}
          className="backoffice-removal-row">
          <span className={`backoffice-status backoffice-status--${item.requestStatus.toLowerCase()}`}>{item.requestStatus}</span>
          <span className="min-w-0"><strong>{item.propertyTitle}</strong>
            <small>{[item.locality, item.city].filter(Boolean).join(', ') || 'Canonical City unresolved'}</small></span>
          <span><CalendarClock /><small>{item.activeAppointmentCount} active appointment{item.activeAppointmentCount === 1 ? '' : 's'}</small></span>
          <span><small>Requested {DATE.format(new Date(item.requestedAt))}</small></span>
        </button>)}
      </div>}
      {state === 'ready' && (page > 0 || hasMore) && <nav
        className="backoffice-pagination" aria-label="Removal request pages">
        <button type="button" className={SECONDARY} disabled={page === 0}
          onClick={() => { restorePageFocus.current = true; setPage(current => Math.max(0, current - 1)); }}>
          Previous
        </button>
        <p ref={pageStatus} tabIndex={-1} aria-live="polite">Page {page + 1}</p>
        <button type="button" className={SECONDARY} disabled={!hasMore}
          onClick={() => { restorePageFocus.current = true; setPage(current => current + 1); }}>
          Next
        </button>
      </nav>}
    </section>

    {detail && <section className="backoffice-removal-detail" aria-labelledby="removal-detail-title">
      <button type="button" className={SECONDARY} onClick={closeDetail}>
        <CalendarClock className="h-4 w-4" />Back to requests
      </button>
      <div className="backoffice-removal-decision-layout">
        <div className="backoffice-removal-detail-stack">
          <article className="backoffice-card">
            <div className="backoffice-inventory-head">
              <div>
                <p className="backoffice-eyebrow">REMOVAL REQUEST · {detail.request.requestStatus}</p>
                <h2 id="removal-detail-title" ref={detailTitle} tabIndex={-1}>{detail.propertyTitle}</h2>
                <p className="backoffice-footnote">{[detail.locality, detail.city].filter(Boolean).join(', ') || 'Canonical City unresolved'}</p>
              </div>
              <span className={`backoffice-status backoffice-status--${detail.request.requestStatus.toLowerCase()}`}>
                {detail.request.requestStatus}
              </span>
            </div>
            <div className="backoffice-removal-facts">
              <div><span>Requested by</span><p>Property owner (lessor)</p></div>
              <div><span>Requested</span><p>{DATE.format(new Date(detail.request.requestedAt))}</p></div>
              <div><span>Owner reason</span><p>{detail.request.ownerReason || 'No reason supplied'}</p></div>
              <div><span>Request version</span><p>v{detail.request.requestVersion}</p></div>
            </div>
          </article>

          <article className="backoffice-card">
            <p className="backoffice-eyebrow">CURRENT LISTING</p>
            <h2 className="backoffice-section-title">Current property state</h2>
            <div className="backoffice-removal-facts">
              <div><span>Status</span><p>{detail.request.currentListingStatus?.replace(/_/g, ' ') || 'Unavailable'}</p></div>
              <div><span>Current listing version</span><p>{detail.request.currentListingVersion === null
                ? 'Unavailable' : `v${detail.request.currentListingVersion}`}</p></div>
              <div><span>Appointments</span><p>{detail.scheduledAppointmentCount} scheduled · {detail.startedAppointmentCount} started</p></div>
              <div><span>Content changes</span><p>{detail.openRevisionStatus
                ? `Open · ${detail.openRevisionStatus.replace(/_/g, ' ').toLowerCase()}` : 'None open'}</p></div>
            </div>
          </article>
        </div>

        <aside className="backoffice-card backoffice-removal-decision-rail" aria-labelledby="removal-decision-rail-title">
          <p className="backoffice-eyebrow">DECISION</p>
          <h2 id="removal-decision-rail-title" className="backoffice-section-title">What approval does</h2>
          <ul className="backoffice-removal-consequences">
            <li>The property is archived and leaves current public and intake surfaces.</li>
            <li>Its history remains available under its existing authorization.</li>
            <li>Existing appointments and business records are not cancelled.</li>
          </ul>
          <p className="backoffice-footnote">Only a currently eligible legacy Admin can approve the archive. Rejecting leaves the listing unchanged.</p>
          <div className="backoffice-alert backoffice-alert--warn"><TriangleAlert />
            <p>A decision uses this request and listing exactly as shown. If either changed, review the refreshed state before deciding again.</p>
          </div>
          {detail.request.requestStatus === 'PENDING' ? <div className="backoffice-removal-decision-actions">
            <button type="button" className="backoffice-danger"
              onClick={event => openDecision('APPROVED', event.currentTarget)}>
              <Archive className="h-4 w-4" />Approve removal
            </button>
            <button type="button" className={SECONDARY}
              onClick={event => openDecision('REJECTED', event.currentTarget)}>
              <XCircle className="h-4 w-4" />Reject request
            </button>
          </div> : <p className="backoffice-removal-decided-note" role="status">
            This request is {detail.request.requestStatus.toLowerCase()}. No further decision is available.
          </p>}
        </aside>
      </div>
    </section>}

    <dialog ref={dialog} aria-labelledby="removal-decision-title" aria-describedby="removal-decision-copy"
      onCancel={event => { if (busy) event.preventDefault(); else closeDecision(); }}
      onClose={() => { if (!busy) setDecision(null); }}
      className="m-auto max-h-[calc(100dvh-2rem)] w-[calc(100%-2rem)] max-w-xl overflow-y-auto rounded-2xl border border-slate-200 bg-white p-0 text-slate-900 shadow-2xl backdrop:bg-slate-950/55">
      {detail && decision && <div className="p-5 sm:p-7">
        <p className={`backoffice-eyebrow ${decision === 'APPROVED' ? 'text-rose-700' : ''}`}>
          {decision === 'APPROVED' ? 'ARCHIVE PROPERTY' : 'REJECT REMOVAL'}
        </p>
        <h2 id="removal-decision-title" className="mt-2 font-serif text-3xl text-[#303c32]">
          {decision === 'APPROVED' ? 'Approve this removal?' : 'Reject this removal request?'}
        </h2>
        <p id="removal-decision-copy" className="mt-2 text-sm leading-relaxed text-slate-600">
          {decision === 'APPROVED'
            ? 'This removes the property from current public and intake surfaces. Scheduled and started visits remain governed by their existing contracts.'
            : 'The property will keep its current lifecycle state. The reason below is shown to the lessor.'}
        </p>
        <label htmlFor="removal-decision-reason" className="mt-5 grid gap-1.5 text-sm font-semibold text-[#4d594f]">
          <span>{decision === 'APPROVED' ? 'Decision reason' : 'Owner-visible rejection reason'}</span>
          <textarea id="removal-decision-reason" autoFocus required maxLength={1000}
            className="min-h-28 w-full rounded-lg border border-[#dddcd5] bg-white px-3 py-2 text-base outline-none focus:border-[#718b73] focus:ring-2 focus:ring-[#e6ece4]"
            value={reason} onChange={event => setReason(event.target.value)} />
        </label>
        {error && <p role="alert" className="mt-3 rounded-lg border border-rose-200 bg-rose-50 p-3 text-sm text-rose-800">{error}</p>}
        <div className="mt-5 flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          <button className={SECONDARY} disabled={busy} onClick={closeDecision}>Cancel</button>
          <button className={decision === 'APPROVED'
            ? 'inline-flex min-h-11 items-center justify-center gap-2 rounded-lg bg-rose-700 px-4 text-sm font-semibold text-white hover:bg-rose-800 disabled:opacity-50'
            : PRIMARY} disabled={busy || !reason.trim()} onClick={submitDecision}>
            {busy ? <LoaderCircle className="h-4 w-4 animate-spin" /> : decision === 'APPROVED'
              ? <Archive className="h-4 w-4" /> : <XCircle className="h-4 w-4" />}
            {decision === 'APPROVED' ? 'Confirm approval' : 'Confirm rejection'}
          </button>
        </div>
      </div>}
    </dialog>
  </section>;
}

