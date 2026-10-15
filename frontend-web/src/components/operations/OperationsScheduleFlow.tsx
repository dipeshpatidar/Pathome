import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { CheckCircle2, CircleAlert, Info, Lock, X } from 'lucide-react';
import {
  OPERATIONS_REASON_MAX,
  activeSessionItems,
  formatAvailabilityWindow,
  formatOperationsDate,
  formatOperationsDuration,
  formatOperationsDateTime,
  formatOperationsSlotRange,
  groundExecutiveLabel,
  operationsRecordReference,
  operationsZoneLabel,
  schedulingBlockersForSession,
  selectionNeedsReason,
  type OperationsRecommendationView,
  type OperationsSchedulingApproval,
  type OperationsSchedulingSelection,
  type OperationsSessionDetail,
  type OperationsSessionItem
} from '../../utils/operationsWorkspacePresentation';
import {
  OperationsGePicker,
  type DiscoveryLoader,
  type RecommendationLoader
} from './OperationsGePicker';
import { TenantConfirmationLabel } from './OperationsWorkspacePrimitives';
import './operationsSchedule.css';

/**
 * The scheduling drawer: Choose a Ground Executive and its evaluated slot, Review the exact selection
 * and the active itinerary, then show the authoritative Result.
 *
 * The drawer never decides the outcome. It submits one guarded command through the workspace and
 * renders whatever the server returned — the returned Session is the only statement about whether the
 * visit is scheduled, who is assigned and what the tenant-confirmation state is.
 */

export type ScheduleOutcome =
  | { kind: 'scheduled'; approval: OperationsSchedulingApproval; refreshFailed: boolean }
  | { kind: 'conflict'; status: number; message: string; detail: string | null }
  | { kind: 'denied'; message: string }
  | { kind: 'uncertain'; message: string }
  /** The reviewed evaluation no longer matches the authoritative Session: nothing may be booked. */
  | { kind: 'review-required'; message: string }
  | { kind: 'not-sent'; message: string };

type Step = 'choose' | 'review' | 'result' | 'conflict';

const FOCUSABLE =
  'button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), a[href], summary, [tabindex]:not([tabindex="-1"])';

interface Props {
  session: OperationsSessionDetail;
  /** True while the scheduling command owns the workspace's mutation lock. */
  blocked: boolean;
  outcome: ScheduleOutcome | null;
  onClose: () => void;
  onAcknowledgeOutcome: () => void;
  onConfirm: (
    selection: OperationsSchedulingSelection,
    view: OperationsRecommendationView | null,
    reason: string
  ) => void;
  loadRanked: RecommendationLoader;
  loadDiscovery: DiscoveryLoader;
  /** Safe GET refresh of the authoritative session after a conflict. */
  onRefreshSession: () => void;
}

const Row: React.FC<{ label: string; children: React.ReactNode }> = ({ label, children }) => (
  <div className="oe-review-row">
    <dt>{label}</dt>
    <dd>{children}</dd>
  </div>
);

const StopList: React.FC<{ items: OperationsSessionItem[] }> = ({ items }) => (
  <section className="oe-review-stops" aria-labelledby="oe-review-stops-title">
    <h3 id="oe-review-stops-title" className="oe-review-stops-title">Visit stops ({items.length})</h3>
    <ol className="oe-review-stops-list">
      {items.map((item, index) => (
        <li key={item.itemId} className="oe-review-stop">
          <span className="oe-review-stop-num oe-num" aria-hidden="true">{index + 1}</span>
          <span className="oe-review-stop-body">
            <span className="oe-review-stop-title">{item.title ?? 'Listing title not recorded'}</span>
            <span className="oe-review-stop-meta">
              <span className="oe-mono">
                {item.listingId === null ? 'Listing ID not recorded' : operationsRecordReference(item.listingId)}
              </span>
              {' · '}
              {item.sector ?? 'Locality not provided'}
            </span>
            <span className="oe-review-stop-meta">
              {formatAvailabilityWindow(item) ? `Available ${formatAvailabilityWindow(item)}` : 'No complete availability window recorded'}
            </span>
          </span>
        </li>
      ))}
    </ol>
  </section>
);

const SelectionSummary: React.FC<{
  selection: OperationsSchedulingSelection;
  session: OperationsSessionDetail;
  stopCount: number;
}> = ({ selection, session, stopCount }) => {
  const candidate = selection.candidate;
  return (
    <dl className="oe-review-list">
      <Row label="Session">
        <span className="oe-mono">{operationsRecordReference(session.sessionId)}</span>
        {` · ${stopCount} ${stopCount === 1 ? 'stop' : 'stops'}`}
      </Row>
      <Row label="Ground Executive">
        {groundExecutiveLabel(candidate)}
        <span className="oe-field-sub oe-mono">GE user ID {candidate.groundExecutiveUserId}</span>
      </Row>
      <Row label="Evaluated slot">
        {formatOperationsDate(candidate.scheduledAt) ?? 'Time not returned'}
        {formatOperationsSlotRange(candidate) ? ` · ${formatOperationsSlotRange(candidate)}` : ''}
        <span className="oe-field-sub">
          {formatOperationsDuration(candidate.durationMinutes) ?? 'Duration not returned'}
          {` · ${operationsZoneLabel(candidate.zoneId) ?? 'zone not returned'}`}
        </span>
      </Row>
      <Row label="Found by">
        {selection.source === 'recommended' ? 'Ranked suggestion' : selection.source === 'browse' ? 'Browse all' : 'Search'}
      </Row>
    </dl>
  );
};

export const OperationsScheduleFlow: React.FC<Props> = ({
  session,
  blocked,
  outcome,
  onClose,
  onAcknowledgeOutcome,
  onConfirm,
  loadRanked,
  loadDiscovery,
  onRefreshSession
}) => {
  const [step, setStep] = useState<Step>('choose');
  const [selection, setSelection] = useState<OperationsSchedulingSelection | null>(null);
  const [reason, setReason] = useState('');
  const [showError, setShowError] = useState(false);
  const [rankedView, setRankedView] = useState<OperationsRecommendationView | null>(null);
  /** Bumped to remount the picker, which is how "reload candidates" issues a fresh ranked read. */
  const [pickerKey, setPickerKey] = useState(0);

  const dialogRef = useRef<HTMLDivElement | null>(null);
  const reasonRef = useRef<HTMLTextAreaElement | null>(null);

  const activeItems = useMemo(() => activeSessionItems(session.items), [session.items]);
  const blockers = useMemo(() => schedulingBlockersForSession(session), [session]);
  const needsReason = selectionNeedsReason(rankedView, selection);
  const reasonInvalid = needsReason && showError && reason.trim().length === 0;

  const focusStepTitle = useCallback(() => {
    const target = document.getElementById('oe-step-title');
    (target ?? dialogRef.current)?.focus();
  }, []);

  // Modal behaviour: page scroll stays locked, focus starts on the step heading, cannot leave the
  // dialog, and is restored to the control that opened it when the drawer closes.
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    const overflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    focusStepTitle();
    const onFocusIn = (event: FocusEvent) => {
      if (dialogRef.current && event.target instanceof Node && !dialogRef.current.contains(event.target)) {
        focusStepTitle();
      }
    };
    document.addEventListener('focusin', onFocusIn);
    return () => {
      document.removeEventListener('focusin', onFocusIn);
      document.body.style.overflow = overflow;
      previous?.focus();
    };
  }, [focusStepTitle]);

  useEffect(() => {
    if (!outcome) return;
    setStep(outcome.kind === 'scheduled' ? 'result' : 'conflict');
    setShowError(false);
    /**
     * A voided evaluation must not survive as the "current" selection: the candidate and its
     * evaluated version are cleared, so the next booking needs a freshly evaluated choice and an
     * explicit Review rather than a silently re-labelled version.
     */
    if (outcome.kind === 'review-required') setSelection(null);
  }, [outcome]);

  const onKeyDown = (event: React.KeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'Escape') {
      event.preventDefault();
      onClose();
      return;
    }
    if (event.key !== 'Tab' || !dialogRef.current) return;
    const nodes = [...dialogRef.current.querySelectorAll<HTMLElement>(FOCUSABLE)]
      .filter((node) => node.getClientRects().length > 0);
    if (nodes.length === 0) return;
    const first = nodes[0];
    const last = nodes[nodes.length - 1];
    const active = document.activeElement as HTMLElement | null;
    const index = active ? nodes.indexOf(active) : -1;
    if (index === -1) {
      event.preventDefault();
      (event.shiftKey ? last : first).focus();
    } else if (event.shiftKey && index === 0) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && index === nodes.length - 1) {
      event.preventDefault();
      first.focus();
    }
  };

  const submit = () => {
    if (!selection || blocked) return;
    /**
     * The requirement is evaluated from the current selection and the current text in this click.
     * Reading it from a rendered value would let a blank required reason reach the booking handler on
     * the first click, which produced a misleading error instead of accurate inline validation.
     */
    if (selectionNeedsReason(rankedView, selection) && reason.trim().length === 0) {
      setShowError(true);
      reasonRef.current?.focus();
      return;
    }
    setShowError(false);
    onConfirm(selection, rankedView, reason);
  };

  // A confirmed booking always carries the authoritative Session, so the Result view never falls back
  // to the pre-booking snapshot.
  const scheduledSession = outcome?.kind === 'scheduled' ? outcome.approval.session : null;

  return (
    <div className="oe-sch-scrim">
      <div
        ref={dialogRef}
        className="oe-sch"
        role="dialog"
        aria-modal="true"
        aria-labelledby="oe-sch-title"
        tabIndex={-1}
        onKeyDown={onKeyDown}
      >
        <header className="oe-sch-head">
          <div className="oe-sch-headtext">
            <h2 id="oe-sch-title" className="oe-sch-title">Schedule visit</h2>
            <p className="oe-sch-sub">
              <span className="oe-mono">{operationsRecordReference(session.sessionId)}</span>
              {` · ${session.city ?? 'City not recorded'}`}
            </p>
          </div>
          <button type="button" className="oe-icon-btn" aria-label="Close scheduling" onClick={onClose}>
            <X size={18} aria-hidden="true" />
          </button>
        </header>

        <div className="oe-sch-sticky">
          <ol className="oe-stepper" aria-label="Scheduling steps">
            {(['choose', 'review', 'result'] as const).map((key, index) => {
              const shown = step === 'conflict' ? 'review' : step;
              return (
                <li key={key} className="oe-stepper-item" aria-current={shown === key ? 'step' : undefined}>
                  <span className="oe-stepper-num oe-num">{index + 1}</span>
                  {key === 'choose' ? 'Choose' : key === 'review' ? 'Review' : 'Result'}
                </li>
              );
            })}
          </ol>
          <details className="oe-sch-context">
            <summary>{activeItems.length} active {activeItems.length === 1 ? 'stop' : 'stops'} · session v{session.version ?? 'unknown'}</summary>
            <ol className="oe-sch-stops">
              {activeItems.map((item) => (
                <li key={item.itemId}>
                  <span className="oe-sch-stop-title">{item.title ?? 'Listing title not recorded'}</span>
                  <span className="oe-quiet"> · {item.sector ?? 'Locality not provided'}</span>
                </li>
              ))}
            </ol>
          </details>
        </div>

        <div className="oe-sch-body">
          {blockers.length > 0 ? (
            <div className="oe-step">
              <div className="oe-conflict" role="alert">
                <Lock size={20} aria-hidden="true" />
                <div>
                  <p className="oe-conflict-cat">Validation</p>
                  <h2 id="oe-step-title" tabIndex={-1} className="oe-step-title">
                    This session can’t be scheduled yet
                  </h2>
                  <p className="oe-step-hint">
                    The server evaluates the itinerary before it will book anything, and these facts stop
                    it: a reason can’t bypass them.
                  </p>
                </div>
              </div>
              <ul className="oe-blocked-list">
                {blockers.map((blocker) => <li key={blocker}>{blocker}</li>)}
              </ul>
              <div className="oe-conflict-actions">
                <button type="button" className="oe-btn oe-btn--primary oe-btn--md" onClick={onClose}>
                  Return to session details
                </button>
              </div>
            </div>
          ) : (
            <>
              {step === 'choose' ? (
                <div className="oe-step">
                  <div className="oe-step-head">
                    <h2 id="oe-step-title" tabIndex={-1} className="oe-step-title">Choose a Ground Executive</h2>
                    <p className="oe-step-hint">
                      Each candidate comes with the one slot the server evaluated for it, including its
                      duration and reserved end.
                    </p>
                  </div>
                  <OperationsGePicker
                    key={pickerKey}
                    sessionId={session.sessionId}
                    sessionVersion={session.version ?? 0}
                    city={session.city ?? 'your city'}
                    selection={selection}
                    onSelect={setSelection}
                    loadRanked={loadRanked}
                    loadDiscovery={loadDiscovery}
                    onRankedView={setRankedView}
                    onRefreshSession={onRefreshSession}
                    blocked={blocked}
                  />
                </div>
              ) : null}

              {step === 'review' && selection ? (
                <div className="oe-step">
                  <div className="oe-step-head">
                    <h2 id="oe-step-title" tabIndex={-1} className="oe-step-title">Review before confirming</h2>
                    <p className="oe-step-hint">
                      Nothing is booked until the server confirms. It re-checks the Ground Executive’s
                      eligibility and the slot when you confirm.
                    </p>
                  </div>
                  <SelectionSummary selection={selection} session={session} stopCount={activeItems.length} />
                  <StopList items={activeItems} />
                  {needsReason ? (
                    <div className="oe-reason">
                      <label htmlFor="oe-reason" className="oe-reason-label">
                        Reason for this choice <span className="oe-required">Required</span>
                      </label>
                      <p id="oe-reason-why" className="oe-reason-why">
                        This isn’t the top evaluated suggestion, so the server requires a short
                        operational reason for the choice.
                      </p>
                      <textarea
                        ref={reasonRef}
                        id="oe-reason"
                        className="oe-textarea"
                        rows={4}
                        maxLength={OPERATIONS_REASON_MAX}
                        value={reason}
                        aria-required="true"
                        aria-invalid={reasonInvalid}
                        aria-describedby={`oe-reason-why oe-reason-count${reasonInvalid ? ' oe-reason-error' : ''}`}
                        onChange={(event) => setReason(event.target.value)}
                      />
                      <div className="oe-reason-foot">
                        {reasonInvalid ? (
                          <p id="oe-reason-error" className="oe-reason-error" role="alert">Add a reason to continue.</p>
                        ) : <span />}
                        <span id="oe-reason-count" className="oe-reason-count oe-num">{reason.length}/{OPERATIONS_REASON_MAX}</span>
                      </div>
                      <p className="oe-footnote">
                        The reason explains the choice. It can’t override availability, eligibility or
                        booking rules.
                      </p>
                    </div>
                  ) : (
                    <p className="oe-inline-note">
                      <CheckCircle2 size={14} aria-hidden="true" />
                      You chose the current top evaluated suggestion and its slot, so no reason is needed.
                    </p>
                  )}
                </div>
              ) : null}

              {step === 'result' && outcome?.kind === 'scheduled' ? (
                <div className="oe-step">
                  <h2 id="oe-step-title" tabIndex={-1} className="oe-step-title">Result</h2>
                  <div className="oe-result" role="status">
                    <CheckCircle2 size={20} aria-hidden="true" />
                    <p>
                      <strong>
                        {(scheduledSession?.status ?? '') === 'SCHEDULED'
                          ? 'The visit is scheduled.'
                          : 'The server accepted the booking and returned this session state.'}
                      </strong>{' '}
                      The details below are the session the server returned.
                    </p>
                  </div>
                  {outcome.refreshFailed ? (
                    <div className="oe-notice oe-notice--warn" role="alert">
                      <span className="oe-notice-icon" aria-hidden="true"><CircleAlert size={18} /></span>
                      <div>
                        <p className="oe-notice-title">Scheduled, but your work lists couldn’t be refreshed</p>
                        <p className="oe-notice-body">
                          The server confirmed the booking. Only reloading your queues failed, so they may
                          not show it yet. Don’t schedule again — refresh to see the current record.
                        </p>
                        <div className="oe-notice-actions">
                          <button type="button" className="oe-btn oe-btn--secondary oe-btn--sm" onClick={onRefreshSession}>
                            Refresh session details
                          </button>
                        </div>
                      </div>
                    </div>
                  ) : null}
                  <dl className="oe-review-list">
                    <Row label="Session">
                      <span className="oe-mono">{operationsRecordReference(scheduledSession?.sessionId ?? session.sessionId)}</span>
                    </Row>
                    <Row label="Visit date and time">
                      {formatOperationsDateTime(scheduledSession?.scheduledAt)
                        ?? <span className="oe-quiet">Not returned</span>}
                    </Row>
                    <Row label="Assigned Ground Executive">
                      {scheduledSession?.representativeUserId === null || scheduledSession === null
                        ? <span className="oe-quiet">Not assigned</span>
                        : <span className="oe-mono">User {scheduledSession.representativeUserId}</span>}
                    </Row>
                    <Row label="Tenant confirmation">
                      <TenantConfirmationLabel value={scheduledSession?.tenantConfirmationState ?? null} />
                      <span className="oe-field-sub">
                        Scheduling and tenant confirmation are separate steps, and this workspace can’t
                        confirm on the tenant’s behalf.
                      </span>
                    </Row>
                    {reason.trim() ? (
                      <Row label="Reason recorded">
                        <span className="oe-reason-quote">{reason.trim()}</span>
                      </Row>
                    ) : null}
                  </dl>
                </div>
              ) : null}

              {step === 'conflict' && outcome && outcome.kind !== 'scheduled' ? (
                <ConflictStep
                  outcome={outcome}
                  onRefreshCandidates={() => {
                    onAcknowledgeOutcome();
                    setSelection(null);
                    setPickerKey((current) => current + 1);
                    setStep('choose');
                  }}
                  onRefreshSession={() => {
                    onAcknowledgeOutcome();
                    onRefreshSession();
                    // The command is never replayed: refresh the authoritative record, then re-review.
                    setStep('review');
                  }}
                  onChooseDifferent={() => {
                    onAcknowledgeOutcome();
                    setSelection(null);
                    setStep('choose');
                  }}
                  onClose={onClose}
                />
              ) : null}
            </>
          )}
        </div>

        {blockers.length === 0 && step === 'choose' ? (
          <footer className="oe-sch-foot">
            <p className="oe-sch-picked" aria-live="polite">
              {selection ? (
                <>
                  <span className="oe-sch-picked-label">Selected</span>
                  {groundExecutiveLabel(selection.candidate)}
                  {formatOperationsDate(selection.candidate.scheduledAt)
                    ? `, ${formatOperationsDate(selection.candidate.scheduledAt)}`
                    : ''}
                  {formatOperationsSlotRange(selection.candidate)
                    ? ` · ${formatOperationsSlotRange(selection.candidate)}`
                    : ''}
                </>
              ) : (
                <span className="oe-quiet">Select a Ground Executive to continue.</span>
              )}
            </p>
            <button
              type="button"
              className="oe-btn oe-btn--primary oe-btn--md"
              disabled={!selection || blocked}
              onClick={() => {
                setShowError(false);
                setStep('review');
              }}
            >
              Continue
            </button>
          </footer>
        ) : null}
        {blockers.length === 0 && step === 'review' ? (
          <footer className="oe-sch-foot">
            <button type="button" className="oe-btn oe-btn--secondary oe-btn--md" disabled={blocked} onClick={() => setStep('choose')}>
              Back
            </button>
            <button type="button" className="oe-btn oe-btn--primary oe-btn--md" disabled={blocked} onClick={submit}>
              {blocked ? 'Sending to server…' : 'Confirm and schedule'}
            </button>
          </footer>
        ) : null}
        {blockers.length === 0 && step === 'result' ? (
          <footer className="oe-sch-foot">
            <button type="button" className="oe-btn oe-btn--primary oe-btn--md" onClick={onClose}>
              Close
            </button>
          </footer>
        ) : null}
      </div>
    </div>
  );
};

const ConflictStep: React.FC<{
  outcome: Exclude<ScheduleOutcome, { kind: 'scheduled' }>;
  onRefreshCandidates: () => void;
  onRefreshSession: () => void;
  onChooseDifferent: () => void;
  onClose: () => void;
}> = ({ outcome, onRefreshCandidates, onRefreshSession, onChooseDifferent, onClose }) => {
  const copy = (() => {
    switch (outcome.kind) {
      case 'denied':
        return {
          category: 'Access',
          title: 'You no longer have access to schedule this session',
          body: 'It may have been released or moved outside your scope. No details are shown.'
        };
      case 'uncertain':
        return {
          category: 'Outcome unknown',
          title: 'We can’t tell whether this was scheduled',
          body: 'The request was sent, but no reliable answer came back. It won’t be retried automatically. Check the session before doing anything else.'
        };
      case 'review-required':
        return {
          category: 'Review required',
          title: 'This session changed since these candidates were evaluated',
          body: 'Its itinerary or version moved on while you were deciding, so nothing was booked. Reload the session to see the current itinerary, reload the candidates, and review the new evaluation before booking.'
        };
      case 'not-sent':
        return {
          category: 'Not sent',
          title: 'This booking was not sent',
          body: 'The action stopped before anything left the app, so nothing was booked. Review the current state and try again.'
        };
      default:
        return outcome.status === 409
          ? {
              category: 'Conflict',
              title: 'This selection is no longer available',
              body: 'The Ground Executive’s eligibility or the evaluated slot changed after it was listed. Nothing was booked. Refresh the session to continue.'
            }
          : outcome.status === 400
            ? {
                category: 'Refused',
                title: 'The server refused this selection',
                body: 'Nothing was booked. The selection and its reason must match the server’s current recommendation baseline.'
              }
            : {
                category: 'Conflict',
                title: 'This booking couldn’t be completed',
                body: 'Nothing was booked. Refresh the candidates and choose again.'
              };
    }
  })();

  const detail = outcome.kind === 'conflict' ? outcome.detail ?? outcome.message : outcome.message;

  return (
    <div className="oe-step">
      <div className="oe-conflict" role="alert">
        <CircleAlert size={20} aria-hidden="true" />
        <div>
          <p className="oe-conflict-cat">{copy.category}</p>
          <h2 id="oe-step-title" tabIndex={-1} className="oe-step-title">{copy.title}</h2>
          <p className="oe-step-hint">{copy.body}</p>
        </div>
      </div>
      {detail ? <p className="oe-inline-note"><Info size={14} aria-hidden="true" />{detail}</p> : null}
      <div className="oe-conflict-actions">
        {outcome.kind === 'conflict' && outcome.status === 409 ? (
          <button type="button" className="oe-btn oe-btn--primary oe-btn--md" onClick={onRefreshSession}>
            Refresh the session
          </button>
        ) : null}
        {outcome.kind === 'conflict' ? (
          <button type="button" className="oe-btn oe-btn--secondary oe-btn--md" onClick={onChooseDifferent}>
            Choose a different candidate
          </button>
        ) : null}
        {outcome.kind === 'not-sent' || outcome.kind === 'uncertain' ? (
          <button type="button" className="oe-btn oe-btn--primary oe-btn--md" onClick={onRefreshSession}>
            Check the session
          </button>
        ) : null}
        {outcome.kind === 'review-required' ? (
          <button type="button" className="oe-btn oe-btn--primary oe-btn--md" onClick={onRefreshSession}>
            Reload the session
          </button>
        ) : null}
        <button type="button" className="oe-btn oe-btn--secondary oe-btn--md" onClick={outcome.kind === 'denied' ? onClose : onRefreshCandidates}>
          {outcome.kind === 'denied' ? 'Return to session details' : 'Reload candidates'}
        </button>
      </div>
    </div>
  );
};
