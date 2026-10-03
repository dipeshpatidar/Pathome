import React, { useCallback, useEffect, useState } from 'react';
import { AlertCircle, LoaderCircle, RefreshCw } from 'lucide-react';
import { ApiRequestError } from '../services/apiError';
import {
  CorrectOperationsOutcomeCommand,
  OperationsOutcomeDetail,
  OperationsOutcomeItem,
  VisitOutcomeException,
  visitExecutionService
} from '../services/visitExecutionService';
import { createVisitOperationId } from '../services/visitExecutionService';
import { operationalVisitTime } from '../utils/visitExecutionPresentation';

const buttonClass = 'inline-flex min-h-11 items-center justify-center rounded-xl border border-slate-700 bg-slate-800 px-3.5 py-2 text-sm font-semibold text-slate-100 hover:bg-slate-700 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-cyan-400 disabled:cursor-wait disabled:opacity-55';
const fieldClass = 'mt-1 min-h-11 w-full rounded-lg border border-slate-700 bg-slate-900 px-3 text-base text-white focus-visible:outline focus-visible:outline-2 focus-visible:outline-cyan-400';
type Draft = { outcome: 'VISITED' | 'SKIPPED'; skipReason: OperationsOutcomeItem['skipReason']; privateNote: string; correctionReason: string };

const outcomeLabel = (outcome: string): string => outcome === 'VISITED' ? 'Viewed'
  : outcome === 'SKIPPED' ? 'Not viewed' : 'Pending';
const reasonLabel = (reason: string | null): string => ({
  PROPERTY_UNAVAILABLE: 'Property unavailable', ACCESS_DENIED: 'Access was not available',
  TENANT_DECLINED: 'Tenant chose not to view', TENANT_LEFT_EARLY: 'Visit ended early',
  PROPERTY_MISMATCH: 'Property details did not match', OTHER: 'Other'
}[reason || ''] || 'No reason recorded');
const newDraft = (item: OperationsOutcomeItem): Draft => ({
  outcome: item.outcome === 'VISITED' ? 'SKIPPED' : 'VISITED',
  skipReason: item.outcome === 'SKIPPED' ? item.skipReason : null,
  privateNote: item.privateNote || '',
  correctionReason: ''
});

export const OperationsVisitOutcomePanel: React.FC = () => {
  const [items, setItems] = useState<VisitOutcomeException[]>([]);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [queueState, setQueueState] = useState<'loading' | 'ready' | 'error'>('loading');
  const [expandedId, setExpandedId] = useState<number | null>(null);
  const [detail, setDetail] = useState<OperationsOutcomeDetail | null>(null);
  const [detailState, setDetailState] = useState<'loading' | 'ready' | 'error'>('ready');
  const [drafts, setDrafts] = useState<Record<number, Draft>>({});
  const [confirmItemId, setConfirmItemId] = useState<number | null>(null);
  const [busyItemId, setBusyItemId] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const loadQueue = useCallback(async (signal?: AbortSignal) => {
    setQueueState('loading');
    try {
      const result = await visitExecutionService.listOutcomeExceptions(page, signal);
      if (signal?.aborted) return;
      if (page >= result.totalPages && result.totalPages > 0) { setPage(result.totalPages - 1); return; }
      setItems(Array.isArray(result.content) ? result.content : []);
      setTotalPages(result.totalPages);
      setQueueState('ready');
    } catch {
      if (!signal?.aborted) setQueueState('error');
    }
  }, [page]);

  useEffect(() => {
    const controller = new AbortController();
    void loadQueue(controller.signal);
    return () => controller.abort();
  }, [loadQueue]);

  const openDetail = async (sessionId: number) => {
    if (expandedId === sessionId) { setExpandedId(null); setDetail(null); return; }
    setExpandedId(sessionId);
    setDetail(null);
    setDetailState('loading');
    setError(null);
    try {
      const result = await visitExecutionService.getOperationsOutcome(sessionId);
      setDetail(result);
      setDrafts(Object.fromEntries(result.properties.map(item => [item.itemId, newDraft(item)])));
      setDetailState('ready');
    } catch (cause) {
      setDetailState('error');
      setError(cause instanceof ApiRequestError && cause.status === 404
        ? 'This visit report is no longer available. Reload the exception list.'
        : 'Could not load this visit report. Retry to refresh the latest details.');
    }
  };

  const refreshExpanded = async () => {
    if (expandedId === null) return;
    setDetailState('loading');
    try {
      const latest = await visitExecutionService.getOperationsOutcome(expandedId);
      setDetail(latest);
      setDrafts(Object.fromEntries(latest.properties.map(item => [item.itemId, newDraft(item)])));
      setConfirmItemId(null);
      setDetailState('ready');
    } catch {
      setDetailState('error');
      setError('The report changed or is unavailable. Reload the exception list and open the visit again.');
    }
  };

  const saveCorrection = async (item: OperationsOutcomeItem) => {
    if (!detail) return;
    const draft = drafts[item.itemId] || newDraft(item);
    if (draft.correctionReason.trim().length < 8 || draft.correctionReason.trim().length > 300
        || (draft.outcome === 'SKIPPED' && (!draft.skipReason
          || (draft.skipReason === 'OTHER' && !draft.privateNote.trim())))) return;
    setBusyItemId(item.itemId);
    setError(null);
    setNotice(null);
    const command: CorrectOperationsOutcomeCommand = {
      itemId: item.itemId,
      outcome: draft.outcome,
      skipReason: draft.outcome === 'SKIPPED' ? draft.skipReason : null,
      privateNote: draft.outcome === 'SKIPPED' && draft.skipReason === 'OTHER' ? draft.privateNote.trim() : null,
      correctionReason: draft.correctionReason.trim(),
      expectedReportVersion: detail.reportVersion,
      expectedItemVersion: item.version,
      operationId: createVisitOperationId()
    };
    try {
      const updated = await visitExecutionService.correctOperationsOutcome(detail.sessionId, command);
      setDetail(updated);
      setDrafts(Object.fromEntries(updated.properties.map(row => [row.itemId, newDraft(row)])));
      setConfirmItemId(null);
      setNotice('Correction saved to the visit audit history.');
      await loadQueue();
    } catch (cause) {
      if (cause instanceof ApiRequestError && cause.status === 409) {
        setError('The report changed while you were reviewing it. The latest version has been loaded; review the proposed correction again.');
        await refreshExpanded();
      } else if (cause instanceof ApiRequestError && cause.status === 403) {
        setError('This account no longer has permission to correct visit outcomes.');
      } else {
        setError('The correction was not saved. Check your connection and retry.');
      }
    } finally { setBusyItemId(null); }
  };

  return <section aria-labelledby="visit-outcome-exceptions-title" className="space-y-4 rounded-2xl border border-slate-800 bg-slate-900/80 p-4 sm:p-5">
    <header className="flex flex-wrap items-start justify-between gap-3">
      <div className="min-w-0">
        <h2 id="visit-outcome-exceptions-title" className="text-base font-bold text-white">Visit outcome exceptions</h2>
        <p className="mt-1 text-xs leading-5 text-slate-400">Incomplete reports appear here after the GE completion window. Reports with no properties viewed are also listed for review.</p>
      </div>
      <button type="button" disabled={queueState === 'loading'} onClick={() => void loadQueue()} className={buttonClass}>
        {queueState === 'loading' ? <LoaderCircle className="h-4 w-4 animate-spin" aria-hidden="true" /> : <RefreshCw className="h-4 w-4" aria-hidden="true" />}Refresh
      </button>
    </header>
    {notice && <p role="status" className="rounded-lg border border-emerald-500/25 bg-emerald-950/20 p-3 text-sm text-emerald-100">{notice}</p>}
    {error && <div role="alert" className="flex items-start gap-2 rounded-lg border border-rose-500/30 bg-rose-950/25 p-3 text-sm text-rose-100"><AlertCircle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" /><p>{error}</p></div>}
    {queueState === 'loading' && <p role="status" className="text-sm text-slate-300">Loading visit exceptions…</p>}
    {queueState === 'error' && <p role="alert" className="text-sm text-rose-200">Visit exceptions could not be loaded. Check your connection and retry.</p>}
    {queueState === 'ready' && items.length === 0 && <p role="status" className="rounded-xl border border-slate-700 bg-slate-950/50 p-4 text-sm text-slate-300">No visit outcome exceptions need review.</p>}
    {queueState === 'ready' && <div className="space-y-3">{items.map(item => {
      const noPropertiesViewed = item.reportState === 'FINALIZED' && item.visitedCount === 0;
      return <article key={item.sessionId} className="min-w-0 rounded-xl border border-slate-700 bg-slate-950/60 p-3 sm:p-4">
        <div className="flex min-w-0 flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <div className="min-w-0">
            <p className="font-semibold text-white">Visit {item.sessionId}{item.city ? ` · ${item.city}` : ''}</p>
            <p className="mt-1 text-xs text-slate-300">Finished {operationalVisitTime(item.finishedAt)}</p>
            <p className="mt-1 text-xs text-amber-200">{noPropertiesViewed ? 'No properties viewed · review outcome' : `${item.unrecordedCount} of ${item.itemCount} properties need details`}</p>
            {item.overdueSince && <p className="mt-1 text-xs font-semibold text-rose-200">Overdue since {operationalVisitTime(item.overdueSince)}</p>}
          </div>
          <button type="button" className={buttonClass} aria-expanded={expandedId === item.sessionId}
            onClick={() => void openDetail(item.sessionId)}>{expandedId === item.sessionId ? 'Close report' : 'Review report'}</button>
        </div>
        {expandedId === item.sessionId && <div className="mt-4 border-t border-slate-800 pt-4">
          {detailState === 'loading' && <p role="status" className="text-sm text-slate-300">Loading report details…</p>}
          {detailState === 'error' && <button type="button" onClick={() => void refreshExpanded()} className={buttonClass}>Retry loading report</button>}
          {detailState === 'ready' && detail?.sessionId === item.sessionId && <>
            <div className="flex flex-wrap items-start justify-between gap-3">
              <div><p className="text-sm font-semibold text-white">{detail.reportState === 'FINALIZED' ? 'Finalized report' : 'Incomplete report'}</p>
                <p className="mt-1 text-xs text-slate-400">Assigned GE account · {detail.currentGroundExecutiveUserId ?? 'Unassigned'} · {detail.city}{detail.locality ? ` · ${detail.locality}` : ''}</p>
                <p className="mt-1 text-xs text-slate-400">{detail.viewedProperties} viewed · {detail.pendingProperties} pending · {detail.totalProperties} total</p></div>
              <button type="button" onClick={() => void refreshExpanded()} className={buttonClass}>Refresh report</button>
            </div>
            {detail.properties.map(property => {
              const draft = drafts[property.itemId] || newDraft(property);
              const reviewing = confirmItemId === property.itemId;
              return <article key={property.itemId} className="mt-3 min-w-0 rounded-xl border border-slate-700 bg-slate-900/70 p-3">
                <div className="flex min-w-0 flex-col gap-2 sm:flex-row sm:items-start sm:justify-between">
                  <div className="min-w-0"><p className="text-xs text-slate-400">Stop {property.position} · {outcomeLabel(property.outcome)}</p>
                    <h3 className="mt-1 break-words text-sm font-semibold text-white">{property.title}</h3>
                    <p className="mt-1 break-words text-xs text-slate-300">{[property.address, property.sector, property.city].filter(Boolean).join(' · ')}</p>
                    {property.outcome === 'SKIPPED' && <p className="mt-1 text-xs text-slate-300">{reasonLabel(property.skipReason)}{property.privateNote ? ` · Internal note: ${property.privateNote}` : ''}</p>}
                  </div>
                  <button type="button" className={buttonClass} onClick={() => {
                    setConfirmItemId(current => current === property.itemId ? null : property.itemId);
                    setDrafts(current => current[property.itemId] ? current : { ...current, [property.itemId]: newDraft(property) });
                  }}>{reviewing ? 'Close correction' : 'Correct outcome'}</button>
                </div>
                {reviewing && <div className="mt-3 space-y-3 border-t border-slate-700 pt-3">
                  <label className="block text-xs font-medium text-slate-300">Correct result
                    <select value={draft.outcome} onChange={event => setDrafts(current => ({ ...current, [property.itemId]: {
                      ...draft, outcome: event.target.value as Draft['outcome'], skipReason: event.target.value === 'VISITED' ? null : draft.skipReason
                    } }))} className={fieldClass}>
                      <option value="VISITED">Viewed</option><option value="SKIPPED">Not viewed</option>
                    </select>
                  </label>
                  {draft.outcome === 'SKIPPED' && <>
                    <label className="block text-xs font-medium text-slate-300">Tenant-visible reason
                      <select value={draft.skipReason || ''} onChange={event => setDrafts(current => ({ ...current, [property.itemId]: {
                        ...draft, skipReason: (event.target.value || null) as OperationsOutcomeItem['skipReason']
                      } }))} className={fieldClass}>
                        <option value="">Choose a reason</option>
                        <option value="PROPERTY_UNAVAILABLE">Property unavailable</option><option value="ACCESS_DENIED">Could not access property</option>
                        <option value="TENANT_DECLINED">Tenant chose not to view</option><option value="TENANT_LEFT_EARLY">Visit ended early</option>
                        <option value="PROPERTY_MISMATCH">Property details did not match</option><option value="OTHER">Other</option>
                      </select>
                    </label>
                    {draft.skipReason === 'OTHER' && <label className="block text-xs font-medium text-slate-300">Internal note
                      <textarea maxLength={500} rows={2} value={draft.privateNote} onChange={event => setDrafts(current => ({ ...current, [property.itemId]: { ...draft, privateNote: event.target.value } }))} className={`${fieldClass} resize-y py-2`} />
                    </label>}
                  </>}
                  <label className="block text-xs font-medium text-slate-300">Internal correction reason
                    <textarea required minLength={8} maxLength={300} rows={2} value={draft.correctionReason}
                      onChange={event => setDrafts(current => ({ ...current, [property.itemId]: { ...draft, correctionReason: event.target.value } }))}
                      className={`${fieldClass} resize-y py-2`} placeholder="Explain why this outcome needs correction" />
                  </label>
                  <p className="rounded-lg border border-amber-500/25 bg-amber-950/20 p-3 text-xs text-amber-100">Current: {outcomeLabel(property.outcome)}{property.skipReason ? ` · ${reasonLabel(property.skipReason)}` : ''}. Proposed: {outcomeLabel(draft.outcome)}{draft.outcome === 'SKIPPED' && draft.skipReason ? ` · ${reasonLabel(draft.skipReason)}` : ''}. This change is recorded in visit history.</p>
                  {confirmItemId === property.itemId && <div className="flex flex-wrap gap-2">
                    <button type="button" className={buttonClass} onClick={() => setConfirmItemId(null)}>Cancel</button>
                    <button type="button" disabled={busyItemId !== null || draft.correctionReason.trim().length < 8 || draft.correctionReason.trim().length > 300
                      || (draft.outcome === 'SKIPPED' && (!draft.skipReason || (draft.skipReason === 'OTHER' && !draft.privateNote.trim())))}
                      onClick={() => void saveCorrection(property)} className={buttonClass}>{busyItemId === property.itemId ? 'Saving…' : 'Apply audited correction'}</button>
                  </div>}
                </div>}
              </article>;
            })}
            {detail.correctionHistory.length > 0 && <details className="mt-4 rounded-xl border border-slate-700 bg-slate-950/70 p-3">
              <summary className="min-h-11 cursor-pointer py-2 text-sm font-semibold text-slate-200">Correction history ({detail.correctionHistory.length})</summary>
              <ol className="space-y-2">{detail.correctionHistory.map((audit, index) => <li key={`${audit.itemId}-${audit.correctedAt}-${index}`} className="border-t border-slate-800 pt-2 text-xs text-slate-300">
                <p>Stop {detail.properties.find(property => property.itemId === audit.itemId)?.position ?? '—'}: {outcomeLabel(audit.previousOutcome)} → {outcomeLabel(audit.correctedOutcome)}</p>
                <p className="mt-1">Reason: {audit.correctionReason} · Operator {audit.actorUserId ?? 'unknown'} · {operationalVisitTime(audit.correctedAt)}</p>
              </li>)}</ol>
            </details>}
          </>}
        </div>}
      </article>;
    })}</div>}
    {totalPages > 1 && queueState === 'ready' && <nav aria-label="Outcome exception pages" className="flex items-center justify-between gap-3 text-sm text-slate-200">
      <button type="button" disabled={page === 0} onClick={() => setPage(value => value - 1)} className={buttonClass}>Previous</button>
      <span>Page {page + 1} of {totalPages}</span>
      <button type="button" disabled={page + 1 >= totalPages} onClick={() => setPage(value => value + 1)} className={buttonClass}>Next</button>
    </nav>}
  </section>;
};
