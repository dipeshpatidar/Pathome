import React, { useEffect, useRef, useState } from 'react';
import { ArrowLeft, Building2, LoaderCircle } from 'lucide-react';
import { getErrorMessage } from '../services/apiError';
import { LessorListingDetail, lessorPortfolioService } from '../services/lessorPortfolioService';
import { LessorListingErrorState } from './LessorListingErrorState';
import { propertyRemovalService, type RemovalRequestView } from '../services/propertyRemovalService';
import { isLatestRequest } from '../utils/requestEpoch.ts';
import {
  operationIdForIntent,
  operationIntentKey,
  type OperationIntent
} from '../utils/operationIntent.ts';
import {
  captureOperationsSession,
  isOperationsSessionActive,
  subscribeOperationsSession,
  type OperationsSession
} from '../utils/operationsSession.ts';
import {
  ExclusiveOperationGate,
  isCurrentProtectedDenial,
  isUncertainCommandFailure,
  statusOf,
  type OperationOwner
} from '../utils/operationOwnership.ts';
import {
  canRequestLessorRemoval,
  latestLessorRemoval,
  lessorRemovalState,
  showLessorListingChanges
} from '../utils/lessorRemovalPresentation.ts';

const SECONDARY = 'inline-flex min-h-11 items-center justify-center gap-2 rounded-xl border border-slate-300 bg-white px-4 text-sm font-semibold text-slate-700 hover:bg-slate-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500';
const DANGER = 'inline-flex min-h-11 items-center justify-center gap-2 rounded-xl border border-rose-200 bg-white px-4 text-sm font-semibold text-rose-700 hover:bg-rose-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-rose-400 disabled:opacity-50';
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

type RemovalPageFocusAction = 'next' | 'previous';

type RemovalPageFocusTicket = {
  listingId: number;
  listingEpoch: number;
  session: OperationsSession | null;
  page: number;
  pageRequestEpoch: number;
  action: RemovalPageFocusAction;
};

export function LessorListingView({ listingId, onBack, onOpenDraft }: { listingId: number; onBack: () => void; onOpenDraft: (id: string) => void }) {
  const [detail, setDetail] = useState<LessorListingDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [removalHistory, setRemovalHistory] = useState<RemovalRequestView[]>([]);
  const [latestRemovalSnapshot, setLatestRemovalSnapshot] = useState<RemovalRequestView | null>(null);
  const [removalPage, setRemovalPage] = useState(0);
  const [removalHasMore, setRemovalHasMore] = useState(false);
  const [removalReason, setRemovalReason] = useState('');
  const [confirmRemoval, setConfirmRemoval] = useState(false);
  const [removalBusy, setRemovalBusy] = useState(false);
  const [removalHistoryState, setRemovalHistoryState] = useState<'loading' | 'ready' | 'error'>('loading');
  const [removalReadbackNeeded, setRemovalReadbackNeeded] = useState(false);
  const [removalReadbackBusy, setRemovalReadbackBusy] = useState(false);
  const removalIntent = useRef<OperationIntent | null>(null);
  const withdrawalIntent = useRef<OperationIntent | null>(null);
  const removalOperationGate = useRef(new ExclusiveOperationGate());
  const listingEpoch = useRef(0);
  const removalPageEpoch = useRef(0);
  const removalPageStatus = useRef<HTMLParagraphElement>(null);
  const removalPageFocusTicket = useRef<RemovalPageFocusTicket | null>(null);
  const removalPageFocusFrame = useRef<number | null>(null);
  const listingBusyRef = useRef(false);
  const removalBusyRef = useRef(false);
  const removalReadbackBusyRef = useRef(false);
  const removalOperationIsOwned = (
    owner: OperationOwner,
    session: OperationsSession | null,
    responseEpoch: number
  ): boolean => isLatestRequest(responseEpoch, listingEpoch.current)
    && removalOperationGate.current.owns(owner)
    && isOperationsSessionActive(session);
  const loadIsOwned = (
    session: OperationsSession | null,
    responseEpoch: number
  ): boolean => isLatestRequest(responseEpoch, listingEpoch.current)
    && isOperationsSessionActive(session);
  const invalidateRemovalPageFocus = (): void => {
    removalPageFocusTicket.current = null;
    if (removalPageFocusFrame.current !== null) {
      window.cancelAnimationFrame(removalPageFocusFrame.current);
      removalPageFocusFrame.current = null;
    }
  };
  const withdrawProtectedListingState = (cause: unknown, fallback: string): void => {
    listingEpoch.current += 1;
    removalPageEpoch.current += 1;
    invalidateRemovalPageFocus();
    removalOperationGate.current.invalidate();
    setDetail(null);
    setLoading(false);
    setError(getErrorMessage(cause, fallback));
    setRemovalHistory([]);
    setLatestRemovalSnapshot(null);
    setRemovalPage(0);
    setRemovalHasMore(false);
    setRemovalHistoryState('error');
    setConfirmRemoval(false);
    setRemovalReason('');
    setBusy(false);
    setRemovalBusy(false);
    setRemovalReadbackNeeded(false);
    setRemovalReadbackBusy(false);
    listingBusyRef.current = false;
    removalBusyRef.current = false;
    removalReadbackBusyRef.current = false;
  };
  const load = async (
    initial = true,
    responseEpoch = listingEpoch.current,
    session: OperationsSession | null = captureOperationsSession()
  ): Promise<boolean> => {
    const owns = () => loadIsOwned(session, responseEpoch);
    if (initial && owns()) setLoading(true);
    if (owns()) setError('');
    try {
      const nextDetail = await lessorPortfolioService.get(listingId);
      if (!owns()) return false;
      setDetail(nextDetail);
      return true;
    } catch (cause) {
      if (!owns()) return false;
      if (isCurrentProtectedDenial(statusOf(cause), owns())) {
        withdrawProtectedListingState(cause, 'This property is not available in your account.');
        return false;
      }
      setError(getErrorMessage(cause, 'Unable to open this property.'));
      return false;
    } finally {
      if (owns()) setLoading(false);
    }
  };
  const loadRemovalHistory = async (
    page = removalPage,
    responseEpoch = listingEpoch.current,
    session: OperationsSession | null = captureOperationsSession(),
    focusAction?: RemovalPageFocusAction
  ): Promise<boolean> => {
    const owns = () => loadIsOwned(session, responseEpoch);
    if (!owns()) return false;
    invalidateRemovalPageFocus();
    const pageRequestEpoch = ++removalPageEpoch.current;
    if (!owns()) return false;
    if (focusAction) {
      removalPageFocusTicket.current = {
        listingId,
        listingEpoch: responseEpoch,
        session,
        page,
        pageRequestEpoch,
        action: focusAction
      };
    }
    setRemovalHistoryState('loading');
    try {
      const result = await propertyRemovalService.history(listingId, page);
      if (!owns() || !isLatestRequest(pageRequestEpoch, removalPageEpoch.current)) return false;
      setRemovalHistory(result.items);
      if (page === 0) setLatestRemovalSnapshot(latestLessorRemoval(result.items));
      setRemovalPage(result.page);
      setRemovalHasMore(result.hasMore);
      setRemovalHistoryState('ready');
      const ticket = removalPageFocusTicket.current;
      if (ticket && ticket.listingId === listingId
          && ticket.listingEpoch === responseEpoch
          && ticket.pageRequestEpoch === pageRequestEpoch
          && ticket.page === page && ticket.page === result.page
          && ticket.action === focusAction
          && isOperationsSessionActive(ticket.session)) {
        removalPageFocusFrame.current = window.requestAnimationFrame(() => {
          removalPageFocusFrame.current = null;
          if (removalPageFocusTicket.current !== ticket
              || !owns()
              || !isLatestRequest(ticket.pageRequestEpoch, removalPageEpoch.current)
              || !isOperationsSessionActive(ticket.session)) return;
          removalPageFocusTicket.current = null;
          removalPageStatus.current?.focus();
        });
      } else if (focusAction) {
        invalidateRemovalPageFocus();
      }
      return true;
    } catch (cause) {
      if (!owns() || !isLatestRequest(pageRequestEpoch, removalPageEpoch.current)) return false;
      invalidateRemovalPageFocus();
      if (isCurrentProtectedDenial(statusOf(cause), owns())) {
        withdrawProtectedListingState(cause, 'Removal history is not available in your account.');
        return false;
      }
      setRemovalHistoryState('error');
      return false;
    }
  };
  useEffect(() => {
    return subscribeOperationsSession(() => {
      listingEpoch.current += 1;
      removalPageEpoch.current += 1;
      invalidateRemovalPageFocus();
      removalOperationGate.current.invalidate();
      removalIntent.current = null;
      withdrawalIntent.current = null;
      listingBusyRef.current = false;
      removalBusyRef.current = false;
      removalReadbackBusyRef.current = false;
      setDetail(null);
      setRemovalHistory([]);
      setLatestRemovalSnapshot(null);
      setRemovalPage(0);
      setRemovalHasMore(false);
      setLoading(false);
      setError('');
      setRemovalHistoryState('error');
      setConfirmRemoval(false);
      setRemovalReason('');
      setRemovalReadbackNeeded(false);
      setRemovalReadbackBusy(false);
      setBusy(false);
      setRemovalBusy(false);
    });
  }, []);

  useEffect(() => {
    const responseEpoch = ++listingEpoch.current;
    removalPageEpoch.current += 1;
    invalidateRemovalPageFocus();
    removalOperationGate.current.invalidate();
    setDetail(null);
    setRemovalHistory([]);
    setLatestRemovalSnapshot(null);
    setRemovalPage(0);
    setRemovalHasMore(false);
    setRemovalHistoryState('loading');
    setConfirmRemoval(false);
    setRemovalReason('');
    setBusy(false);
    setRemovalBusy(false);
    setRemovalReadbackNeeded(false);
    setRemovalReadbackBusy(false);
    removalIntent.current = null;
    withdrawalIntent.current = null;
    listingBusyRef.current = false;
    removalBusyRef.current = false;
    removalReadbackBusyRef.current = false;
    void load(true, responseEpoch);
    void loadRemovalHistory(0, responseEpoch);
    return () => {
      listingEpoch.current += 1;
      removalPageEpoch.current += 1;
      invalidateRemovalPageFocus();
      removalOperationGate.current.invalidate();
    };
  }, [listingId]);

  const startRevision = async () => {
    if (listingBusyRef.current) return;
    invalidateRemovalPageFocus();
    const operationEpoch = listingEpoch.current;
    const session = captureOperationsSession();
    const owns = () => isLatestRequest(operationEpoch, listingEpoch.current)
      && isOperationsSessionActive(session);
    listingBusyRef.current = true;
    setBusy(true); setError('');
    try {
      const draft = await lessorPortfolioService.startRevision(listingId);
      if (!owns()) return;
      onOpenDraft(draft.draftId);
    } catch (cause) {
      if (!owns()) return;
      if (isCurrentProtectedDenial(statusOf(cause), owns())) {
        withdrawProtectedListingState(cause, 'This property is not available in your account.');
        return;
      }
      setError(getErrorMessage(cause, 'Unable to start a revision.'));
    } finally {
      if (isLatestRequest(operationEpoch, listingEpoch.current)) {
        listingBusyRef.current = false;
        setBusy(false);
      }
    }
  };
  const updateStatus = async (action: 'pause' | 'resume-review') => {
    if (listingBusyRef.current || !detail) return;
    invalidateRemovalPageFocus();
    const operationEpoch = listingEpoch.current;
    const session = captureOperationsSession();
    const owns = () => isLatestRequest(operationEpoch, listingEpoch.current)
      && isOperationsSessionActive(session);
    listingBusyRef.current = true;
    setBusy(true); setError('');
    try {
      await lessorPortfolioService.changeStatus(listingId, detail.version, action);
      if (!owns()) return;
      await load(false, operationEpoch, session);
    } catch (cause) {
      if (!owns()) return;
      if (isCurrentProtectedDenial(statusOf(cause), owns())) {
        withdrawProtectedListingState(cause, 'This property is not available in your account.');
        return;
      }
      setError(getErrorMessage(cause, 'Unable to update this property.'));
    } finally {
      if (isLatestRequest(operationEpoch, listingEpoch.current)) {
        listingBusyRef.current = false;
        setBusy(false);
      }
    }
  };
  const refreshRemovalAfterCommand = async (
    owner: OperationOwner,
    session: OperationsSession | null,
    responseEpoch: number
  ): Promise<boolean> => {
    const detailLoaded = await load(false, responseEpoch, session);
    if (!removalOperationIsOwned(owner, session, responseEpoch) || !detailLoaded) return false;
    const historyLoaded = await loadRemovalHistory(0, responseEpoch, session);
    return removalOperationIsOwned(owner, session, responseEpoch) && historyLoaded;
  };
  const revalidateCurrentRemovalState = async (): Promise<void> => {
    if (removalBusyRef.current || removalReadbackBusyRef.current) return;
    invalidateRemovalPageFocus();
    const session = captureOperationsSession();
    const responseEpoch = listingEpoch.current;
    if (!loadIsOwned(session, responseEpoch)) return;
    removalReadbackBusyRef.current = true;
    setRemovalReadbackBusy(true);
    setError('');
    try {
      const detailLoaded = await load(false, responseEpoch, session);
      if (!loadIsOwned(session, responseEpoch) || !detailLoaded) return;
      const historyLoaded = await loadRemovalHistory(removalPage, responseEpoch, session);
      if (loadIsOwned(session, responseEpoch)) setRemovalReadbackNeeded(!historyLoaded);
    } finally {
      if (isLatestRequest(responseEpoch, listingEpoch.current)) {
        removalReadbackBusyRef.current = false;
        setRemovalReadbackBusy(false);
      }
    }
  };
  const handleCurrentRemovalFailure = (
    cause: unknown,
    fallback: string,
    uncertainMessage: string
  ): void => {
    if (isCurrentProtectedDenial(statusOf(cause), true)) {
      withdrawProtectedListingState(cause, 'This removal action is not available in your account.');
      return;
    }
    if (isUncertainCommandFailure(statusOf(cause))) {
      setRemovalReadbackNeeded(true);
      setError(uncertainMessage);
      return;
    }
    setError(getErrorMessage(cause, fallback));
  };
  const requestRemoval = async () => {
    if (!detail || removalBusyRef.current || removalReadbackBusyRef.current) return;
    invalidateRemovalPageFocus();
    const operationEpoch = listingEpoch.current;
    const session = captureOperationsSession();
    const sessionIdentity = session?.identity ?? '';
    const submittedDetail = detail;
    const submittedReason = removalReason.trim();
    const owner = removalOperationGate.current.begin(
      session?.identity,
      `lessor-listing:${listingId}:removal-create`,
      `${listingId}:${submittedDetail.version}:${submittedReason}`,
      operationEpoch
    );
    if (!owner) return;
    removalBusyRef.current = true;
    setRemovalBusy(true);
    setError('');
    try {
      const intentKey = operationIntentKey([
        sessionIdentity,
        listingId,
        submittedDetail.version,
        submittedReason
      ]);
      removalIntent.current = operationIdForIntent(removalIntent.current, intentKey);
      const created = await propertyRemovalService.create(listingId, submittedDetail.version,
        submittedReason, removalIntent.current.id);
      if (!removalOperationIsOwned(owner, session, operationEpoch)) return;
      removalIntent.current = null;
      setRemovalReadbackNeeded(false);
      setRemovalHistory(current => [created, ...current.filter(item => item.requestId !== created.requestId)]);
      setLatestRemovalSnapshot(created);
      setRemovalPage(0);
      setRemovalHasMore(false);
      setRemovalHistoryState('ready');
      setConfirmRemoval(false);
      setRemovalReason('');
      await refreshRemovalAfterCommand(owner, session, operationEpoch);
    } catch (cause) {
      if (!removalOperationIsOwned(owner, session, operationEpoch)) return;
      const status = statusOf(cause);
      handleCurrentRemovalFailure(
        cause,
        'The removal request could not be created.',
        'The removal request outcome could not be confirmed. Check the current status before trying again.'
      );
      if (status === 409 && removalOperationIsOwned(owner, session, operationEpoch)) {
        const message = getErrorMessage(cause, 'The property changed before this request was sent.');
        await refreshRemovalAfterCommand(owner, session, operationEpoch);
        if (removalOperationIsOwned(owner, session, operationEpoch)) setError(message);
      }
    } finally {
      if (removalOperationGate.current.finish(owner)) {
        removalBusyRef.current = false;
        setRemovalBusy(false);
      }
    }
  };
  const withdrawRemoval = async (request: RemovalRequestView) => {
    if (removalBusyRef.current || removalReadbackBusyRef.current) return;
    invalidateRemovalPageFocus();
    const operationEpoch = listingEpoch.current;
    const session = captureOperationsSession();
    const sessionIdentity = session?.identity ?? '';
    const owner = removalOperationGate.current.begin(
      session?.identity,
      `lessor-listing:${listingId}:removal-withdraw`,
      `${listingId}:${request.requestId}:${request.requestVersion}`,
      operationEpoch
    );
    if (!owner) return;
    removalBusyRef.current = true;
    setRemovalBusy(true);
    setError('');
    try {
      const intentKey = operationIntentKey([
        sessionIdentity,
        listingId,
        request.requestId,
        request.requestVersion
      ]);
      withdrawalIntent.current = operationIdForIntent(withdrawalIntent.current, intentKey);
      const withdrawn = await propertyRemovalService.withdraw(
        listingId, request, withdrawalIntent.current.id);
      if (!removalOperationIsOwned(owner, session, operationEpoch)) return;
      withdrawalIntent.current = null;
      setRemovalReadbackNeeded(false);
      setRemovalHistory(current => current.map(item => item.requestId === withdrawn.requestId ? withdrawn : item));
      setLatestRemovalSnapshot(current => current?.requestId === withdrawn.requestId ? withdrawn : current);
      setRemovalPage(0);
      setRemovalHasMore(false);
      setRemovalHistoryState('ready');
      await refreshRemovalAfterCommand(owner, session, operationEpoch);
    } catch (cause) {
      if (!removalOperationIsOwned(owner, session, operationEpoch)) return;
      const status = statusOf(cause);
      handleCurrentRemovalFailure(
        cause,
        'The removal request could not be withdrawn.',
        'The withdrawal outcome could not be confirmed. Check the current status before trying again.'
      );
      if (status === 409 && removalOperationIsOwned(owner, session, operationEpoch)) {
        const message = getErrorMessage(cause, 'The request changed before it could be withdrawn.');
        await refreshRemovalAfterCommand(owner, session, operationEpoch);
        if (removalOperationIsOwned(owner, session, operationEpoch)) setError(message);
      }
    } finally {
      if (removalOperationGate.current.finish(owner)) {
        removalBusyRef.current = false;
        setRemovalBusy(false);
      }
    }
  };

  if (loading) return <div className="flex min-h-48 items-center justify-center gap-2 text-slate-600"><LoaderCircle className="h-5 w-5 animate-spin"/>Opening property…</div>;
  if (!detail) return <LessorListingErrorState
    error={error || 'Unable to open this property.'}
    onRetry={() => { void load(); }}
    onBack={onBack}
  />;
  const property = detail.preview;
  const latestRemoval = latestRemovalSnapshot;
  const pendingRemoval = latestRemoval?.requestStatus === 'PENDING' ? latestRemoval : null;
  const removalState = lessorRemovalState(latestRemoval);
  const showListingChanges = showLessorListingChanges(detail.status, removalState);
  const canRequestRemoval = !removalReadbackNeeded
    && canRequestLessorRemoval(removalHistoryState, detail.status, removalState);
  const cover = property.media.find(item => item.cover && item.contentType.startsWith('image/')) || property.media.find(item => item.contentType.startsWith('image/'));
  return <section className="mx-auto flex w-full max-w-[430px] flex-col gap-4">
    <button className={SECONDARY} onClick={onBack}><ArrowLeft className="h-4 w-4"/>My Properties</button>
    <div className="mt-4 flex flex-wrap items-center justify-between gap-2">
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
    <div className="mt-2 overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm"><div className="aspect-[16/10] bg-slate-100">{cover?.url ? <img src={cover.url} alt="Property cover" className="h-full w-full object-cover"/> : <div className="flex h-full items-center justify-center text-slate-400"><Building2 className="h-10 w-10" aria-hidden="true"/></div>}</div>
      <div className="p-5"><p className="text-xl font-bold text-slate-950">{property.monthlyRent !== null ? MONEY.format(property.monthlyRent) : 'Rent unavailable'} <span className="text-sm font-normal text-slate-500">/ month</span></p><p className="mt-1 text-sm text-slate-600">Deposit: {property.securityDeposit !== null ? MONEY.format(property.securityDeposit) : 'Unavailable'}</p><div className="mt-5 grid gap-2 text-sm text-slate-700 sm:grid-cols-2"><span>Available: {property.availableFrom || 'Not specified'}</span>{property.totalAreaSqFt && <span>{property.totalAreaSqFt} sq ft</span>}{property.furnishingStatus && <span>{property.furnishingStatus.replace(/_/g, ' ').toLowerCase()}</span>}</div>{property.description && <p className="mt-5 whitespace-pre-wrap text-sm leading-relaxed text-slate-700">{property.description}</p>}</div></div>
    {property.media.length > 1 && <div className="mt-4 grid grid-cols-2 gap-2 sm:grid-cols-3">{property.media.filter(item => item !== cover && item.url).map(item => item.contentType.startsWith('image/') ? <img key={item.mediaId} src={item.url || undefined} alt="Additional property photo" loading="lazy" className="aspect-[4/3] w-full rounded-xl object-cover"/> : <video key={item.mediaId} controls preload="metadata" src={item.url || undefined} className="aspect-[4/3] w-full rounded-xl bg-slate-900"/>)}</div>}

    {showListingChanges && <section aria-labelledby="lessor-listing-changes-title"
      className="flex flex-col gap-3 rounded-2xl border border-slate-200 bg-white p-[18px] shadow-sm">
      <h2 id="lessor-listing-changes-title" className="text-base font-bold text-slate-950">Listing changes</h2>
      <p className="text-sm leading-relaxed text-slate-600">Update property details or pause the listing. These actions do not start a removal request.</p>
      <div className="flex flex-wrap gap-2.5">
        {detail.openRevisionStatus === 'DRAFT' && detail.openRevisionDraftId
          && <button className={SECONDARY} onClick={() => onOpenDraft(detail.openRevisionDraftId!)}>Continue property changes</button>}
        {detail.openRevisionStatus === 'CHANGES_REQUIRED'
          && <button disabled={busy} className={SECONDARY} onClick={() => { void startRevision(); }}>{busy ? 'Opening…' : 'Review requested changes'}</button>}
        {!detail.openRevisionDraftId && ['PUBLISHED', 'PAUSED', 'CHANGES_REQUIRED'].includes(detail.status)
          && <button disabled={busy} className={SECONDARY} onClick={() => { void startRevision(); }}>{busy ? 'Opening…' : detail.status === 'CHANGES_REQUIRED' ? 'Review changes' : 'Edit details for review'}</button>}
        {detail.status === 'PUBLISHED'
          && <button disabled={busy} className={SECONDARY} onClick={() => { void updateStatus('pause'); }}>Pause listing</button>}
        {detail.status === 'PAUSED'
          && <button disabled={busy} className={SECONDARY} onClick={() => { void updateStatus('resume-review'); }}>Request review to resume</button>}
      </div>
      {detail.openRevisionStatus === 'REVIEW'
        && <p role="status" className="rounded-xl bg-amber-50 px-4 py-3 text-sm text-amber-900">Your revised details have been submitted for review. The approved listing remains unchanged until approval.</p>}
    </section>}

    <section aria-labelledby="lessor-removal-title"
      className="flex flex-col gap-3 rounded-2xl border border-slate-200 bg-white p-[18px] shadow-sm">
      <h2 id="lessor-removal-title" className="text-base font-bold text-slate-950">Removal</h2>
      {removalHistoryState === 'loading' && <p role="status" className="flex items-center gap-2 text-sm text-slate-600">
        <LoaderCircle className="h-4 w-4 animate-spin motion-reduce:animate-none" />Checking removal status…
      </p>}
      {removalHistoryState === 'error' && <div role="alert" className="rounded-xl border border-rose-200 bg-rose-50 p-4 text-sm text-rose-900">
        <p className="font-semibold">Removal status could not be checked</p>
        <p className="mt-1">No removal request can be sent until the current status is known.</p>
        <button disabled={removalBusy} className={`${SECONDARY} mt-3`}
          onClick={() => { void loadRemovalHistory(removalPage); }}>Try again</button>
      </div>}
      {removalHistoryState === 'ready' && pendingRemoval && <div role="status" className="rounded-xl border border-amber-200 bg-amber-50 p-4 text-sm text-amber-950">
        <p className="font-semibold">Removal request pending</p>
        <p className="mt-1">Your listing stays in its current state until an Admin approves removal. Existing visits are not cancelled.</p>
        <p className="mt-2 text-xs text-amber-800">Requested {new Date(pendingRemoval.requestedAt).toLocaleString('en-IN')}</p>
        {pendingRemoval.ownerReason && <p className="mt-2 whitespace-pre-wrap text-xs">Your reason: {pendingRemoval.ownerReason}</p>}
        {pendingRemoval.permittedActions.includes('WITHDRAW') && <button
          disabled={removalBusy || removalReadbackNeeded || removalReadbackBusy}
          className={`${SECONDARY} mt-3`} onClick={() => { void withdrawRemoval(pendingRemoval); }}>
          {removalBusy ? 'Withdrawing…' : 'Withdraw request'}
        </button>}
      </div>}
      {removalHistoryState === 'ready' && latestRemoval?.requestStatus === 'WITHDRAWN' && <div role="status" className="rounded-xl border border-slate-200 bg-slate-50 p-4 text-sm text-slate-800">
        <p className="font-semibold">Removal request withdrawn</p>
        <p className="mt-1">Your listing is unchanged. You can make a new request if you still need removal.</p>
      </div>}
      {removalHistoryState === 'ready' && latestRemoval?.requestStatus === 'REJECTED' && <div role="status" className="rounded-xl border border-amber-200 bg-amber-50 p-4 text-sm text-amber-950">
        <p className="font-semibold">Removal request not approved</p>
        <p className="mt-1">Your listing is unchanged.</p>
        {latestRemoval.decisionReason && <p className="mt-2 whitespace-pre-wrap"><span className="font-semibold">Reason from Pathome:</span> {latestRemoval.decisionReason}</p>}
      </div>}
      {removalHistoryState === 'ready' && latestRemoval && removalState === 'approved' && <div role="status" className="rounded-xl border border-rose-200 bg-rose-50 p-4 text-sm text-rose-950">
        <p className="font-semibold">Removal approved</p>
        <p className="mt-1">Your listing is archived and no longer shown to renters. Existing visits are not cancelled.</p>
        {latestRemoval.decisionReason && <p className="mt-2 whitespace-pre-wrap">Reason: {latestRemoval.decisionReason}</p>}
      </div>}
      {removalHistoryState === 'ready' && !latestRemoval && canRequestRemoval && <div>
        <p className="text-sm leading-relaxed text-slate-600">Ask Pathome to remove this listing. An Admin reviews every request.</p>
        <button disabled={busy || removalBusy} className={`${DANGER} mt-3`}
          onClick={() => setConfirmRemoval(true)}>Request property removal</button>
      </div>}
      {removalHistoryState === 'ready' && !latestRemoval && !canRequestRemoval && <p className="text-sm text-slate-600">
        Removal requests are unavailable in the property’s current state.
      </p>}
      {removalHistoryState === 'ready' && latestRemoval && !pendingRemoval && removalState !== 'approved' && canRequestRemoval
        && <button disabled={busy || removalBusy} className={DANGER} onClick={() => setConfirmRemoval(true)}>
          Request property removal again
        </button>}
      {confirmRemoval && canRequestRemoval && <div className="rounded-xl border border-amber-200 bg-amber-50 p-4 text-sm text-amber-950">
        <p className="font-semibold">Request property removal?</p>
        <p className="mt-1">This does not archive the property immediately. Your listing stays in its current state while an Admin reviews the request. Existing visits are not cancelled.</p>
        <label htmlFor="lessor-removal-reason" className="mt-3 block text-xs font-semibold">Reason (optional)</label>
        <textarea id="lessor-removal-reason" value={removalReason} maxLength={1000}
          onChange={event => setRemovalReason(event.target.value)}
          className="mt-1 min-h-24 w-full rounded-lg border border-amber-200 bg-white p-3 text-base text-slate-900 outline-none focus:ring-2 focus:ring-amber-400"
          placeholder="Tell the review team why this property should be removed" />
        <div className="mt-3 flex flex-wrap gap-2">
          <button disabled={removalBusy || removalReadbackNeeded || removalReadbackBusy}
            className={DANGER} onClick={() => { void requestRemoval(); }}>
            {removalBusy ? 'Sending request…' : 'Send removal request'}
          </button>
          <button disabled={removalBusy} className={SECONDARY} onClick={() => { setConfirmRemoval(false); setRemovalReason(''); }}>Cancel</button>
        </div>
      </div>}
      {removalHistoryState !== 'loading' && removalHistory.length > 0 && <div className="rounded-xl border border-slate-200 bg-slate-50 p-4">
        <p className="text-xs font-bold uppercase tracking-wide text-slate-500">Removal history</p>
        <ol className="mt-2 divide-y divide-slate-200">
          {removalHistory.map(item => <li key={item.requestId} className="flex flex-wrap items-center justify-between gap-2 py-2 text-sm">
            <span className="font-semibold text-slate-800">{item.requestStatus.replace(/_/g, ' ').toLowerCase()}</span>
            <span className="text-xs text-slate-500">{new Date(item.requestedAt).toLocaleDateString('en-IN')}</span>
          </li>)}
        </ol>
      </div>}
      {removalHistoryState === 'ready' && (removalPage > 0 || removalHasMore)
        && <nav className="flex flex-wrap items-center justify-between gap-2" aria-label="Removal history pages">
          <button type="button" disabled={removalPage === 0 || removalBusy || removalReadbackBusy}
            className={SECONDARY}
            onClick={() => {
              setRemovalPage(current => Math.max(0, current - 1));
              void loadRemovalHistory(Math.max(0, removalPage - 1), undefined, undefined, 'previous');
            }}>Previous</button>
          <p ref={removalPageStatus} tabIndex={-1} aria-live="polite" className="text-sm text-slate-600">
            Page {removalPage + 1}
          </p>
          <button type="button" disabled={!removalHasMore || removalBusy || removalReadbackBusy}
            className={SECONDARY}
            onClick={() => {
              setRemovalPage(current => current + 1);
              void loadRemovalHistory(removalPage + 1, undefined, undefined, 'next');
            }}>Next</button>
        </nav>}
    </section>
    {error && <div role="alert" className="mt-5 rounded-xl border border-rose-200 bg-rose-50 p-4 text-sm text-rose-900">
      <p>{error}</p>
      {removalReadbackNeeded && <button type="button" disabled={removalReadbackBusy}
        className={`${SECONDARY} mt-3`}
        onClick={() => { void revalidateCurrentRemovalState(); }}>
        {removalReadbackBusy ? 'Checking current status…' : 'Check current status'}
      </button>}
    </div>}
  </section>;
}
