import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Check, KeyRound, RefreshCw, ShieldCheck } from 'lucide-react';
import type { LeadDetail } from '../../utils/operationsLeadPresentation';
import {
  captureOperationsSession,
  isOperationsSessionActive,
  isOperationsSessionChangedError
} from '../../utils/operationsSession';
import { ApiRequestError } from '../../services/apiError';
import {
  leadAssistanceService,
  newClientOperationId,
  type PhoneVerification
} from '../../services/leadAssistanceService';

interface Props {
  lead: Pick<LeadDetail, 'id' | 'version' | 'maskedContact' | 'sourceAttributions' | 'contactPhoneRevision'>;
  onChanged: () => void;
}

const statusText = (verification: PhoneVerification): string => {
  if (verification.state === 'VERIFIED') {
    return verification.message;
  }
  if (verification.state === 'PENDING' && verification.deliveryState === 'SUPPRESSED') {
    return 'SMS confirmation is unavailable here. The Lead and operator follow-up remain saved.';
  }
  if (verification.state === 'PENDING') return verification.message;
  return verification.message;
};

export const LeadPhoneVerificationPanel: React.FC<Props> = ({ lead, onChanged }) => {
  const [verification, setVerification] = useState<PhoneVerification | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [noticeAgreed, setNoticeAgreed] = useState(false);
  const [operatorCode, setOperatorCode] = useState('');
  const [message, setMessage] = useState('');
  const mounted = useRef(true);
  const controller = useRef<AbortController | null>(null);
  const source = lead.sourceAttributions.find(item => item.state === 'PROCESSED') ?? null;
  const contextEpoch = useRef(0);
  const contextKey = `${lead.id}:${lead.version}:${lead.contactPhoneRevision}:${source?.sourceEventId ?? 'none'}`;
  const currentContext = useRef(contextKey);

  const load = useCallback(async () => {
    const epoch = contextEpoch.current;
    controller.current?.abort();
    const next = new AbortController();
    controller.current = next;
    const session = captureOperationsSession();
    if (!session) {
      setLoading(false);
      setMessage('Your session has ended.');
      return;
    }
    setLoading(true);
    setMessage('');
    try {
      const result = await leadAssistanceService.latestPhone(lead.id, {
        session,
        signal: next.signal
      });
      if (!mounted.current || next.signal.aborted || epoch !== contextEpoch.current
        || currentContext.current !== contextKey || !isOperationsSessionActive(session)) return;
      setVerification(result);
    } catch (cause) {
      if (!mounted.current || next.signal.aborted || epoch !== contextEpoch.current
        || currentContext.current !== contextKey || isOperationsSessionChangedError(cause)
        || !isOperationsSessionActive(session)) return;
      if (cause instanceof ApiRequestError && cause.status === 404) {
        setVerification(null);
      } else {
        setMessage(cause instanceof Error ? cause.message : 'Phone verification status could not be loaded.');
      }
    } finally {
      if (mounted.current && !next.signal.aborted && epoch === contextEpoch.current
        && currentContext.current === contextKey) setLoading(false);
    }
  }, [contextKey, lead.id]);

  useEffect(() => {
    mounted.current = true;
    currentContext.current = contextKey;
    contextEpoch.current += 1;
    void load();
    return () => {
      mounted.current = false;
      controller.current?.abort();
    };
  }, [contextKey, load]);

  const run = async (
    operation: (session: NonNullable<ReturnType<typeof captureOperationsSession>>) => Promise<PhoneVerification>,
    recovery?: { operationId: string; action: 'LEAD_PHONE_ISSUE' | 'LEAD_PHONE_RESEND' | 'LEAD_PHONE_CONFIRM' }
  ) => {
    if (busy) return;
    const session = captureOperationsSession();
    if (!session) {
      setMessage('Your session has ended.');
      return;
    }
    const epoch = contextEpoch.current;
    const key = currentContext.current;
    setBusy(true);
    setMessage('');
    try {
      const result = await operation(session);
      if (!mounted.current || epoch !== contextEpoch.current || key !== currentContext.current
        || !isOperationsSessionActive(session)) return;
      setVerification(result);
      setOperatorCode('');
      onChanged();
    } catch (cause) {
      if (!mounted.current || epoch !== contextEpoch.current || key !== currentContext.current
        || isOperationsSessionChangedError(cause)
        || !isOperationsSessionActive(session)) return;
      const status = cause instanceof ApiRequestError ? cause.status : undefined;
      if (recovery && (status === undefined || status === 408 || status >= 500)) {
        try {
          const receipt = await leadAssistanceService.phoneCommandStatus(
            lead.id, recovery.operationId, recovery.action, { session }
          );
          if (receipt.state === 'RECORDED' && epoch === contextEpoch.current
            && key === currentContext.current && isOperationsSessionActive(session)) {
            await load();
            setMessage('The earlier phone command was recorded. Current challenge state was reloaded; no second send was attempted.');
            return;
          }
        } catch {
          // Keep the explicit unknown outcome below.
        }
      }
      if (status === 400 || status === 409 || status === 410 || status === 429
        || status === 503 || status === undefined || status >= 500) {
        await load();
      }
      if (!mounted.current || epoch !== contextEpoch.current || key !== currentContext.current
        || !isOperationsSessionActive(session)) return;
      setMessage(cause instanceof Error ? cause.message : 'Phone verification could not be completed.');
    } finally {
      if (mounted.current && epoch === contextEpoch.current
        && key === currentContext.current) setBusy(false);
    }
  };

  if (loading && !verification) {
    return (
      <section className="ld-card" aria-labelledby="ld-phone-proof" aria-busy="true">
        <h2 id="ld-phone-proof">Enquiry phone proof</h2>
        <span className="oe-skel" style={{ display: 'block', height: '5rem' }} />
      </section>
    );
  }

  const send = () => {
    if (!source) {
      setMessage('A current processed enquiry source is required before sending a code.');
      return;
    }
    const operationId = newClientOperationId();
    void run(session => leadAssistanceService.issuePhone(
      lead.id,
      {
        expectedLeadVersion: lead.version,
        sourceEvent: { id: source.sourceEventId },
        expectedContactPhoneRevision: lead.contactPhoneRevision,
        noticeAgreed
      },
      operationId,
      { session }
    ), { operationId, action: 'LEAD_PHONE_ISSUE' });
  };

  return (
    <section className="ld-card" aria-labelledby="ld-phone-proof">
      <h2 id="ld-phone-proof">Enquiry phone proof</h2>
      <p className="oe-footnote">
        This proves possession of the enquiry number only. It does not sign anyone in, verify a Pathome account,
        or replace Visit-start OTP.
      </p>
      {verification ? (
        <div className="ld-phone-proof-state">
          <p className="oe-mono">{verification.maskedDestination || lead.maskedContact || 'Masked number unavailable'}</p>
          <p className="ld-meta">
            {verification.state.replace(/_/g, ' ')}
            {verification.entryMethod ? ` · ${verification.entryMethod.replace(/_/g, ' ')}` : ''}
          </p>
          <p className={verification.state === 'VERIFIED' ? 'ld-notice-body' : 'oe-footnote'} aria-live="polite">
            {statusText(verification)}
          </p>
        </div>
      ) : (
        <p className="oe-quiet">No enquiry-phone code has been requested.</p>
      )}

      {message ? <p className="ld-form-error" role="alert">{message}</p> : null}

      {!verification || verification.state === 'EXPIRED' || verification.state === 'EXHAUSTED'
        || verification.state === 'CANCELLED'
        ? (
          <>
            <label className="ld-check">
              <input
                type="checkbox"
                checked={noticeAgreed}
                onChange={event => setNoticeAgreed(event.target.checked)}
              />
              <span>I explained this is enquiry-phone proof only, not account login or visit booking, and the customer agrees to receive the code.</span>
            </label>
            <button
              type="button"
              className="oe-btn oe-btn--secondary oe-btn--md oe-btn--block"
              disabled={busy || !noticeAgreed || !source}
              onClick={send}
            >
              <KeyRound size={16} aria-hidden="true" />
              {busy ? 'Requesting…' : 'Send enquiry code'}
            </button>
          </>
        ) : null}

      {verification?.state === 'PENDING' ? (
        <button
          type="button"
          className="oe-btn oe-btn--ghost oe-btn--md oe-btn--block"
          disabled={busy || verification.deliveryState === 'SUPPRESSED'}
          onClick={() => {
            const operationId = newClientOperationId();
            void run(session => leadAssistanceService.resendPhone(
              lead.id, lead.version, verification, operationId, { session }
            ), { operationId, action: 'LEAD_PHONE_RESEND' });
          }}
        >
          <RefreshCw size={15} aria-hidden="true" />Resend after cooldown
        </button>
      ) : null}

      {verification?.state === 'PENDING' ? (
        <form
          className="ld-phone-operator"
          onSubmit={event => {
            event.preventDefault();
            if (!/^\d{6}$/.test(operatorCode)) {
              setMessage('Enter the six-digit code exactly once.');
              return;
            }
            const operationId = newClientOperationId();
            void run(session => leadAssistanceService.confirmPhoneAsOperator(
              lead.id, lead.version, verification, operatorCode, operationId, { session }
            ), { operationId, action: 'LEAD_PHONE_CONFIRM' });
          }}
        >
          <label className="oe-field-stack">
            <span className="oe-field-label">Feature-phone fallback · customer dictates the code</span>
            <input
              className="oe-input ld-input oe-mono"
              inputMode="numeric"
              pattern="[0-9]{6}"
              maxLength={6}
              autoComplete="one-time-code"
              value={operatorCode}
              onChange={event => setOperatorCode(event.target.value.replace(/\D/g, '').slice(0, 6))}
            />
          </label>
          <button type="submit" className="oe-btn oe-btn--secondary oe-btn--md" disabled={busy || operatorCode.length !== 6}>
            {busy ? 'Checking…' : 'Confirm dictated code'}
          </button>
        </form>
      ) : null}

      {verification?.state === 'VERIFIED' && verification.currentProofUsable ? (
        <p className="oe-locked"><ShieldCheck size={15} aria-hidden="true" /><Check size={14} aria-hidden="true" />
          Current for source, phone revision {verification.boundContactPhoneRevision}
          {verification.proofUsableUntil
            ? ` · usable until ${new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(verification.proofUsableUntil))}`
            : ''}
        </p>
      ) : null}
      {verification?.state === 'VERIFIED' && !verification.currentProofUsable ? (
        <p className="oe-quiet">Historical confirmation only. It is not usable for the current phone/source context.</p>
      ) : null}
      {!source ? <p className="oe-footnote">A processed source is required before this control can send a code.</p> : null}
      {verification?.state === 'PENDING' ? (
        <p className="oe-footnote">The default customer form is in the SMS. The OE-entered field is an explicitly labelled feature-phone fallback.</p>
      ) : null}
    </section>
  );
};
