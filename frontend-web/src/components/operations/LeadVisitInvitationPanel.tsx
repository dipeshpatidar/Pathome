import React, { useEffect, useRef, useState } from 'react';
import { Check, Clipboard, Link2, ShieldAlert, X } from 'lucide-react';
import type {
  LeadContactPreference,
  LeadDetail,
  LeadShortlist
} from '../../utils/operationsLeadPresentation';
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
  type VisitProposal
} from '../../services/leadAssistanceService';
import { ApiRequestError } from '../../services/apiError';

interface Props {
  lead: LeadDetail;
  shortlist: LeadShortlist;
  preference: LeadContactPreference;
  onLeadChanged: () => Promise<void>;
}

export const LeadVisitInvitationPanel: React.FC<Props> = ({
  lead, shortlist, preference, onLeadChanged
}) => {
  const [listingId, setListingId] = useState<number | null>(shortlist.listingIds[0] ?? null);
  const [properties, setProperties] = useState<Map<number, Property>>(() => new Map());
  const [proposal, setProposal] = useState<VisitProposal | null>(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const mounted = useRef(true);
  const operationId = useRef<string | null>(null);
  const controller = useRef<AbortController | null>(null);

  useEffect(() => {
    mounted.current = true;
    const loadController = new AbortController();
    controller.current?.abort();
    controller.current = loadController;
    Promise.all(shortlist.listingIds.map(id => propertyService.getPublicProperty(id, loadController.signal)
      .then(property => [id, property] as const)))
      .then(entries => {
        if (mounted.current && !loadController.signal.aborted) setProperties(new Map(entries));
      })
      .catch(() => {
        if (mounted.current && !loadController.signal.aborted) {
          setMessage('The exact public property facts could not be loaded. Refresh before preparing an invitation.');
        }
      });
    const unsubscribe = subscribeOperationsSession(() => {
      controller.current?.abort();
      setProposal(null);
      setProperties(new Map());
      setMessage('Your account changed. The Visit invitation link was cleared.');
    });
    return () => {
      mounted.current = false;
      loadController.abort();
      unsubscribe();
    };
  }, [lead.id, shortlist.id, shortlist.version, shortlist.listingIds]);

  const issue = async () => {
    if (busy) return;
    const property = listingId == null ? null : properties.get(listingId);
    if (!property || property.listingVersion == null) {
      setMessage('Choose a current shortlisted property before preparing the invitation.');
      return;
    }
    const session = captureOperationsSession();
    if (!session) {
      setMessage('Your session has ended.');
      return;
    }
    setBusy(true);
    setMessage('');
    operationId.current ??= newClientOperationId();
    try {
      const result = await leadAssistanceService.createProposal(
        lead.id,
        {
          expectedLeadVersion: lead.version,
          shortlistId: shortlist.id,
          listingId: property.id,
          expectedShortlistVersion: shortlist.version,
          expectedListingVersion: property.listingVersion,
          contactPreferenceRevision: preference.revision
        },
        operationId.current,
        { session, signal: controller.current?.signal }
      );
      if (!mounted.current || !isOperationsSessionActive(session)) return;
      operationId.current = null;
      setProposal(result);
      setMessage(result.handoffUrl
        ? 'Invitation prepared. Send the exact public-property link; the customer must still sign in and explicitly request the visit.'
        : 'The invitation was already recorded, but its hash-only secure link cannot be reproduced. Revoke it and issue a new invitation.');
      await onLeadChanged();
    } catch (cause) {
      if (!mounted.current || isOperationsSessionChangedError(cause)
        || !isOperationsSessionActive(session)) return;
      const status = cause instanceof ApiRequestError ? cause.status : undefined;
      if (operationId.current && (status === undefined || status === 408 || status >= 500)) {
        try {
          const receipt = await leadAssistanceService.proposalCommandStatus(
            lead.id, operationId.current, 'LEAD_VISIT_PROPOSAL_CREATE', { session }
          );
          if (receipt.state === 'RECORDED' && receipt.resultReference
            && isOperationsSessionActive(session)) {
            const recovered = await leadAssistanceService.getProposal(
              lead.id, receipt.resultReference, { session }
            );
            if (mounted.current && isOperationsSessionActive(session)) {
              setProposal(recovered);
              setMessage('The invitation was recorded, but its hash-only link cannot be recreated. Revoke this invitation and prepare a new one.');
              operationId.current = null;
              return;
            }
          }
        } catch {
          // Keep the explicit unknown outcome below.
        }
      }
      setMessage(cause instanceof Error ? cause.message : 'The invitation could not be prepared.');
    } finally {
      if (mounted.current) setBusy(false);
    }
  };

  const refresh = async () => {
    if (!proposal || busy) return;
    const session = captureOperationsSession();
    if (!session) return;
    try {
      const current = await leadAssistanceService.getProposal(
        lead.id, proposal.proposalId, { session }
      );
      if (mounted.current && isOperationsSessionActive(session)) {
        setProposal(current);
        setMessage(current.associationStatus === 'PENDING'
          ? 'The customer requested their own Visit and consented to an optional association review.'
          : 'Invitation status refreshed.');
      }
    } catch (cause) {
      if (mounted.current && !isOperationsSessionChangedError(cause)) {
        setMessage(cause instanceof Error ? cause.message : 'Invitation status could not be refreshed.');
      }
    }
  };

  const decide = async (decision: 'CONFIRM' | 'DISPUTE', claimId: string,
                        expectedClaimVersion: number) => {
    if (!proposal || busy) return;
    const session = captureOperationsSession();
    if (!session) return;
    setBusy(true);
    try {
      const result = await leadAssistanceService.decideAssociation(
        lead.id,
        proposal,
        claimId,
        expectedClaimVersion,
        decision,
        decision === 'CONFIRM' ? 'CUSTOMER_CONFIRMED_OWNERSHIP' : 'CURRENT_OE_COULD_NOT_CONFIRM',
        newClientOperationId(),
        { session }
      );
      if (!mounted.current || !isOperationsSessionActive(session)) return;
      setMessage(result.message);
      const current = await leadAssistanceService.getProposal(
        lead.id, proposal.proposalId, { session }
      );
      if (mounted.current && isOperationsSessionActive(session)) setProposal(current);
      await onLeadChanged();
    } catch (cause) {
      if (mounted.current && !isOperationsSessionChangedError(cause)) {
        setMessage(cause instanceof Error ? cause.message : 'The association decision could not be saved.');
      }
    } finally {
      if (mounted.current) setBusy(false);
    }
  };

  const revoke = async () => {
    if (!proposal || busy) return;
    const session = captureOperationsSession();
    if (!session) return;
    setBusy(true);
    try {
      const result = await leadAssistanceService.revokeProposal(
        lead.id, proposal.proposalId, newClientOperationId(), { session }
      );
      if (!mounted.current || !isOperationsSessionActive(session)) return;
      setProposal(result);
      setMessage('The previous invitation is revoked. Issue a new invitation if the customer still wants to continue.');
    } catch (cause) {
      if (mounted.current && !isOperationsSessionChangedError(cause)) {
        setMessage(cause instanceof Error ? cause.message : 'The invitation could not be revoked.');
      }
    } finally {
      if (mounted.current) setBusy(false);
    }
  };

  return (
    <section className="ld-card" aria-labelledby="ld-visit-invitation">
      <h2 id="ld-visit-invitation">Customer Visit invitation</h2>
      <p className="oe-footnote">
        The link opens the public property. Signing in and pressing “Request visit” remain deliberate
        customer actions. The customer’s account is always used; this never impersonates them.
      </p>
      {!proposal ? (
        <>
          <label className="oe-field-stack">
            <span className="oe-field-label">Exact shortlisted property</span>
            <select
              className="oe-input ld-input"
              value={listingId ?? ''}
              onChange={event => setListingId(Number(event.target.value) || null)}
            >
              {shortlist.listingIds.map(id => (
                <option key={id} value={id}>{properties.get(id)?.title || `Property #${id}`}</option>
              ))}
            </select>
          </label>
          <button type="button" className="oe-btn oe-btn--secondary oe-btn--md oe-btn--block" disabled={busy} onClick={() => void issue()}>
            <Link2 size={16} aria-hidden="true" />{busy ? 'Preparing…' : 'Prepare 30-minute Visit invitation'}
          </button>
        </>
      ) : (
        <>
          <dl className="ld-mini">
            <div><dt>Invitation</dt><dd>{proposal.state}</dd></div>
            <div><dt>Association</dt><dd>{proposal.associationStatus?.replace(/_/g, ' ') || 'Not requested'}</dd></div>
            <div><dt>Expires</dt><dd>{new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(proposal.expiresAt))}</dd></div>
          </dl>
          {proposal.handoffUrl ? (
            <div className="ld-invite-link">
              <p className="oe-field-label">Exact public-property invitation</p>
              <code>{proposal.handoffUrl}</code>
              <button
                type="button"
                className="oe-btn oe-btn--ghost oe-btn--sm"
                onClick={() => void navigator.clipboard?.writeText(proposal.handoffUrl || '')
                  .then(() => setMessage('Invitation link copied. Pathome cannot confirm whether it was sent.'))
                  .catch(() => setMessage('The link could not be copied. Select it manually.'))}
              >
                <Clipboard size={14} aria-hidden="true" />Copy link
              </button>
            </div>
          ) : null}
          {proposal.associationClaims.length ? (
            <div className="ld-availability-outcome">
              <p className="oe-field-label">Optional enquiry-association claims</p>
              {proposal.associationClaims.map(claim => (
                <div key={claim.claimId} className="ld-mini">
                  <div><dt>Request</dt><dd>#{claim.requestId}</dd></div>
                  <div><dt>State</dt><dd>{claim.state.replace(/_/g, ' ')}</dd></div>
                  {claim.state === 'PENDING' ? (
                    <div className="ld-actions">
                      <button type="button" className="oe-btn oe-btn--primary oe-btn--sm" disabled={busy} onClick={() => void decide('CONFIRM', claim.claimId, claim.version)}>
                        <Check size={14} aria-hidden="true" />Confirm association
                      </button>
                      <button type="button" className="oe-btn oe-btn--secondary oe-btn--sm" disabled={busy} onClick={() => void decide('DISPUTE', claim.claimId, claim.version)}>
                        <ShieldAlert size={14} aria-hidden="true" />Dispute
                      </button>
                    </div>
                  ) : null}
                </div>
              ))}
            </div>
          ) : null}
          <div className="ld-actions">
            <button type="button" className="oe-btn oe-btn--ghost oe-btn--sm" disabled={busy} onClick={() => void refresh()}>
              Check invitation status
            </button>
            {proposal.state === 'OPEN' ? (
              <button type="button" className="oe-btn oe-btn--ghost oe-btn--sm" disabled={busy} onClick={() => void revoke()}>
                <X size={14} aria-hidden="true" />Revoke
              </button>
            ) : (
              <button type="button" className="oe-btn oe-btn--secondary oe-btn--sm" disabled={busy} onClick={() => {
                setProposal(null);
                setMessage('');
              }}>
                Prepare another
              </button>
            )}
          </div>
        </>
      )}
      {message ? <p className="ld-notice-body" role="status">{message}</p> : null}
    </section>
  );
};
