import React, { useCallback, useEffect, useRef, useState } from 'react';
import { CircleAlert, EyeOff, Phone, ShieldAlert, UserRound } from 'lucide-react';
import { ApiRequestError } from '../../services/apiError';
import type { OperationsContact } from '../../utils/operationsWorkspacePresentation';
import {
  beginContactReveal,
  contactRevealIsCurrent,
  createContactRevealGate,
  resetContactRevealGate,
  type ContactRevealGate,
  type ContactRevealTicket
} from '../../utils/operationsContactReveal';

/**
 * Canonical lessor contact, revealed on demand for one authorized operational root.
 *
 * The backend route is the only permitted source of lessor contact; it is read when asked, never
 * cached and never persisted. Protected data therefore lives in this component's memory only and is
 * dropped whenever the root, its version, the signed-in identity, the workspace authority or the
 * detail itself changes — `contextKey` is that identity, so a changed key resets the reveal before
 * any old name or number can be read.
 *
 * A refused read (`403`/`404`) withdraws the protected value in place: the name and number are dropped
 * and the reason is stated honestly, so a lost owner or a hidden resource can never leave a previously
 * revealed contact on screen.
 *
 * Clearing on a context change is not sufficient on its own: a reveal that was already in flight can
 * resolve after the context moved (the same record at a new version keeps this component mounted), so
 * every reveal carries a generation ticket and an abortable transport. An outdated answer is dropped.
 *
 * Hiding the document is the same kind of invalidation: the revealed contact is cleared, the pending
 * reveal's generation is invalidated before its transport is aborted, and coming back requires a new
 * deliberate reveal.
 */

export type LessorContactReveal = (signal: AbortSignal) => Promise<OperationsContact>;

interface Props {
  /** Identity of the root and version this reveal belongs to. A new value clears protected state. */
  contextKey: string;
  /** Performs the guarded read through the shared session-captured transport. */
  reveal: LessorContactReveal;
  /** Name of the property or item this contact belongs to, used for accessible labels. */
  subject: string;
  compact?: boolean;
  /** Copy for the protected subject. Lessor behaviour remains the default. */
  variant?: 'lessor' | 'tenant';
}

type ContactState =
  | { kind: 'hidden' }
  | { kind: 'loading' }
  | { kind: 'shown'; contact: OperationsContact }
  | { kind: 'unavailable' }
  | { kind: 'lost' }
  | { kind: 'error' };

/** Auto-hide window for revealed contact, matching the approved on-demand presentation. */
const CONTACT_AUTO_HIDE_MS = 120_000;

/** Only a plainly valid number may become a call link; anything else is shown as recorded text. */
const CALLABLE_MOBILE = /^\+?[0-9][0-9\s-]{5,}$/;

export const OperationsLessorContactPanel: React.FC<Props> = ({
  contextKey,
  reveal,
  subject,
  compact = false,
  variant = 'lessor'
}) => {
  const [state, setState] = useState<ContactState>({ kind: 'hidden' });
  const timers = useRef<number[]>([]);
  const regionRef = useRef<HTMLDivElement | null>(null);
  const triggerRef = useRef<HTMLButtonElement | null>(null);
  /**
   * The live reveal generation and the transport of the reveal it belongs to. Both are refs, so the
   * "may this answer be displayed?" decision is taken synchronously in the same tick as the write.
   */
  const gateRef = useRef<ContactRevealGate>(createContactRevealGate());
  const revealController = useRef<AbortController | null>(null);
  const isTenant = variant === 'tenant';

  const clearTimers = useCallback(() => {
    timers.current.forEach((id) => window.clearTimeout(id));
    timers.current = [];
  }, []);

  /**
   * A new root, version, identity or authority clears every revealed value immediately *and*
   * invalidates the reveal that was in flight, so a late answer cannot repopulate the panel under the
   * context that replaced it.
   */
  useEffect(() => {
    clearTimers();
    gateRef.current = resetContactRevealGate(gateRef.current, contextKey);
    revealController.current?.abort();
    revealController.current = null;
    setState({ kind: 'hidden' });
    return () => {
      // Unmounting invalidates the generation before aborting, so nothing can commit into a panel
      // that no longer exists.
      gateRef.current = resetContactRevealGate(gateRef.current, null);
      revealController.current?.abort();
      revealController.current = null;
      clearTimers();
    };
  }, [contextKey, clearTimers]);

  /**
   * Hiding the document invalidates the revealed contact immediately.
   *
   * A protected name and number must not stay resident in a backgrounded tab, and a reveal that is
   * still in flight must not be able to repopulate the panel when the document becomes visible again:
   * the generation is invalidated *before* the transport is aborted, so even a transport that ignores
   * abort cannot commit its answer. Returning to the document therefore requires another deliberate
   * on-demand reveal. Focus is deliberately not moved — this document is hidden, and focusing a
   * control here would pull the caret away from whatever the user moved to.
   */
  useEffect(() => {
    if (typeof document === 'undefined' || typeof document.addEventListener !== 'function') return;
    const onVisibilityChange = () => {
      if (document.visibilityState !== 'hidden') return;
      clearTimers();
      gateRef.current = resetContactRevealGate(gateRef.current, null);
      revealController.current?.abort();
      revealController.current = null;
      setState({ kind: 'hidden' });
    };
    document.addEventListener('visibilitychange', onVisibilityChange);
    return () => document.removeEventListener('visibilitychange', onVisibilityChange);
  }, [clearTimers]);

  const hide = useCallback(() => {
    clearTimers();
    gateRef.current = resetContactRevealGate(gateRef.current, contextKey);
    revealController.current?.abort();
    revealController.current = null;
    setState({ kind: 'hidden' });
    triggerRef.current?.focus();
  }, [clearTimers, contextKey]);

  const request = useCallback(async () => {
    clearTimers();
    // A newer reveal supersedes the previous ticket and transport for this context.
    const started = beginContactReveal(gateRef.current, contextKey);
    gateRef.current = started.gate;
    const ticket: ContactRevealTicket = started.ticket;
    revealController.current?.abort();
    const controller = new AbortController();
    revealController.current = controller;
    /** True while this exact reveal is still the live one for the live context. */
    const stillLive = (): boolean =>
      !controller.signal.aborted
      && revealController.current === controller
      && contactRevealIsCurrent(gateRef.current, ticket);
    setState({ kind: 'loading' });
    try {
      const contact = await reveal(controller.signal);
      if (!stillLive()) return;
      if (contact.status === 'CONTACT_AVAILABLE' && contact.name && contact.mobile) {
        setState({ kind: 'shown', contact });
        timers.current.push(window.setTimeout(() => setState({ kind: 'hidden' }), CONTACT_AUTO_HIDE_MS));
        return;
      }
      setState({ kind: 'unavailable' });
    } catch (cause) {
      // An aborted or superseded reveal is not an outcome: nothing may be displayed for it.
      if (!stillLive()) return;
      const status = cause instanceof ApiRequestError ? cause.status : undefined;
      if (status === 403 || status === 404) {
        setState({ kind: 'lost' });
        return;
      }
      setState({ kind: 'error' });
    }
  }, [clearTimers, contextKey, reveal]);

  return (
    <div className={`oe-contact${compact ? ' oe-contact--compact' : ''}`} ref={regionRef}>
      {state.kind === 'hidden' || state.kind === 'loading' ? (
        <button
          ref={triggerRef}
          type="button"
          className="oe-btn oe-btn--secondary oe-btn--sm"
          onClick={() => void request()}
          disabled={state.kind === 'loading'}
          /**
           * The accessible name starts with the visible label in both states, so the control is
           * announced exactly as it reads (WCAG "Label in Name") while loading and while idle.
           */
          aria-label={`${state.kind === 'loading' ? 'Loading contact…' : `Show ${isTenant ? 'tenant' : 'lessor'} contact`} for ${subject}`}
        >
          <UserRound size={14} aria-hidden="true" />
          {state.kind === 'loading' ? 'Loading contact…' : `Show ${isTenant ? 'tenant' : 'lessor'} contact`}
        </button>
      ) : null}

      <div className="oe-contact-live" aria-live="polite">
        {state.kind === 'shown' ? (
          <div className="oe-contact-card">
            <dl className="oe-contact-fields">
              <div>
                <dt>{isTenant ? 'Tenant' : 'Lessor'}</dt>
                <dd>{state.contact.name}</dd>
              </div>
              <div>
                <dt>Phone</dt>
                <dd className="oe-num">{state.contact.mobile}</dd>
              </div>
            </dl>
            <div className="oe-contact-actions">
              {CALLABLE_MOBILE.test(state.contact.mobile ?? '') ? (
                <a
                  className="oe-btn oe-btn--primary oe-btn--sm"
                  href={`tel:${(state.contact.mobile as string).replace(/[\s-]/g, '')}`}
                >
                  <Phone size={14} aria-hidden="true" />
                  {isTenant ? 'Call tenant' : 'Call lessor'}
                </a>
              ) : null}
              <button type="button" className="oe-btn oe-btn--secondary oe-btn--sm" onClick={hide}>
                <EyeOff size={14} aria-hidden="true" />
                Hide
              </button>
            </div>
            <p className="oe-footnote">
              Hidden again after two minutes, when you leave this record, or when its details change.
              It is also cleared as soon as this tab is hidden. This workspace doesn’t record calls or
              notes{isTenant ? '.' : ', so record availability after you speak with the lessor.'}
            </p>
          </div>
        ) : state.kind === 'unavailable' ? (
          <div className="oe-contact-card">
            <p className="oe-contact-msg">
              {isTenant
                ? 'The tenant’s account has no usable name and mobile on record. Contact is only ever shown when the backend can resolve it for you.'
                : 'No canonical lessor contact is on record for this property. Contact is only ever shown when the backend can resolve it for you.'}
            </p>
            <button type="button" className="oe-link" onClick={hide}>Dismiss</button>
          </div>
        ) : state.kind === 'lost' ? (
          <div className="oe-contact-card oe-contact-card--warn" role="alert">
            <p className="oe-contact-msg">
              <ShieldAlert size={14} aria-hidden="true" />
              You no longer have access to this {isTenant ? 'tenant’s' : 'lessor’s'} contact.
            </p>
            <button type="button" className="oe-link" onClick={hide}>Dismiss</button>
          </div>
        ) : state.kind === 'error' ? (
          <div className="oe-contact-card oe-contact-card--warn" role="alert">
            <p className="oe-contact-msg">
              <CircleAlert size={14} aria-hidden="true" />
              The contact couldn’t be loaded, so nothing is shown. This doesn’t change the record.
            </p>
            <div className="oe-contact-actions">
              <button type="button" className="oe-btn oe-btn--secondary oe-btn--sm" onClick={() => void request()}>
                Try again
              </button>
              <button type="button" className="oe-link" onClick={hide}>Cancel</button>
            </div>
          </div>
        ) : null}
      </div>
    </div>
  );
};
