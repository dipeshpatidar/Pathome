import React, { useCallback, useEffect, useState } from 'react';
import { AlertCircle, CheckCircle2, LoaderCircle, RefreshCw, Wrench } from 'lucide-react';
import { boundedVisitPage, operationalVisitTime } from '../utils/visitExecutionPresentation';
import {
  operationsVisitRepairService,
  VisitRecommendationCandidate,
  VisitRecommendationView,
  VisitRepairItem
} from '../services/operationsVisitRepairService';

const actionClass = 'inline-flex min-h-11 items-center justify-center gap-2 rounded-xl border border-slate-300 bg-white px-4 text-xs font-semibold text-slate-800 transition-colors hover:bg-slate-50 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-700 disabled:cursor-wait disabled:opacity-60';
const displayTime = operationalVisitTime;

export const OperationsVisitRepairPanel: React.FC = () => {
  const [items, setItems] = useState<VisitRepairItem[]>([]);
  const [state, setState] = useState<'loading' | 'ready' | 'error'>('loading');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<number | null>(null);
  const [recommendations, setRecommendations] = useState<Record<number, VisitRecommendationView>>({});
  const [notice, setNotice] = useState<string | null>(null);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);

  const load = useCallback(async (signal?: AbortSignal) => {
    setState('loading');
    setError(null);
    try {
      const result = await operationsVisitRepairService.list(page, signal);
      if (signal?.aborted) return;
      if (page !== boundedVisitPage(page, result.totalPages)) { setPage(boundedVisitPage(page, result.totalPages)); return; }
      setItems(Array.isArray(result.items) ? result.items : []);
      setTotalPages(result.totalPages);
      setState('ready');
    } catch (cause) {
      if (signal?.aborted) return;
      setError(cause instanceof Error ? cause.message : 'The repair queue is unavailable.');
      setState('error');
    }
  }, [page]);

  useEffect(() => {
    const controller = new AbortController();
    void load(controller.signal);
    return () => controller.abort();
  }, [load]);

  const review = async (item: VisitRepairItem) => {
    setBusy(item.sessionId);
    setError(null);
    setNotice(null);
    try {
      const result = await operationsVisitRepairService.recommendations(item.sessionId, item.version);
      setRecommendations(current => ({ ...current, [item.sessionId]: result }));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not produce current safe visit options.');
    } finally { setBusy(null); }
  };

  const approve = async (sessionId: number, candidate: VisitRecommendationCandidate) => {
    const recommendation = recommendations[sessionId];
    if (!recommendation) return;
    setBusy(sessionId);
    setError(null);
    setNotice(null);
    try {
      await operationsVisitRepairService.approve(sessionId, candidate, recommendation.sessionVersion);
      setNotice(`Visit Session ${sessionId} was rescheduled. The tenant must confirm this proposed time.`);
      setRecommendations(current => { const next = { ...current }; delete next[sessionId]; return next; });
      await load();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'The option became stale. Refresh and review current options.');
    } finally { setBusy(null); }
  };

  return <section aria-labelledby="visit-repair-title" className="space-y-4">
    <div className="flex flex-wrap items-start justify-between gap-3">
      <div>
        <h2 id="visit-repair-title" className="flex items-center gap-2 text-base font-bold text-slate-950"><Wrench className="h-4 w-4 text-amber-700" aria-hidden="true" /> Visit repair queue</h2>
        <p className="mt-1 max-w-2xl text-sm leading-5 text-slate-600">These visit times cannot be honored as booked. Review fresh 2C-B scheduling options, then wait for tenant confirmation.</p>
      </div>
      <button type="button" onClick={() => void load()} disabled={state === 'loading'} className={actionClass}>
        {state === 'loading' ? <LoaderCircle className="h-4 w-4 animate-spin" aria-hidden="true" /> : <RefreshCw className="h-4 w-4" aria-hidden="true" />} Refresh queue
      </button>
    </div>
    {error && <div role="alert" className="flex gap-2 rounded-xl border border-rose-200 bg-rose-50 p-3 text-sm text-rose-900"><AlertCircle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" /><p>{error}</p></div>}
    {notice && <p role="status" className="flex items-start gap-2 rounded-xl border border-emerald-200 bg-emerald-50 p-3 text-sm text-emerald-900"><CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />{notice}</p>}
    {state === 'loading' && <p role="status" className="rounded-xl border border-slate-200 bg-white p-4 text-sm text-slate-600">Loading repair queue…</p>}
    {state === 'error' && <button type="button" onClick={() => void load()} className={actionClass}>Retry queue</button>}
    {state === 'ready' && items.length === 0 && <p role="status" className="rounded-xl border border-slate-200 bg-white p-4 text-sm text-slate-600">No visits currently require schedule repair.</p>}
    {state === 'ready' && items.map(item => {
      const result = recommendations[item.sessionId];
      const loading = busy === item.sessionId;
      return <article key={item.sessionId} className="min-w-0 rounded-2xl border border-slate-200 bg-white p-4 shadow-sm sm:p-5">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="min-w-0">
            <p className="text-sm font-semibold text-slate-950">Visit Session {item.sessionId} · {item.city}</p>
            <p className="mt-1 text-xs text-slate-600">Previous time {displayTime(item.previouslyScheduledAt, item.zoneId)} · assigned GE {item.assignedGroundExecutiveUserId ?? 'none'}</p>
            <p className="mt-1 text-xs text-amber-900">This previous time is no longer confirmed.</p>
          </div>
          {!result && <button type="button" disabled={loading} onClick={() => void review(item)} className={actionClass}>
            {loading ? <LoaderCircle className="h-4 w-4 animate-spin" aria-hidden="true" /> : null}Review safe options
          </button>}
        </div>
        {result && <div className="mt-4 space-y-3">
          {result.candidates.length === 0 ? <div className="rounded-xl border border-amber-200 bg-amber-50 p-3">
            <p className="text-sm font-semibold text-amber-950">No safe option is available right now.</p>
            <p className="mt-1 text-xs leading-5 text-amber-900">Keep the visit in the repair queue and review again after tenant or property availability changes.</p>
            {result.diagnostics?.length > 0 && <p className="mt-2 text-xs text-amber-900">Reason: {result.diagnostics[0].replace(/_/g, ' ').toLowerCase()}.</p>}
          </div> : result.candidates.map(candidate => <div key={`${candidate.groundExecutiveUserId}-${candidate.scheduledAt}`} className="flex min-w-0 flex-col gap-3 rounded-xl border border-slate-200 bg-slate-50 p-3 sm:flex-row sm:items-center sm:justify-between">
            <div className="min-w-0">
              <p className="text-sm font-semibold text-slate-900">{displayTime(candidate.scheduledAt, candidate.zoneId)} · GE {candidate.groundExecutiveUserId}</p>
              <p className="mt-1 text-xs text-slate-600">{candidate.durationMinutes} minutes · travel {candidate.travelConfidence.toLowerCase()}</p>
              {candidate.reasons?.length > 0 && <p className="mt-1 break-words text-[11px] text-slate-500">{candidate.reasons.slice(0, 3).map(reason => reason.replace(/_/g, ' ').toLowerCase()).join(' · ')}</p>}
            </div>
            <button type="button" disabled={loading} onClick={() => void approve(item.sessionId, candidate)} className={`${actionClass} shrink-0 bg-emerald-700 text-white hover:bg-emerald-800`}>Propose this time</button>
          </div>)}
          <button type="button" onClick={() => setRecommendations(current => { const next = { ...current }; delete next[item.sessionId]; return next; })} className={`min-h-11 rounded-lg px-3 text-xs font-semibold text-slate-700 underline ${actionClass}`}>Close options</button>
        </div>}
      </article>;
    })}
    {state === 'ready' && totalPages > 1 && <nav aria-label="Visit repair pages" className="flex items-center justify-between gap-3 text-sm text-slate-700">
      <button type="button" className={actionClass} disabled={page === 0} onClick={() => setPage(value => value - 1)}>Previous</button>
      <span>Page {page + 1} of {totalPages}</span>
      <button type="button" className={actionClass} disabled={page + 1 >= totalPages} onClick={() => setPage(value => value + 1)}>Next</button>
    </nav>}
  </section>;
};
