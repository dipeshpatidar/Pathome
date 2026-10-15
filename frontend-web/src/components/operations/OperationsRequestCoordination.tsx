import React, { useCallback, useEffect, useRef, useState } from 'react';
import { ArrowLeft, Ban, FilePlus2, Link2 } from 'lucide-react';
import {
  operationsRecordReference,
  type OperationsMyWorkItem
} from '../../utils/operationsWorkspacePresentation';

/**
 * Coordination actions for an owned standalone RECEIVED Request: create a new DRAFT session, attach
 * the Request to one of the caller's owned DRAFT sessions, or record confirmed unavailability.
 *
 * Every command is executed by the workspace through the shared guarded lifecycle, so this component
 * only collects the decision and never transmits anything itself. It also never claims eligibility:
 * the DRAFT sessions below are owned candidates, and the server decides whether one may accept the
 * Request. No Tenant identity is returned by these DTOs, so the list is deliberately described as
 * candidates rather than verified same-Tenant matches.
 */

export interface OperationsOwnedCandidatePage<T> {
  items: T[];
  page: number;
  totalPages: number;
  totalCount: number;
}

type Mode = 'menu' | 'new' | 'attach' | 'unavailable';

interface Props {
  requestId: number;
  /** True while any operational command owns the workspace's mutation lock. */
  blocked: boolean;
  /** The in-flight command key, so only the control that started it shows progress. */
  busyCommand: string | null;
  onCoordinateNewSession: () => void;
  onAttachToOwnedDraft: (sessionId: number, sessionVersion: number) => void;
  onMarkConfirmedUnavailable: () => void;
  /**
   * Reads one server page of the caller's owned My Work roots under the workspace's captured
   * session. Resolves null when the read was invalidated (account, epoch or authority changed),
   * so a stale page can never be offered as a candidate.
   */
  loadOwnedCandidates: (
    page: number,
    signal: AbortSignal
  ) => Promise<OperationsOwnedCandidatePage<OperationsMyWorkItem> | null>;
}

export const OperationsRequestCoordination: React.FC<Props> = ({
  requestId,
  blocked,
  busyCommand,
  onCoordinateNewSession,
  onAttachToOwnedDraft,
  onMarkConfirmedUnavailable,
  loadOwnedCandidates
}) => {
  const [mode, setMode] = useState<Mode>('menu');
  const [drafts, setDrafts] = useState<OperationsMyWorkItem[] | null>(null);
  const [draftPage, setDraftPage] = useState(0);
  const [draftPages, setDraftPages] = useState(0);
  const [draftError, setDraftError] = useState(false);
  const [selectedDraft, setSelectedDraft] = useState<number | null>(null);
  const [touched, setTouched] = useState(false);
  const [confirmedUnavailable, setConfirmedUnavailable] = useState(false);
  const headingRef = useRef<HTMLHeadingElement | null>(null);
  const controller = useRef<AbortController | null>(null);

  useEffect(() => () => controller.current?.abort(), []);

  const loadPage = useCallback(
    async (page: number, append: boolean) => {
      controller.current?.abort();
      const next = new AbortController();
      controller.current = next;
      setDraftError(false);
      try {
        const result = await loadOwnedCandidates(page, next.signal);
        if (next.signal.aborted || result === null) return;
        const found = result.items.filter(
          (item) => item.targetType === 'SESSION' && item.status === 'DRAFT' && item.expectedVersion !== null
        );
        setDraftPage(result.page);
        setDraftPages(result.totalPages);
        setDrafts((previous) => {
          const base = append && previous ? previous : [];
          const seen = new Set(base.map((item) => item.targetId));
          return [...base, ...found.filter((item) => !seen.has(item.targetId))];
        });
      } catch {
        if (next.signal.aborted) return;
        setDraftError(true);
      }
    },
    [loadOwnedCandidates]
  );

  const go = useCallback(
    (next: Mode) => {
      setTouched(false);
      setConfirmedUnavailable(false);
      setSelectedDraft(null);
      setMode(next);
      if (next === 'attach') {
        setDrafts(null);
        setDraftPages(0);
        setDraftError(false);
        void loadPage(0, false);
      } else {
        controller.current?.abort();
      }
    },
    [loadPage]
  );

  useEffect(() => {
    if (mode === 'menu') return;
    headingRef.current?.focus();
  }, [mode]);

  if (mode === 'menu') {
    return (
      <div className="oe-actions-list">
        <button
          type="button"
          className="oe-btn oe-btn--primary oe-btn--md oe-btn--block"
          onClick={() => go('new')}
          disabled={blocked}
        >
          <FilePlus2 size={16} aria-hidden="true" />
          Coordinate into a new session
        </button>
        <button
          type="button"
          className="oe-btn oe-btn--secondary oe-btn--md oe-btn--block"
          onClick={() => go('attach')}
          disabled={blocked}
        >
          <Link2 size={16} aria-hidden="true" />
          Add to an existing draft
        </button>
        <button
          type="button"
          className="oe-btn oe-btn--secondary oe-btn--md oe-btn--block"
          onClick={() => go('unavailable')}
          disabled={blocked}
        >
          <Ban size={16} aria-hidden="true" />
          Mark property unavailable
        </button>
        <p className="oe-action-note">
          The server decides whether the property can be coordinated. Recording unavailability is
          separate from an unanswered call.
        </p>
      </div>
    );
  }

  const back = (
    <button type="button" className="oe-back oe-back--inline" onClick={() => go('menu')} disabled={blocked}>
      <ArrowLeft size={14} aria-hidden="true" />
      All actions
    </button>
  );

  if (mode === 'new') {
    return (
      <div className="oe-coord">
        {back}
        <h4 ref={headingRef} tabIndex={-1} className="oe-coord-title">
          Coordinate into a new session
        </h4>
        <ul className="oe-coord-points">
          <li>
            Creates a DRAFT visit session you own, with{' '}
            <span className="oe-mono">{operationsRecordReference(requestId)}</span> as its first stop.
          </li>
          <li>The request moves to Coordinating and is managed from the session afterwards.</li>
          <li>Nothing is booked. You record property availability and choose a Ground Executive next.</li>
        </ul>
        <button
          type="button"
          className="oe-btn oe-btn--primary oe-btn--md oe-btn--block"
          onClick={onCoordinateNewSession}
          disabled={blocked}
        >
          {busyCommand === 'coordinate' ? 'Creating draft session…' : 'Create draft session'}
        </button>
      </div>
    );
  }

  if (mode === 'attach') {
    const invalid = touched && selectedDraft === null;
    return (
      <div className="oe-coord">
        {back}
        <h4 ref={headingRef} tabIndex={-1} className="oe-coord-title">
          Add to an existing draft
        </h4>
        <p className="oe-step-hint">
          These are your owned DRAFT sessions. The server checks that this request may join the one
          you choose; the list itself is not a promise of eligibility.
        </p>
        {draftError ? (
          <div className="oe-notice oe-notice--warn" role="alert">
            <p className="oe-notice-title">Draft sessions couldn’t be loaded</p>
            <p className="oe-notice-body">Nothing was changed. Try again, or coordinate into a new session.</p>
            <div className="oe-notice-actions">
              <button type="button" className="oe-btn oe-btn--secondary oe-btn--sm" onClick={() => void loadPage(0, false)}>
                Try again
              </button>
            </div>
          </div>
        ) : drafts === null ? (
          <p className="oe-ppanel-note" role="status">Checking your owned draft sessions…</p>
        ) : drafts.length === 0 ? (
          <p className="oe-inline-note" role="status">
            No DRAFT session is on the pages loaded from your My Work queue. Coordinate into a new
            session instead, or load more.
          </p>
        ) : (
          <fieldset className="oe-choice-list" aria-describedby={invalid ? 'oe-attach-err' : undefined}>
            <legend className="oe-field-label">Your draft sessions</legend>
            {drafts.map((draft) => (
              <label key={draft.targetId} className="oe-choice">
                <input
                  type="radio"
                  name="oe-attach-draft"
                  value={draft.targetId}
                  checked={selectedDraft === draft.targetId}
                  onChange={() => setSelectedDraft(draft.targetId)}
                />
                <span>
                  <span className="oe-mono oe-choice-title">{operationsRecordReference(draft.targetId)}</span>
                  <span className="oe-choice-sub">
                    Draft · {draft.cityDisplayName ?? 'City not recorded'} ·{' '}
                    {draft.teamDisplayName ?? 'Team not recorded'}
                  </span>
                </span>
              </label>
            ))}
          </fieldset>
        )}
        {invalid ? (
          <p id="oe-attach-err" className="oe-reason-error" role="alert">Choose a draft session.</p>
        ) : null}
        {drafts !== null && !draftError && draftPage < draftPages - 1 ? (
          <button type="button" className="oe-btn oe-btn--secondary oe-btn--sm" onClick={() => void loadPage(draftPage + 1, true)}>
            Load more drafts
          </button>
        ) : null}
        {drafts !== null && drafts.length > 0 ? (
          <>
            <p className="oe-footnote">The request is added as the last stop. You can reorder stops from the session.</p>
            <button
              type="button"
              className="oe-btn oe-btn--primary oe-btn--md oe-btn--block"
              disabled={blocked}
              onClick={() => {
                setTouched(true);
                const chosen = drafts.find((draft) => draft.targetId === selectedDraft);
                if (!chosen || chosen.expectedVersion === null) return;
                onAttachToOwnedDraft(chosen.targetId, chosen.expectedVersion);
              }}
            >
              {busyCommand === 'coordinate' ? 'Adding to draft…' : 'Add to this draft'}
            </button>
          </>
        ) : null}
        {selectedDraft !== null && drafts !== null
          && drafts.find((draft) => draft.targetId === selectedDraft)?.expectedVersion === null ? (
            <p className="oe-reason-error" role="alert">
              That session did not return the version needed to attach this request. Refresh your work
              lists and try again.
            </p>
          ) : null}
      </div>
    );
  }

  const missingConfirmation = touched && !confirmedUnavailable;
  return (
    <div className="oe-coord">
      {back}
      <h4 ref={headingRef} tabIndex={-1} className="oe-coord-title">Mark property unavailable</h4>
      <p className="oe-inline-note oe-inline-note--warn">
        Only use this when the lessor has confirmed the property can’t be visited. An unanswered call
        or a missing reply is not confirmation. This closes the request and can’t be undone here.
      </p>
      <label className="oe-check">
        <input
          type="checkbox"
          checked={confirmedUnavailable}
          onChange={(event) => setConfirmedUnavailable(event.target.checked)}
          aria-invalid={missingConfirmation}
          aria-describedby={missingConfirmation ? 'oe-unavailable-err' : undefined}
        />
        <span>The lessor confirmed this property is unavailable.</span>
      </label>
      {missingConfirmation ? (
        <p id="oe-unavailable-err" className="oe-reason-error" role="alert">Confirm before continuing.</p>
      ) : null}
      <p className="oe-footnote">
        Only the status change is saved. How the lessor was reached, and any note about the call,
        aren’t recorded anywhere in this workspace.
      </p>
      <button
        type="button"
        className="oe-btn oe-btn--danger oe-btn--md oe-btn--block"
        disabled={blocked}
        onClick={() => {
          setTouched(true);
          if (confirmedUnavailable) onMarkConfirmedUnavailable();
        }}
      >
        {busyCommand === 'unavailable' ? 'Recording…' : 'Mark unavailable'}
      </button>
    </div>
  );
};
