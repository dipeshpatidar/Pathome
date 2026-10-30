import React, { useEffect, useRef, useState } from 'react';
import { Check, Copy, Eye, Phone, ShieldCheck } from 'lucide-react';
import type { LeadDetail, LeadShortlist } from '../../utils/operationsLeadPresentation';
import type { Property } from '../../types';
import { propertyService } from '../../services/propertyService';
import {
  captureOperationsSession,
  isOperationsSessionActive,
  isOperationsSessionChangedError,
  subscribeOperationsSession
} from '../../utils/operationsSession';
import {
  leadAssistanceService,
  newClientOperationId,
  type AvailabilityEnquiry,
  type AvailabilityOutcome,
  type LessorContact
} from '../../services/leadAssistanceService';
import { ApiRequestError } from '../../services/apiError';

interface Props {
  lead: LeadDetail;
  shortlist: LeadShortlist;
  onLeadChanged: () => Promise<void>;
}

const REPORTED_OUTCOMES: Array<{ value: AvailabilityOutcome; label: string }> = [
  { value: 'NO_ANSWER', label: 'No answer' },
  { value: 'LESSOR_REPORTED_AVAILABLE', label: 'Lessor reported available' },
  { value: 'LESSOR_REPORTED_UNAVAILABLE', label: 'Lessor reported unavailable' },
  { value: 'CALLBACK_REQUESTED', label: 'Callback requested' },
  { value: 'CONTACT_UNUSABLE', label: 'Contact unusable' },
  { value: 'CUSTOMER_WITHDREW', label: 'Customer withdrew' },
  { value: 'CUSTOMER_DISPUTED', label: 'Customer disputed' },
  { value: 'PROPERTY_DECLINED', label: 'Property declined' }
];

export const LeadLessorAvailabilityPanel: React.FC<Props> = ({
  lead, shortlist, onLeadChanged
}) => {
  const [listingId, setListingId] = useState<number | null>(shortlist.listingIds[0] ?? null);
  const [properties, setProperties] = useState<Map<number, Property>>(() => new Map());
  const [loadingProperties, setLoadingProperties] = useState(true);
  const [attested, setAttested] = useState(false);
  const [busy, setBusy] = useState(false);
  const [enquiry, setEnquiry] = useState<AvailabilityEnquiry | null>(null);
  const [contact, setContact] = useState<LessorContact | null>(null);
  const [outcome, setOutcome] = useState<AvailabilityOutcome | ''>('');
  const [note, setNote] = useState('');
  const [message, setMessage] = useState('');
  const mounted = useRef(true);
  const propertyController = useRef<AbortController | null>(null);
  const sensitiveController = useRef<AbortController | null>(null);
  const contextEpoch = useRef(0);
  const createOperationId = useRef<string | null>(null);
  const outcomeOperationId = useRef<string | null>(null);
  const source = lead.sourceAttributions.find(item => item.state === 'PROCESSED') ?? null;
  const contextKey = `${lead.id}:${lead.version}:${lead.contactPhoneRevision}:${shortlist.id}:${shortlist.version}:${listingId ?? 'none'}:${source?.sourceEventId ?? 'none'}`;
  const currentContext = useRef(contextKey);

  const withdrawContact = () => {
    contextEpoch.current += 1;
    sensitiveController.current?.abort();
    setContact(null);
  };

  const current = (
    epoch: number,
    key: string,
    session: NonNullable<ReturnType<typeof captureOperationsSession>>
  ) => mounted.current
    && epoch === contextEpoch.current
    && key === currentContext.current
    && document.visibilityState === 'visible'
    && isOperationsSessionActive(session);

  useEffect(() => {
    mounted.current = true;
    const abort = new AbortController();
    propertyController.current?.abort();
    propertyController.current = abort;
    setLoadingProperties(true);
    Promise.all(shortlist.listingIds.map(id => propertyService.getPublicProperty(id, abort.signal)
      .then(property => [id, property] as const)))
      .then(entries => {
        if (!mounted.current || abort.signal.aborted) return;
        setProperties(new Map(entries));
      })
      .catch(cause => {
        if (!mounted.current || abort.signal.aborted) return;
        setMessage(cause instanceof Error
          ? 'One or more shortlisted properties are no longer available. Refresh the shortlist before contacting a lessor.'
          : 'Public property facts could not be loaded.');
      })
      .finally(() => {
        if (mounted.current && !abort.signal.aborted) setLoadingProperties(false);
      });
    const unsubscribe = subscribeOperationsSession(() => {
      propertyController.current?.abort();
      withdrawContact();
      setEnquiry(null);
      setProperties(new Map());
      setBusy(false);
      setMessage('Your account changed. The lessor contact was cleared.');
    });
    const hideContact = () => {
      if (document.visibilityState === 'hidden') {
        withdrawContact();
        setBusy(false);
        propertyController.current?.abort();
        setMessage((existing) => existing || 'Sensitive lessor contact was cleared while this screen was hidden.');
      } else {
        currentContext.current = contextKey;
        setMessage((existing) => existing
          || 'Returned to this screen. Reveal lessor contact again when needed.');
      }
    };
    document.addEventListener('visibilitychange', hideContact);
    return () => {
      mounted.current = false;
      abort.abort();
      sensitiveController.current?.abort();
      unsubscribe();
      document.removeEventListener('visibilitychange', hideContact);
    };
  }, [lead.id, shortlist.id, shortlist.version, shortlist.listingIds]);

  useEffect(() => {
    currentContext.current = contextKey;
    contextEpoch.current += 1;
    sensitiveController.current?.abort();
    setContact(null);
  }, [contextKey]);

  useEffect(() => {
    setListingId(shortlist.listingIds[0] ?? null);
    setEnquiry(null);
    setContact(null);
    setAttested(false);
  }, [shortlist.id, shortlist.version]);

  const withSession = async (
    operation: (session: NonNullable<ReturnType<typeof captureOperationsSession>>) => Promise<void>
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
      await operation(session);
    } catch (cause) {
      if (!mounted.current || epoch !== contextEpoch.current || key !== currentContext.current
        || isOperationsSessionChangedError(cause)
        || !isOperationsSessionActive(session)) return;
      const status = cause instanceof ApiRequestError ? cause.status : undefined;
      if (status === 401 || status === 403 || status === 404) {
        withdrawContact();
        setBusy(false);
        setMessage(cause instanceof Error ? cause.message
          : 'Current lessor-contact authorization is no longer available.');
        return;
      }
      if (createOperationId.current && (status === undefined || status === 408 || status >= 500)) {
        try {
          const receipt = await leadAssistanceService.availabilityCommandStatus(
            createOperationId.current, 'LEAD_AVAILABILITY_CREATE', { session }
          );
          if (receipt.state === 'RECORDED' && receipt.resultReference
            && current(epoch, key, session)) {
            const recovered = await leadAssistanceService.getAvailability(
              lead.id, receipt.resultReference, { session }
            );
            if (current(epoch, key, session)) {
              createOperationId.current = null;
              setEnquiry(recovered);
              setMessage(`The pending availability enquiry was recovered. ${recovered.message}`);
              return;
            }
          }
        } catch {
          // Keep the explicit unknown outcome below.
        }
      }
      if (outcomeOperationId.current && (status === undefined || status === 408 || status >= 500)) {
        try {
          const receipt = await leadAssistanceService.availabilityCommandStatus(
            outcomeOperationId.current, 'LEAD_AVAILABILITY_RESPONSE', { session }
          );
          if (receipt.state === 'RECORDED' && receipt.resultReference
            && current(epoch, key, session)) {
            const recovered = await leadAssistanceService.getAvailability(
              lead.id, receipt.resultReference, { session }
            );
            if (current(epoch, key, session)) {
              outcomeOperationId.current = null;
              setEnquiry(recovered);
              setContact(null);
              setOutcome('');
              setNote('');
              setMessage(`The reported outcome was recovered. ${recovered.message}`);
              await onLeadChanged();
              return;
            }
          }
        } catch {
          // Keep the explicit unknown outcome below.
        }
      }
      if (status === 409) {
        await onLeadChanged();
        if (!current(epoch, key, session)) return;
        setMessage(cause instanceof Error ? cause.message : 'The Lead or property changed. Refresh and retry.');
      } else {
        setMessage(cause instanceof Error ? cause.message : 'The availability action could not be completed.');
      }
    } finally {
      if (mounted.current && epoch === contextEpoch.current
        && key === currentContext.current) setBusy(false);
    }
  };

  const createEnquiry = () => withSession(async session => {
    if (!source || !listingId) {
      setMessage('A current processed source and exact shortlisted property are required.');
      return;
    }
    const property = properties.get(listingId);
    if (!property || property.listingVersion == null) {
      setMessage('Refresh the public property facts before contacting the lessor.');
      return;
    }
    const epoch = contextEpoch.current;
    const key = currentContext.current;
    sensitiveController.current?.abort();
    const next = new AbortController();
    sensitiveController.current = next;
    createOperationId.current ??= newClientOperationId();
    const result = await leadAssistanceService.createAvailability(
      lead.id,
      {
        shortlistId: shortlist.id,
        listingId,
        sourceEventId: source.sourceEventId,
        expectedLeadVersion: lead.version,
        expectedShortlistVersion: shortlist.version,
        expectedListingVersion: property.listingVersion,
        genuineEnquiryAttested: attested
      },
      createOperationId.current,
      { session, signal: next.signal }
    );
    if (!current(epoch, key, session)) return;
    createOperationId.current = null;
    setEnquiry(result);
    setContact(null);
    setMessage(result.message);
    if (result.contactRevealEligible) {
      try {
        const revealed = await leadAssistanceService.revealLessorContact(
          lead.id, result, { session, signal: next.signal }
        );
        if (current(epoch, key, session) && !next.signal.aborted) {
          setContact(revealed);
          setMessage(revealed.status === 'CONTACT_AVAILABLE'
            ? 'The pending availability record is saved. Lessor contact was revealed after Pathome recorded this access.'
            : 'The availability record is saved, but lessor contact is unavailable.');
        }
      } catch (cause) {
        if (current(epoch, key, session)) {
          setMessage(`The pending availability enquiry is saved. ${cause instanceof Error
            ? cause.message
            : 'Lessor contact could not be revealed.'}`);
        }
      }
    }
  });

  const reveal = () => withSession(async session => {
    if (!enquiry) return;
    const epoch = contextEpoch.current;
    const key = currentContext.current;
    sensitiveController.current?.abort();
    const next = new AbortController();
    sensitiveController.current = next;
    const result = await leadAssistanceService.revealLessorContact(
      lead.id, enquiry, { session, signal: next.signal }
    );
    if (!current(epoch, key, session) || next.signal.aborted) return;
    setContact(result);
    setMessage(result.status === 'CONTACT_AVAILABLE'
      ? 'Lessor contact revealed after Pathome recorded this access. The phone call happens manually.'
      : 'Lessor contact is unavailable. The pending enquiry remains saved.');
  });

  const saveOutcome = () => withSession(async session => {
    if (!enquiry || !outcome) {
      setMessage('Choose the actual lessor-reported outcome.');
      return;
    }
    const epoch = contextEpoch.current;
    const key = currentContext.current;
    sensitiveController.current?.abort();
    const next = new AbortController();
    sensitiveController.current = next;
    outcomeOperationId.current ??= newClientOperationId();
    const result = await leadAssistanceService.recordAvailabilityOutcome(
      lead.id,
      enquiry,
      lead.version,
      outcome,
      note.trim() || null,
      outcomeOperationId.current,
      { session, signal: next.signal }
    );
    if (!current(epoch, key, session) || next.signal.aborted) return;
    outcomeOperationId.current = null;
    setEnquiry(result);
    setOutcome('');
    setNote('');
    setContact(null);
    setMessage(result.message);
    setBusy(false);
    await onLeadChanged();
  });

  return (
    <section className="ld-card ld-availability" aria-labelledby="ld-lessor-availability">
      <header className="ld-card-head">
        <h2 id="ld-lessor-availability">Lessor availability</h2>
        <span className="oe-badge oe-badge--attention">OE-reported only</span>
      </header>
      <p className="oe-footnote">
        Check one exact shortlisted property. This creates no Visit Request, reservation, Session, GE assignment,
        OTP proof or credit movement.
      </p>

      {loadingProperties ? <span className="oe-skel" style={{ display: 'block', height: '5rem' }} /> : (
        <label className="oe-field-stack">
          <span className="oe-field-label">Shortlisted property</span>
          <select
            className="oe-input ld-input"
            value={listingId ?? ''}
            disabled={Boolean(enquiry) || busy}
            onChange={event => {
              setListingId(Number(event.target.value) || null);
              setEnquiry(null);
              setContact(null);
            }}
          >
            {shortlist.listingIds.map(id => (
              <option key={id} value={id}>
                {properties.get(id)?.title || `Property #${id}`}
              </option>
            ))}
          </select>
        </label>
      )}

      {!enquiry ? (
        <>
          <label className="ld-check">
            <input type="checkbox" checked={attested} onChange={event => setAttested(event.target.checked)} />
            <span>The customer has a current, genuine viewing-availability question about this exact property.</span>
          </label>
          <button
            type="button"
            className="oe-btn oe-btn--primary oe-btn--md oe-btn--block"
            disabled={busy || !attested || !source || !listingId || loadingProperties}
            onClick={() => void createEnquiry()}
          >
            <Phone size={16} aria-hidden="true" />{busy ? 'Checking availability…' : 'Check lessor availability'}
          </button>
        </>
      ) : (
        <>
          <dl className="ld-mini">
            <div><dt>Outcome</dt><dd>{enquiry.outcome.replace(/_/g, ' ')}</dd></div>
            <div><dt>Phone proof</dt><dd>{enquiry.phoneVerificationState === 'VERIFIED' ? 'Current for this source' : 'Pending / unavailable'}</dd></div>
            <div><dt>Expires</dt><dd>{new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(enquiry.expiresAt))}</dd></div>
          </dl>
          {enquiry.contactRevealEligible ? (
            <button type="button" className="oe-btn oe-btn--secondary oe-btn--md oe-btn--block" disabled={busy} onClick={() => void reveal()}>
              <Eye size={16} aria-hidden="true" />{busy ? 'Checking access…' : 'Reveal fresh lessor contact'}
            </button>
          ) : (
            <p className="oe-locked"><ShieldCheck size={15} aria-hidden="true" />Current enquiry-phone proof and exact ownership are required before private contact disclosure.</p>
          )}
          {contact ? (
            contact.status === 'CONTACT_AVAILABLE' ? (
              <div className="ld-contact-reveal" aria-live="polite">
                <p className="oe-field-label">Lessor phone</p>
                <p><strong>{contact.name}</strong></p>
                <p className="oe-mono">{contact.mobile}</p>
                <div className="ld-actions">
                  <button
                    type="button"
                    className="oe-btn oe-btn--ghost oe-btn--sm"
                    onClick={() => {
                      void navigator.clipboard?.writeText(contact.mobile || '');
                    }}
                  >
                    <Copy size={14} aria-hidden="true" />Copy number
                  </button>
                  <button type="button" className="oe-btn oe-btn--ghost oe-btn--sm" onClick={() => setContact(null)}>
                    Clear contact
                  </button>
                </div>
              </div>
            ) : <p className="oe-locked">Lessor contact is unavailable from canonical property data. No fallback contact was used.</p>
          ) : null}

          <div className="ld-availability-outcome">
            <label className="oe-field-stack">
              <span className="oe-field-label">What did the lessor actually report?</span>
              <select className="oe-input ld-input" value={outcome} onChange={event => setOutcome(event.target.value as AvailabilityOutcome | '')}>
                <option value="">Choose outcome</option>
                {REPORTED_OUTCOMES.map(option => <option key={option.value} value={option.value}>{option.label}</option>)}
              </select>
            </label>
            <label className="oe-field-stack">
              <span className="oe-field-label">Call note <span className="oe-quiet">Optional</span></span>
              <textarea className="oe-input ld-input" rows={3} maxLength={4000} value={note} onChange={event => setNote(event.target.value)} />
            </label>
            <button type="button" className="oe-btn oe-btn--secondary oe-btn--md" disabled={busy || !outcome} onClick={() => void saveOutcome()}>
              <Check size={15} aria-hidden="true" />Record reported outcome
            </button>
            <p className="oe-footnote">“Lessor reported available” is not a booking, reserved time or confirmed appointment.</p>
          </div>
        </>
      )}
      {message ? <p className="ld-notice-body" role="status">{message}</p> : null}
      {!source ? <p className="ld-form-error">A processed enquiry source is required.</p> : null}
    </section>
  );
};
