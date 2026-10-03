import React, { useCallback, useEffect, useState } from 'react';
import { AlertCircle, CheckCircle2, LoaderCircle, RefreshCw } from 'lucide-react';
import { ApiRequestError } from '../services/apiError';
import {
  createVisitOperationId,
  GroundVisitOutcomeItem,
  GroundVisitOutcomeReport,
  RecordGroundVisitOutcomeCommand,
  visitExecutionService
} from '../services/visitExecutionService';
import { canSaveSkip, canSubmitOutcomeReport, outcomeSaveFailureAction, outcomeSkipReasons, outcomeStateLabel, recordedOutcomeCount, skipReasonLabel } from '../utils/visitOutcomePresentation';

const buttonClass = 'inline-flex min-h-11 items-center justify-center rounded-xl border border-slate-700 bg-slate-800 px-3.5 py-2 text-sm font-semibold text-slate-100 hover:bg-slate-700 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-cyan-400 disabled:cursor-wait disabled:opacity-55';
const inputClass = 'mt-1 min-h-11 w-full rounded-lg border border-slate-700 bg-slate-900 px-3 text-base text-white focus-visible:outline focus-visible:outline-2 focus-visible:outline-cyan-400';
type Draft = { outcome: 'VISITED' | 'SKIPPED' | 'UNRECORDED'; skipReason: GroundVisitOutcomeItem['skipReason']; privateNote: string };

interface Props { sessionId: number; compact?: boolean; onFinalized?: () => void }

const draftsFrom = (report: GroundVisitOutcomeReport): Record<number, Draft> => Object.fromEntries(report.items.map(item => [item.itemId, {
  outcome: item.outcome, skipReason: item.skipReason, privateNote: item.privateNote || ''
}]));

export const GroundVisitOutcomePanel: React.FC<Props> = ({ sessionId, compact = false, onFinalized }) => {
  const [report, setReport] = useState<GroundVisitOutcomeReport | null>(null);
  const [accessUnavailable, setAccessUnavailable] = useState(false);
  const [drafts, setDrafts] = useState<Record<number, Draft>>({});
  const [loading, setLoading] = useState(true);
  const [savingItem, setSavingItem] = useState<number | null>(null);
  const [savedItem, setSavedItem] = useState<number | null>(null);
  const [retryCommands, setRetryCommands] = useState<Record<number, RecordGroundVisitOutcomeCommand>>({});
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [completionCommand, setCompletionCommand] = useState<{ expectedSessionVersion: number; expectedReportVersion: number; operationId: string } | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [completionRetry, setCompletionRetry] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const latest = await visitExecutionService.getOutcomeReport(sessionId);
      setAccessUnavailable(false);
      setReport(latest);
      setDrafts(draftsFrom(latest));
      setRetryCommands({});
      setCompletionCommand(null);
      setCompletionRetry(false);
    } catch (cause) {
      if (cause instanceof ApiRequestError && (cause.status === 403 || cause.status === 404)) {
        setAccessUnavailable(true);
        setReport(null);
        setDrafts({});
      }
      setError(cause instanceof ApiRequestError && cause.status === 403
        ? 'This account cannot record visit outcomes.'
        : cause instanceof ApiRequestError && cause.status === 404
          ? 'Visit details are not available to this account yet. Refresh assigned visits to check again.'
          : cause instanceof ApiRequestError ? cause.message : 'Could not load visit details. Check your connection and retry.');
    } finally { setLoading(false); }
  }, [sessionId]);

  useEffect(() => { void load(); }, [load]);

  const sendOutcome = async (itemId: number, command: RecordGroundVisitOutcomeCommand) => {
    setSavingItem(itemId);
    setSavedItem(null);
    setError(null);
    setNotice(null);
    setRetryCommands(current => ({ ...current, [itemId]: command }));
    try {
      const updated = await visitExecutionService.recordOutcome(sessionId, itemId, command);
      setReport(updated);
      setDrafts(draftsFrom(updated));
      setRetryCommands(current => { const next = { ...current }; delete next[itemId]; return next; });
      setSavedItem(itemId);
    } catch (cause) {
      if (cause instanceof ApiRequestError && outcomeSaveFailureAction(cause.status) === 'REFRESH') {
        setNotice('This visit was updated elsewhere. Refreshing the latest details.');
        setRetryCommands(current => { const next = { ...current }; delete next[itemId]; return next; });
        await load();
      } else if (cause instanceof ApiRequestError && (cause.status === 403 || cause.status === 404)) {
        setAccessUnavailable(true);
        setReport(null);
        setDrafts({});
        setRetryCommands({});
        setError('Visit details are no longer available to this account. Refresh assigned visits to check again.');
      } else {
        setError(cause instanceof ApiRequestError ? cause.message : 'Outcome was not saved. Retry when your connection is available.');
      }
    } finally { setSavingItem(null); }
  };

  const saveViewed = (item: GroundVisitOutcomeItem) => {
    if (!report) return;
    const draft = drafts[item.itemId];
    const command: RecordGroundVisitOutcomeCommand = {
      outcome: 'VISITED', skipReason: null, privateNote: null,
      expectedSessionVersion: report.sessionVersion, expectedReportVersion: report.reportVersion,
      expectedItemVersion: item.itemVersion, operationId: createVisitOperationId()
    };
    setDrafts(current => ({ ...current, [item.itemId]: { ...draft, outcome: 'VISITED', skipReason: null, privateNote: '' } }));
    void sendOutcome(item.itemId, command);
  };

  const saveSkipped = (item: GroundVisitOutcomeItem) => {
    if (!report) return;
    const draft = drafts[item.itemId] || { outcome: 'UNRECORDED', skipReason: null, privateNote: '' };
    if (!canSaveSkip(draft.skipReason, draft.privateNote)) return;
    const command: RecordGroundVisitOutcomeCommand = {
      outcome: 'SKIPPED', skipReason: draft.skipReason, privateNote: draft.privateNote.trim() || null,
      expectedSessionVersion: report.sessionVersion, expectedReportVersion: report.reportVersion,
      expectedItemVersion: item.itemVersion, operationId: createVisitOperationId()
    };
    void sendOutcome(item.itemId, command);
  };

  const submitReport = async () => {
    if (!report || !canSubmitOutcomeReport(report)) return;
    const command = completionCommand || {
      expectedSessionVersion: report.sessionVersion,
      expectedReportVersion: report.reportVersion,
      operationId: createVisitOperationId()
    };
    setCompletionCommand(command);
    setSubmitting(true);
    setError(null);
    setNotice(null);
    try {
      const finalized = await visitExecutionService.completeWithOutcomes(sessionId, command);
      setReport(finalized);
      setDrafts(draftsFrom(finalized));
      setCompletionCommand(null);
      setCompletionRetry(false);
      onFinalized?.();
    } catch (cause) {
      if (cause instanceof ApiRequestError && outcomeSaveFailureAction(cause.status) === 'REFRESH') {
        setNotice('This visit was updated elsewhere. Refreshing the latest details.');
        setCompletionCommand(null);
        await load();
      } else if (cause instanceof ApiRequestError && (cause.status === 403 || cause.status === 404)) {
        setAccessUnavailable(true);
        setReport(null);
        setDrafts({});
        setCompletionCommand(null);
        setError('Visit details are no longer available to this account. Refresh assigned visits to check again.');
      } else {
        setCompletionRetry(true);
        setError(cause instanceof ApiRequestError ? cause.message : 'Report was not submitted. Retry when your connection is available.');
      }
    } finally { setSubmitting(false); }
  };

  return <section aria-label="Visit property outcomes" className={`min-w-0 rounded-xl border border-slate-700 bg-slate-950/45 ${compact ? 'p-3' : 'p-4'}`}>
    <div className="flex flex-wrap items-center justify-between gap-2">
      <div>
        <h4 className="font-semibold text-white">Property outcomes</h4>
        {report && <p className="mt-1 text-xs text-slate-300">{recordedOutcomeCount(report)} of {report.items.length} properties recorded</p>}
      </div>
      <button type="button" onClick={() => void load()} disabled={loading || savingItem !== null || submitting} className={buttonClass}>
        {loading ? <LoaderCircle className="h-4 w-4 animate-spin" aria-hidden="true" /> : <RefreshCw className="h-4 w-4" aria-hidden="true" />}Refresh details
      </button>
    </div>
    {notice && <p role="status" aria-live="polite" className="mt-3 rounded-lg border border-amber-500/30 bg-amber-950/20 p-3 text-sm text-amber-100">{notice}</p>}
    {error && <div role="alert" className="mt-3 flex items-start gap-2 rounded-lg border border-rose-500/30 bg-rose-950/25 p-3 text-sm text-rose-100"><AlertCircle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" /><p>{error}</p></div>}
    {loading && !report && <p role="status" className="mt-3 text-sm text-slate-300">Loading visit details…</p>}
    {!loading && error && !report && <button type="button" onClick={() => void load()} className={`${buttonClass} mt-3`}>{accessUnavailable ? 'Refresh assigned visits' : 'Retry loading details'}</button>}
    {report && <>
      {report.reportState === 'FINALIZED' && <p role="status" className="mt-3 flex items-center gap-2 rounded-lg border border-emerald-500/25 bg-emerald-950/20 p-3 text-sm text-emerald-100"><CheckCircle2 className="h-4 w-4 shrink-0" aria-hidden="true" />Report submitted · {report.items.filter(item => item.outcome === 'VISITED').length} of {report.items.length} properties viewed</p>}
      {report.sessionState === 'COMPLETED' && report.reportState === 'OPEN' && <p role="status" className="mt-3 rounded-lg border border-amber-500/25 bg-amber-950/20 p-3 text-sm text-amber-100">Visit ended. Outcome details are still pending.</p>}
      <div className="mt-3 space-y-3">
        {report.items.map((item, index) => {
          const draft = drafts[item.itemId] || { outcome: item.outcome, skipReason: item.skipReason, privateNote: item.privateNote || '' };
          const isSaving = savingItem === item.itemId;
          const canEdit = report.reportState === 'OPEN';
          return <article key={item.itemId} className="min-w-0 rounded-xl border border-slate-700/80 bg-slate-900/80 p-3">
            <div className="flex min-w-0 items-start justify-between gap-2">
              <div className="min-w-0">
                <p className="text-xs font-semibold uppercase tracking-wide text-slate-400">Stop {index + 1}</p>
                <h5 className="mt-1 break-words text-sm font-semibold text-white">{item.title}</h5>
                <p className="mt-1 break-words text-xs leading-5 text-slate-300">{[item.address, item.sector, item.city].filter(Boolean).join(' · ')}</p>
              </div>
              <span className={`shrink-0 rounded-full px-2.5 py-1 text-xs font-semibold ${item.outcome === 'UNRECORDED' ? 'bg-amber-500/10 text-amber-200' : 'bg-slate-800 text-slate-200'}`}>{outcomeStateLabel(item.outcome)}</span>
            </div>
            {item.outcome === 'SKIPPED' && item.skipReason && <p className="mt-2 text-xs text-slate-300">{skipReasonLabel(item.skipReason)}</p>}
            {canEdit && <>
              <div className="mt-3 grid grid-cols-2 gap-2">
                <button type="button" disabled={isSaving || savingItem !== null || submitting || Boolean(retryCommands[item.itemId])} onClick={() => saveViewed(item)} className={`${buttonClass} ${item.outcome === 'VISITED' ? 'border-emerald-500/40 bg-emerald-950/35' : ''}`}>
                  {isSaving && draft.outcome === 'VISITED' ? <LoaderCircle className="h-4 w-4 animate-spin" aria-hidden="true" /> : null}Viewed
                </button>
                <button type="button" disabled={isSaving || savingItem !== null || submitting || Boolean(retryCommands[item.itemId])} onClick={() => setDrafts(current => ({ ...current, [item.itemId]: { ...draft, outcome: 'SKIPPED' } }))} className={`${buttonClass} ${draft.outcome === 'SKIPPED' ? 'border-amber-500/40 bg-amber-950/25' : ''}`}>Not viewed</button>
              </div>
              {draft.outcome === 'SKIPPED' && <div className="mt-3 space-y-2">
                <label className="block text-xs font-medium text-slate-300">Reason
                  <select value={draft.skipReason || ''} disabled={savingItem !== null || submitting || Boolean(retryCommands[item.itemId])}
                    onChange={event => setDrafts(current => ({ ...current, [item.itemId]: { ...draft, skipReason: (event.target.value || null) as GroundVisitOutcomeItem['skipReason'] } }))}
                    className={inputClass}>
                    <option value="">Choose a reason</option>
                    {outcomeSkipReasons.map(reason => <option key={reason.value} value={reason.value}>{reason.label}</option>)}
                  </select>
                </label>
                {draft.skipReason === 'OTHER' && <label className="block text-xs font-medium text-slate-300">Short private note
                  <textarea maxLength={500} rows={2} value={draft.privateNote} disabled={savingItem !== null || submitting || Boolean(retryCommands[item.itemId])}
                    onChange={event => setDrafts(current => ({ ...current, [item.itemId]: { ...draft, privateNote: event.target.value } }))}
                    className={`${inputClass} resize-y py-2`} />
                </label>}
                {retryCommands[item.itemId] ? <button type="button" disabled={isSaving} onClick={() => void sendOutcome(item.itemId, retryCommands[item.itemId])} className={buttonClass}>{isSaving ? 'Saving…' : 'Retry save'}</button>
                  : <button type="button" disabled={savingItem !== null || submitting || !canSaveSkip(draft.skipReason, draft.privateNote)} onClick={() => saveSkipped(item)} className={buttonClass}>{isSaving ? 'Saving…' : 'Save outcome'}</button>}
              </div>}
              {retryCommands[item.itemId] && draft.outcome === 'VISITED' && <button type="button" disabled={isSaving} onClick={() => void sendOutcome(item.itemId, retryCommands[item.itemId])} className={`${buttonClass} mt-2`}>{isSaving ? 'Saving…' : 'Retry save'}</button>}
              {savedItem === item.itemId && <p role="status" className="mt-2 text-xs text-emerald-300">Saved</p>}
            </>}
          </article>;
        })}
      </div>
      {report.reportState === 'OPEN' && <div className="mt-4 border-t border-slate-800 pt-4">
        <p className="text-sm text-slate-300">{report.sessionState === 'STARTED' ? 'Finish the visit now if you need to move on. You can complete this report later.' : 'All property outcomes are saved and ready to submit.'}</p>
        <button type="button" disabled={!canSubmitOutcomeReport(report) || savingItem !== null || submitting} onClick={() => void submitReport()} className={`${buttonClass} mt-3 w-full sm:w-auto`}>
          {submitting ? <><LoaderCircle className="h-4 w-4 animate-spin" aria-hidden="true" />Submitting…</> : completionRetry ? 'Retry report submission' : report.sessionState === 'STARTED' ? 'Finish visit and submit report' : 'Submit report'}
        </button>
        {!canSubmitOutcomeReport(report) && <p className="mt-2 text-xs text-slate-400">Record every property before submitting the report. The separate Finish Visit action stays available.</p>}
      </div>}
    </>}
  </section>;
};
