import React, { useCallback, useEffect, useState } from 'react';
import { AlertCircle, CheckCircle2, Clock3, LoaderCircle, MapPin, Phone, RefreshCw, ShieldCheck } from 'lucide-react';
import { UserProfile } from '../types';
import { boundedVisitPage, operationalVisitTime, visitStartFeedback } from '../utils/visitExecutionPresentation';
import { physicalFinishAvailable } from '../utils/visitOutcomePresentation';
import { GroundVisitOutcomePanel } from './GroundVisitOutcomePanel';
import {
  createVisitOperationId,
  GroundPendingVisitOutcome,
  GroundVisitSession,
  GroundVisitStartCodeStatus,
  GroundVisitTenantContact,
  visitExecutionService
} from '../services/visitExecutionService';

interface Props { user: UserProfile | null }
const buttonClass = 'inline-flex min-h-11 items-center justify-center gap-2 rounded-xl border border-slate-700 bg-slate-800 px-3.5 text-xs font-semibold text-slate-100 transition-colors hover:bg-slate-700 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-cyan-400 disabled:cursor-wait disabled:opacity-60';
const dateText = operationalVisitTime;

export const GroundVisitOperationsPanel: React.FC<Props> = ({ user }) => {
  const [sessions, setSessions] = useState<GroundVisitSession[]>([]);
  const [pendingOutcomes, setPendingOutcomes] = useState<GroundPendingVisitOutcome[]>([]);
  const [pendingState, setPendingState] = useState<'loading' | 'ready' | 'error'>('loading');
  const [pendingPage, setPendingPage] = useState(0);
  const [pendingTotalPages, setPendingTotalPages] = useState(0);
  const [openPending, setOpenPending] = useState<Record<number, boolean>>({});
  const [state, setState] = useState<'loading' | 'ready' | 'error'>('loading');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<number | null>(null);
  const [contacts, setContacts] = useState<Record<number, GroundVisitTenantContact>>({});
  const [contactOutcomes, setContactOutcomes] = useState<Record<number, string>>({});
  const [contactEtaInputs, setContactEtaInputs] = useState<Record<number, string>>({});
  const [contactOperationIds, setContactOperationIds] = useState<Record<number, string>>({});
  const [codeStatus, setCodeStatus] = useState<Record<number, GroundVisitStartCodeStatus>>({});
  const [codeInputs, setCodeInputs] = useState<Record<number, string>>({});
  const [startOperationIds, setStartOperationIds] = useState<Record<number, string>>({});
  const [moreTimeOperationIds, setMoreTimeOperationIds] = useState<Record<number, string>>({});
  const [feedback, setFeedback] = useState<Record<number, string>>({});
  const [startNotice, setStartNotice] = useState<string | null>(null);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);

  const load = useCallback(async (signal?: AbortSignal) => {
    if (!user?.id) { setSessions([]); setState('ready'); return; }
    setState('loading');
    setError(null);
    try {
      const result = await visitExecutionService.listGround(page, signal);
      if (signal?.aborted) return;
      if (page !== boundedVisitPage(page, result.totalPages)) { setPage(boundedVisitPage(page, result.totalPages)); return; }
      setSessions(Array.isArray(result.sessions) ? result.sessions : []);
      setTotalPages(result.totalPages);
      setState('ready');
    } catch (cause) {
      if (signal?.aborted) return;
      setError(cause instanceof Error ? cause.message : 'Could not load assigned visits.');
      setState('error');
    }
  }, [user?.id, page]);

  const loadPending = useCallback(async (signal?: AbortSignal) => {
    if (!user?.id) { setPendingOutcomes([]); setPendingState('ready'); return; }
    setPendingState('loading');
    try {
      const result = await visitExecutionService.listPendingOutcomes(pendingPage, signal);
      if (signal?.aborted) return;
      setPendingOutcomes(Array.isArray(result.content) ? result.content : []);
      setPendingTotalPages(result.totalPages);
      setPendingState('ready');
    } catch {
      if (!signal?.aborted) setPendingState('error');
    }
  }, [user?.id, pendingPage]);

  useEffect(() => {
    const controller = new AbortController();
    setContacts({});
    setContactOutcomes({});
    setContactEtaInputs({});
    setContactOperationIds({});
    setCodeStatus({});
    setCodeInputs({});
    setStartOperationIds({});
    setMoreTimeOperationIds({});
    setFeedback({});
    setStartNotice(null);
    setOpenPending({});
    void load(controller.signal);
    void loadPending(controller.signal);
    return () => controller.abort();
  }, [load, loadPending]);

  const run = async (sessionId: number, action: () => Promise<unknown>, success: string): Promise<boolean> => {
    setBusy(sessionId);
    setError(null);
    try {
      await action();
      setFeedback(current => ({ ...current, [sessionId]: success }));
      await load();
      await loadPending();
      return true;
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Visit update failed. Please retry.');
      return false;
    } finally {
      setBusy(null);
    }
  };

  const refreshBoth = async () => { await load(); await loadPending(); };

  const requestMoreTime = async (sessionId: number) => {
    const operationId = moreTimeOperationIds[sessionId] || createVisitOperationId();
    setMoreTimeOperationIds(current => ({ ...current, [sessionId]: operationId }));
    const succeeded = await run(sessionId, () => visitExecutionService.needMoreTime(sessionId, 30, operationId),
      'Additional 30 minutes reported. Downstream visits requiring recovery were sent to Operations.');
    if (succeeded) setMoreTimeOperationIds(current => { const next = { ...current }; delete next[sessionId]; return next; });
  };

  const recordContact = async (sessionId: number, outcome: string, eta?: string) => {
    const operationId = contactOperationIds[sessionId] || createVisitOperationId();
    setContactOperationIds(current => ({ ...current, [sessionId]: operationId }));
    setBusy(sessionId);
    setError(null);
    try {
      const result = await visitExecutionService.recordContact(sessionId, outcome, eta, operationId);
      setFeedback(current => ({ ...current, [sessionId]: outcome === 'TENANT_ACCEPTED_RESCHEDULE'
        ? result.resultCode === 'RESCHEDULE_CONFIRMED'
          ? `The requested time is confirmed for ${dateText(result.scheduledAt, result.zoneId)}.`
          : 'Consent is recorded. The requested time needs Operations review and is not confirmed.'
        : 'Tenant contact result recorded.' }));
      setContactOperationIds(current => { const next = { ...current }; delete next[sessionId]; return next; });
      await load();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Visit update failed. Please retry.');
    } finally { setBusy(null); }
  };

  const showContact = async (sessionId: number) => {
    setBusy(sessionId);
    setError(null);
    try {
      const contact = await visitExecutionService.getGroundContact(sessionId);
      setContacts(current => ({ ...current, [sessionId]: contact }));
      setContactOutcomes(current => ({ ...current, [sessionId]: current[sessionId] || 'NO_ANSWER' }));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Tenant contact is unavailable.');
    } finally { setBusy(null); }
  };

  const loadCodeStatus = async (sessionId: number) => {
    setBusy(sessionId);
    try {
      const status = await visitExecutionService.getGroundCodeStatus(sessionId);
      setCodeStatus(current => ({ ...current, [sessionId]: status }));
      setFeedback(current => ({ ...current, [sessionId]: status.challengeAvailable
        ? `Current code generation ${status.generation} · expires ${dateText(status.expiresAt, sessions.find(item => item.sessionId === sessionId)?.zoneId)}`
        : 'Ask the tenant to request a start code in Pathome when ready.' }));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Code status is unavailable.');
    } finally { setBusy(null); }
  };

  const start = async (sessionId: number) => {
    const status = codeStatus[sessionId];
    if (!status?.challengeAvailable) {
      await loadCodeStatus(sessionId);
      return;
    }
    const code = (codeInputs[sessionId] || '').trim();
    if (!/^\d{6}$/.test(code)) {
      setFeedback(current => ({ ...current, [sessionId]: 'Enter the six-digit code shown to the tenant.' }));
      return;
    }
    const operationId = startOperationIds[sessionId] || createVisitOperationId();
    setStartOperationIds(current => ({ ...current, [sessionId]: operationId }));
    setBusy(sessionId);
    try {
      const result = await visitExecutionService.start(sessionId, status.generation, code, operationId);
      if (result.resultCode === 'INVALID_CODE' || result.resultCode === 'TOO_MANY_ATTEMPTS') {
        setFeedback(current => ({ ...current, [sessionId]: result.resultCode === 'INVALID_CODE'
          ? 'That code did not match. Check with the tenant before trying again.'
          : 'Too many attempts. The tenant can request a new code after a short wait.' }));
        if (result.resultCode === 'TOO_MANY_ATTEMPTS') setCodeStatus(current => ({
          ...current, [sessionId]: { ...status, lockedUntil: new Date(Date.now() + 30000).toISOString() }
        }));
      } else {
        const message = visitStartFeedback(result.status, result.resultCode);
        setFeedback(current => ({ ...current, [sessionId]: message }));
        setStartNotice(result.status === 'STARTED' ? null : message);
        setCodeInputs(current => ({ ...current, [sessionId]: '' }));
        await load();
      }
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Visit start failed. Confirm the latest code and retry.');
    } finally { setBusy(null); }
  };

  return <section aria-labelledby="assigned-visit-title" className="space-y-4">
    <div className="flex flex-wrap items-center justify-between gap-3">
      <div>
        <h2 id="assigned-visit-title" className="flex items-center gap-2 text-base font-bold text-white">
          <MapPin className="h-4 w-4 text-indigo-300" aria-hidden="true" /> Assigned visits
        </h2>
        <p className="mt-1 text-xs leading-5 text-slate-400">Arrival is an operational report. A tenant code is required to start a visit.</p>
      </div>
      <button type="button" onClick={() => void refreshBoth()} disabled={state === 'loading' || pendingState === 'loading'} className={buttonClass}>
        {state === 'loading' ? <LoaderCircle className="h-4 w-4 animate-spin" aria-hidden="true" /> : <RefreshCw className="h-4 w-4" aria-hidden="true" />}
        Refresh visits
      </button>
    </div>
    {error && <div role="alert" className="flex gap-2 rounded-xl border border-rose-500/30 bg-rose-950/30 p-3 text-sm text-rose-200"><AlertCircle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" /><p>{error}</p></div>}
    {startNotice && <p role="status" aria-live="polite" className="rounded-xl border border-amber-500/30 bg-amber-950/30 p-3 text-sm text-amber-100">{startNotice}</p>}
    <section aria-labelledby="pending-outcomes-title" className="space-y-3 rounded-2xl border border-amber-500/20 bg-slate-900/70 p-4">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div>
          <h3 id="pending-outcomes-title" className="text-sm font-bold text-white">Pending outcomes</h3>
          <p className="mt-1 text-xs text-slate-400">Finished visits assigned to you with details still to submit.</p>
        </div>
        {pendingState === 'loading' && <span role="status" className="text-xs text-slate-400">Loading…</span>}
      </div>
      {pendingState === 'error' && <div className="flex flex-wrap items-center justify-between gap-2 text-sm text-rose-200">
        <span>Pending reports could not be loaded.</span><button type="button" onClick={() => void loadPending()} className={buttonClass}>Retry</button>
      </div>}
      {pendingState === 'ready' && pendingOutcomes.length === 0 && <p role="status" className="text-sm text-slate-300">No finished visits need outcome details.</p>}
      {pendingState === 'ready' && pendingOutcomes.map(item => <article key={item.sessionId} className="min-w-0 rounded-xl border border-slate-700 bg-slate-950/50 p-3">
        <div className="flex min-w-0 flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <div className="min-w-0">
            <p className="font-semibold text-white">Visit {item.sessionId}{item.city ? ` · ${item.city}` : ''}</p>
            <p className="mt-1 text-xs text-slate-300">Finished {dateText(item.finishedAt || item.scheduledAt, null)}</p>
            <p className="mt-1 text-xs text-amber-200">{item.pendingPropertyCount} of {item.totalPropertyCount} properties still need details</p>
          </div>
          <button type="button" className={buttonClass} aria-expanded={Boolean(openPending[item.sessionId])}
            onClick={() => setOpenPending(current => ({ ...current, [item.sessionId]: !current[item.sessionId] }))}>
            {openPending[item.sessionId] ? 'Close report' : 'Complete report'}
          </button>
        </div>
        {openPending[item.sessionId] && <div className="mt-3"><GroundVisitOutcomePanel key={`${user?.id ?? 'signed-out'}-${item.sessionId}`} sessionId={item.sessionId} compact onFinalized={() => void refreshBoth()} /></div>}
      </article>)}
      {pendingState === 'ready' && pendingTotalPages > 1 && <nav aria-label="Pending outcome pages" className="flex items-center justify-between gap-3 text-sm text-slate-200">
        <button type="button" className={buttonClass} disabled={pendingPage === 0} onClick={() => setPendingPage(value => value - 1)}>Previous</button>
        <span>Page {pendingPage + 1} of {pendingTotalPages}</span>
        <button type="button" className={buttonClass} disabled={pendingPage + 1 >= pendingTotalPages} onClick={() => setPendingPage(value => value + 1)}>Next</button>
      </nav>}
    </section>
    {state === 'loading' && <div role="status" className="rounded-2xl border border-slate-800 bg-slate-900 p-5 text-sm text-slate-300">Loading assigned visits…</div>}
    {state === 'ready' && sessions.length === 0 && <div role="status" className="rounded-2xl border border-slate-800 bg-slate-900 p-5 text-sm text-slate-300">No visits are assigned to you right now.</div>}
    {state === 'error' && <button type="button" onClick={() => void load()} className={buttonClass}>Retry loading visits</button>}
    {state === 'ready' && sessions.map(visit => {
      const firstStop = visit.items?.[0];
      const isStarted = physicalFinishAvailable(visit.status);
      const inProgress = busy === visit.sessionId;
      const contact = contacts[visit.sessionId];
      const phoneHref = contact?.tenantPhone?.trim().replace(/[^+\d]/g, '');
      return <article key={visit.sessionId} className="min-w-0 rounded-2xl border border-slate-800 bg-slate-900/80 p-4 sm:p-5">
        <div className="flex min-w-0 flex-col gap-4">
          <div className="flex min-w-0 flex-wrap items-start justify-between gap-3">
            <div className="min-w-0">
              <div className="flex flex-wrap items-center gap-2">
                <span className="rounded-full border border-indigo-400/30 bg-indigo-500/10 px-2.5 py-1 text-[11px] font-bold text-indigo-200">Session {visit.sessionId}</span>
                <span className={`rounded-full px-2.5 py-1 text-[11px] font-bold ${isStarted ? 'bg-emerald-500/10 text-emerald-200' : 'bg-slate-800 text-slate-200'}`}>{visit.status.replace(/_/g, ' ')}</span>
                {visit.overPlannedTime && <span className="rounded-full border border-amber-400/30 bg-amber-500/10 px-2.5 py-1 text-[11px] font-bold text-amber-200">Over planned time</span>}
              </div>
              <h3 className="mt-3 break-words text-base font-semibold text-white">{firstStop?.title || 'Visit itinerary'}</h3>
              <p className="mt-1 break-words text-sm text-slate-300">{[firstStop?.sector, firstStop?.city || visit.city].filter(Boolean).join(', ')}</p>
              <p className="mt-2 flex items-center gap-2 text-xs text-slate-400"><Clock3 className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />Scheduled {dateText(visit.scheduledAt, visit.zoneId)}</p>
              {visit.arrivedAt && <p className="mt-1 flex items-center gap-2 text-xs text-emerald-200"><CheckCircle2 className="h-3.5 w-3.5" aria-hidden="true" />Arrival reported {dateText(visit.arrivedAt, visit.zoneId)}</p>}
              {isStarted && <p className="mt-1 text-xs text-slate-300">Started {dateText(visit.startedAt, visit.zoneId)} · expected end {dateText(visit.expectedEndAt, visit.zoneId)}</p>}
              {visit.tenantEtaAt && <p className="mt-1 text-xs text-slate-300">Tenant confirmed ETA {dateText(visit.tenantEtaAt, visit.zoneId)}</p>}
              {visit.repairState !== 'NONE' && <p className="mt-2 rounded-lg border border-amber-500/30 bg-amber-950/20 p-2 text-xs text-amber-100">This visit needs Operations review. The previous time is not confirmed.</p>}
              {visit.tenantConfirmationState === 'PENDING' && <p className="mt-2 rounded-lg border border-sky-500/30 bg-sky-950/20 p-2 text-xs text-sky-100">The tenant must confirm the proposed time before the visit can start.</p>}
            </div>
          </div>

          <div className="flex flex-wrap gap-2">
            {!isStarted && !visit.arrivedAt && <button type="button" disabled={inProgress} onClick={() => void run(visit.sessionId, () => visitExecutionService.markArrived(visit.sessionId), 'Arrival reported. This does not prove location or tenant attendance.')} className={buttonClass}>Mark arrived</button>}
            {!isStarted && <>
              <button type="button" disabled={inProgress} onClick={() => void showContact(visit.sessionId)} className={buttonClass}><Phone className="h-4 w-4" aria-hidden="true" />Show tenant contact</button>
              <button type="button" disabled={inProgress} onClick={() => void loadCodeStatus(visit.sessionId)} className={buttonClass}><ShieldCheck className="h-4 w-4" aria-hidden="true" />Check code status</button>
              {visit.arrivedAt && <button type="button" disabled={inProgress} onClick={() => void run(visit.sessionId, () => visitExecutionService.markProvisionalNoShow(visit.sessionId), 'Provisional no-show recorded with a dispute window.')} className={buttonClass}>Provisional no-show</button>}
            </>}
          {isStarted && <>
              <button type="button" disabled={inProgress} onClick={() => void requestMoreTime(visit.sessionId)} className={buttonClass}>Need 30 more minutes</button>
              <button type="button" disabled={inProgress} onClick={() => void run(visit.sessionId, () => visitExecutionService.finish(visit.sessionId), 'Visit finished.')} className={buttonClass}>Finish visit</button>
            </>}
          </div>

          {isStarted && <GroundVisitOutcomePanel key={`${user?.id ?? 'signed-out'}-${visit.sessionId}`} sessionId={visit.sessionId} onFinalized={() => void refreshBoth()} />}

          {contact && <div className="rounded-xl border border-slate-700 bg-slate-950/50 p-3 text-sm text-slate-200">
            <p className="font-semibold">{contact.tenantName || 'Tenant'}</p>
            {phoneHref ? <a className="mt-1 inline-flex min-h-11 items-center gap-2 text-cyan-300 underline underline-offset-2 focus-visible:outline focus-visible:outline-2 focus-visible:outline-cyan-400" href={`tel:${phoneHref}`}><Phone className="h-4 w-4" aria-hidden="true" />{contact.tenantPhone}</a> : <p className="mt-1 text-xs text-slate-400">No contact number is available.</p>}
          </div>}

          {contact && !isStarted && <div className="grid min-w-0 gap-3 rounded-xl border border-slate-700 bg-slate-950/50 p-3 sm:grid-cols-[minmax(0,1fr)_minmax(0,1fr)_auto] sm:items-end">
            <label className="min-w-0 text-xs font-medium text-slate-300">Call outcome
              <select value={contactOutcomes[visit.sessionId] || 'NO_ANSWER'}
                onChange={event => setContactOutcomes(current => ({ ...current, [visit.sessionId]: event.target.value }))}
                className="mt-1 min-h-11 w-full rounded-lg border border-slate-700 bg-slate-900 px-3 text-base text-white focus-visible:outline focus-visible:outline-2 focus-visible:outline-cyan-400">
                <option value="NO_ANSWER">No answer</option><option value="BUSY">Tenant busy</option>
                <option value="CONNECTED">Connected</option><option value="TENANT_CONFIRMED">Tenant confirmed time</option>
                <option value="TENANT_ACCEPTED_RESCHEDULE">Tenant agreed to reschedule</option>
                <option value="TENANT_DECLINED_RESCHEDULE">Tenant declined reschedule</option>
              </select>
            </label>
            <label className="min-w-0 text-xs font-medium text-slate-300">Tenant ETA / agreed time
              <input type="datetime-local" value={contactEtaInputs[visit.sessionId] || ''}
                onChange={event => setContactEtaInputs(current => ({ ...current, [visit.sessionId]: event.target.value }))}
                className="mt-1 min-h-11 w-full rounded-lg border border-slate-700 bg-slate-900 px-3 text-base text-white focus-visible:outline focus-visible:outline-2 focus-visible:outline-cyan-400" />
            </label>
            <button type="button" disabled={inProgress} onClick={() => {
              const outcome = contactOutcomes[visit.sessionId] || 'NO_ANSWER';
              const localEta = contactEtaInputs[visit.sessionId];
              const needsEta = outcome === 'TENANT_CONFIRMED' || outcome === 'TENANT_ACCEPTED_RESCHEDULE';
              if (needsEta && !localEta) { setError('Enter the tenant-confirmed ETA before recording this result.'); return; }
              const eta = localEta ? new Date(localEta) : null;
              if (eta && Number.isNaN(eta.getTime())) { setError('Enter a valid tenant ETA.'); return; }
              void recordContact(visit.sessionId, outcome, eta?.toISOString());
            }} className={buttonClass}>Record call result</button>
          </div>}

          {codeStatus[visit.sessionId]?.challengeAvailable && !isStarted && <div className="flex min-w-0 flex-col gap-2 rounded-xl border border-slate-700 bg-slate-950/50 p-3 sm:flex-row sm:items-end">
            <label className="min-w-0 flex-1 text-xs font-medium text-slate-300">Tenant start code · generation {codeStatus[visit.sessionId].generation}
              <input inputMode="numeric" autoComplete="one-time-code" maxLength={6} value={codeInputs[visit.sessionId] || ''}
                onChange={event => setCodeInputs(current => ({ ...current, [visit.sessionId]: event.target.value.replace(/\D/g, '').slice(0, 6) }))}
                className="mt-1 min-h-11 w-full rounded-lg border border-slate-700 bg-slate-900 px-3 font-mono text-base tracking-[0.3em] text-white focus-visible:outline focus-visible:outline-2 focus-visible:outline-cyan-400" />
            </label>
            <button type="button" disabled={inProgress || Boolean(codeStatus[visit.sessionId].lockedUntil && new Date(codeStatus[visit.sessionId].lockedUntil!).getTime() > Date.now())}
              onClick={() => void start(visit.sessionId)} className={buttonClass}>{inProgress ? <LoaderCircle className="h-4 w-4 animate-spin" aria-hidden="true" /> : <ShieldCheck className="h-4 w-4" aria-hidden="true" />}Verify and start</button>
          </div>}
          {feedback[visit.sessionId] && <p role="status" aria-live="polite" className="text-xs leading-5 text-slate-300">{feedback[visit.sessionId]}</p>}
        </div>
      </article>;
    })}
    {state === 'ready' && totalPages > 1 && <nav aria-label="Assigned visit pages" className="flex items-center justify-between gap-3 text-sm text-slate-200">
      <button type="button" className={buttonClass} disabled={page === 0} onClick={() => setPage(value => value - 1)}>Previous</button>
      <span>Page {page + 1} of {totalPages}</span>
      <button type="button" className={buttonClass} disabled={page + 1 >= totalPages} onClick={() => setPage(value => value + 1)}>Next</button>
    </nav>}
  </section>;
};
