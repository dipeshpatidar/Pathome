import React from 'react';
import { CalendarPlus, CircleAlert, ListOrdered, LoaderCircle, Lock, MapPin, RefreshCw, SearchX } from 'lucide-react';
import {
  attentionCategory,
  formatOperationsDate,
  formatOperationsDetailDateTime,
  formatOperationsDuration,
  formatOperationsTime,
  formatOperationsZonedDateTime,
  formatOperationsZonedWindow,
  formatStoredLocalDateTime,
  isOwnedRequestReleasable,
  isOwnedSessionReleasable,
  isTerminalOperationsStatus,
  linkedRequestsLabel,
  needsCoordinatorAttention,
  operationsAvailabilityMeta,
  operationsNoun,
  operationsRecordReference,
  operationsRecordTitle,
  operationsStatusMeta,
  requestHasCompleteWindow,
  schedulingBlockersForSession,
  sessionItemOriginLabel,
  unavailableActiveStopCount,
  type ClaimableWorkItem,
  type OperationsLessorContact,
  type OperationsMyWorkItem,
  type OperationsRequestDetail,
  type OperationsSessionDetail,
  type OperationsTargetType,
  type OperationsTenantContact
} from '../../utils/operationsWorkspacePresentation';
import {
  Field,
  MissingValue,
  StateMessage,
  StatusBadge,
  TenantConfirmationLabel,
  TypeTag
} from './OperationsWorkspacePrimitives';
import { OperationsLessorContactPanel } from './OperationsLessorContactPanel';
import {
  OperationsRequestCoordination,
  type OperationsOwnedCandidatePage
} from './OperationsRequestCoordination';
import {
  OperationsDraftItinerary,
  type AddStopInput,
  type DiscoveryListingOption,
  type OperationsAvailabilityEditing
} from './OperationsDraftItinerary';

export type OperationsDetailKind = 'claimable' | 'request' | 'session';

export interface OperationsDetailSnapshot {
  kind: OperationsDetailKind;
  claimable?: ClaimableWorkItem;
  request?: OperationsRequestDetail;
  session?: OperationsSessionDetail;
}

export type OperationsDetailState =
  | { status: 'loading' }
  | { status: 'error' }
  | { status: 'missing' }
  | { status: 'ready'; snapshot: OperationsDetailSnapshot };

/**
 * Everything an owned record can do, supplied by the workspace.
 *
 * Each entry runs through the workspace's single guarded command lifecycle, so the detail never
 * transmits a command itself and never invents an outcome.
 */
export interface OperationsDetailActions {
  onRelease: () => void;
  onCoordinateNewSession: () => void;
  onAttachToOwnedDraft: (sessionId: number, sessionVersion: number) => void;
  onMarkConfirmedUnavailable: () => void;
  onOpenScheduling: () => void;
  onReorderItems: (itemIds: number[]) => void;
  onRemoveItem: (itemId: number, reason: string | null) => void;
  onAddStop: (input: AddStopInput) => void;
  /** Reveals the canonical contact; the signal cancels the read when the contact context changes. */
  revealRequestContact: (signal: AbortSignal) => Promise<OperationsLessorContact>;
  revealItemContact: (itemId: number, signal: AbortSignal) => Promise<OperationsLessorContact>;
  revealRequestTenantContact: (signal: AbortSignal) => Promise<OperationsTenantContact>;
  revealSessionTenantContact: (signal: AbortSignal) => Promise<OperationsTenantContact>;
  searchListings: (query: string, signal: AbortSignal) => Promise<DiscoveryListingOption[]>;
  loadOwnedCandidates: (
    page: number,
    signal: AbortSignal
  ) => Promise<OperationsOwnedCandidatePage<OperationsMyWorkItem> | null>;
}

interface Props {
  targetType: OperationsTargetType;
  targetId: number;
  source: 'owned' | 'claimable';
  state: OperationsDetailState;
  busyAction: 'claim' | 'release' | null;
  /** The in-flight Phase 2A command key, or null when none is running. */
  busyCommand: string | null;
  /** A fresh, guarded Session read is in progress before the scheduling drawer opens. */
  schedulingOpening: boolean;
  /** Truthful retry copy when that fresh read could not complete. */
  schedulingOpenError: string | null;
  /** Identity and version of the visible root; a change clears revealed lessor contact. */
  contactContextKey: string;
  actions: OperationsDetailActions;
  /** The workspace-owned availability draft and its generation-scoped transitions. */
  availability: OperationsAvailabilityEditing;
  onBack: () => void;
  onRetry: () => void;
  onClaim: () => void;
}

const Section: React.FC<{ title: string; children: React.ReactNode }> = ({ title, children }) => (
  <section className="oe-section" aria-label={title}>
    <h3 className="oe-h2">{title}</h3>
    {children}
  </section>
);

const Layout: React.FC<{ primary: React.ReactNode; secondary?: React.ReactNode; actions: React.ReactNode }> = ({
  primary,
  secondary,
  actions
}) => (
  <div className="oe-detail-grid">
    <div className="oe-card oe-detail-primary">{primary}</div>
    {actions}
    {secondary ? <div className="oe-card oe-detail-secondary">{secondary}</div> : null}
  </div>
);

/**
 * Exactly what this workspace still does not do. Repair, override, rescheduling, cancellation,
 * coordinator reassignment and tenant confirmation are separate authorities this console does not
 * hold, and no call attempt or note is persisted anywhere.
 */
const NOT_AVAILABLE_FOOTNOTE =
  'Repair, override, rescheduling, cancellation, coordinator reassignment and tenant confirmation are outside this workspace. Calls and notes are not recorded here.';

const ArrowLeftIcon: React.FC = () => (
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.25" aria-hidden="true">
    <path d="M19 12H5M12 19l-7-7 7-7" strokeLinecap="round" strokeLinejoin="round" />
  </svg>
);

export const OperationsWorkDetail: React.FC<Props> = ({
  targetType,
  targetId,
  source,
  state,
  busyAction,
  busyCommand,
  schedulingOpening,
  schedulingOpenError,
  contactContextKey,
  actions,
  availability,
  onBack,
  onRetry,
  onClaim
}) => {
  const back = (
    <button type="button" className="oe-back" onClick={onBack}>
      <ArrowLeftIcon />
      Back
    </button>
  );

  if (state.status === 'loading') {
    return (
      <div className="oe-stack" aria-busy="true">
        {back}
        <h2 id="oe-page-title" tabIndex={-1} className="oe-sr">
          Loading {operationsRecordTitle(targetType, targetId)}
        </h2>
        <div className="oe-detail-skel">
          <span className="oe-skel" style={{ width: '18%' }} />
          <span className="oe-skel oe-skel--title" style={{ width: '42%' }} />
          <span className="oe-skel" style={{ width: '60%' }} />
        </div>
        <span className="oe-sr">Loading details…</span>
      </div>
    );
  }

  if (state.status === 'error') {
    return (
      <div className="oe-stack">
        {back}
        <h2 id="oe-page-title" tabIndex={-1} className="oe-sr">
          {operationsRecordTitle(targetType, targetId)} could not be loaded
        </h2>
        <StateMessage
          icon={<CircleAlert size={20} />}
          tone="warn"
          title="We couldn’t load these details"
          body="Nothing about this item has changed. Check your connection and try again."
        >
          <button type="button" className="oe-btn oe-btn--primary oe-btn--md" onClick={onRetry}>
            <RefreshCw size={14} aria-hidden="true" />
            Try again
          </button>
        </StateMessage>
      </div>
    );
  }

  if (state.status === 'missing') {
    return (
      <div className="oe-stack">
        {back}
        <h2 id="oe-page-title" tabIndex={-1} className="oe-sr">
          {operationsRecordTitle(targetType, targetId)} is not available
        </h2>
        <StateMessage
          icon={<SearchX size={20} />}
          title="This item isn’t available to you"
          body={`It may have been claimed, released or moved outside your teams. ${operationsRecordTitle(targetType, targetId)} stays in your queues while it is yours.`}
        >
          <button type="button" className="oe-btn oe-btn--secondary oe-btn--md" onClick={onBack}>
            Back to the queue
          </button>
        </StateMessage>
      </div>
    );
  }

  const { snapshot } = state;

  if (snapshot.kind === 'claimable' && snapshot.claimable) {
    return (
      <ClaimablePreview item={snapshot.claimable} back={back} busy={busyAction === 'claim'} onClaim={onClaim} />
    );
  }
  if (snapshot.kind === 'request' && snapshot.request) {
    return (
      <OwnedRequest
        detail={snapshot.request}
        back={back}
        busy={busyAction === 'release'}
        busyCommand={busyCommand}
        contactContextKey={contactContextKey}
        actions={actions}
      />
    );
  }
  if (snapshot.kind === 'session' && snapshot.session) {
    return (
      <OwnedSession
        detail={snapshot.session}
        back={back}
        busy={busyAction === 'release'}
        busyCommand={busyCommand}
        schedulingOpening={schedulingOpening}
        schedulingOpenError={schedulingOpenError}
        contactContextKey={contactContextKey}
        actions={actions}
        availability={availability}
      />
    );
  }

  // A root the workspace could not describe is reported, never filled with substitute content.
  return (
    <div className="oe-stack">
      {back}
      <h2 id="oe-page-title" tabIndex={-1} className="oe-sr">
        {operationsRecordTitle(targetType, targetId)} details are unavailable
      </h2>
      <StateMessage
        icon={<SearchX size={20} />}
        title="These details are unavailable"
        body={`${operationsRecordTitle(targetType, targetId)} returned no usable information from the ${
          source === 'claimable' ? 'claim preview' : 'owned detail'
        } endpoint. Nothing was substituted for it.`}
      >
        <button type="button" className="oe-btn oe-btn--secondary oe-btn--md" onClick={onRetry}>
          <RefreshCw size={14} aria-hidden="true" />
          Try again
        </button>
      </StateMessage>
    </div>
  );
};

const DetailHead: React.FC<{
  targetType: OperationsTargetType;
  targetId: number;
  status: string;
  city: string;
  team: string;
  lede: string;
  flagLabel?: string;
  unclaimed?: boolean;
}> = ({ targetType, targetId, status, city, team, lede, flagLabel, unclaimed = false }) => {
  const hint = operationsStatusMeta(targetType, status).hint;
  return (
    <header className="oe-detail-head">
      <div className="oe-detail-id">
        <TypeTag targetType={targetType} />
        <StatusBadge targetType={targetType} status={status} />
        {flagLabel ? <span className="oe-chip oe-chip--flag">{flagLabel}</span> : null}
        {unclaimed ? <span className="oe-chip">Unclaimed</span> : null}
      </div>
      <h2 id="oe-page-title" tabIndex={-1} className="oe-h1 oe-ref-title">
        {operationsRecordTitle(targetType, targetId)}
      </h2>
      <p className="oe-detail-lede">{lede}</p>
      {hint ? <p className="oe-detail-hint">{hint}</p> : null}
      <p className="oe-detail-scope">
        <MapPin size={14} aria-hidden="true" />
        <span>
          <span className="oe-detail-scope-city">{city}</span>
          <span aria-hidden="true"> · </span>
          <span>{team}</span>
        </span>
      </p>
    </header>
  );
};

const LinkedRequests: React.FC<{ items: { requestId: number; version: number }[] }> = ({ items }) => {
  if (items.length === 0) return <p className="oe-quiet">No tenant visit requests.</p>;
  return (
    <ul className="oe-linked">
      {items.map((entry) => (
        <li key={entry.requestId}>
          <span className="oe-mono oe-linked-ref">{operationsRecordReference(entry.requestId)}</span>
          <span className="oe-linked-version oe-mono">v{entry.version}</span>
        </li>
      ))}
    </ul>
  );
};

const LinkedRequestTimingFacts: React.FC<{
  facts: OperationsSessionDetail['linkedRequestTimingFacts'];
}> = ({ facts }) => {
  if (facts.length === 0) {
    return <p className="oe-quiet">No tenant visit request timing is recorded for this visit plan.</p>;
  }
  return (
    <div className="oe-stack">
      {facts.map((fact) => {
        const start = formatOperationsZonedDateTime(fact.availabilityStartAt, fact.availabilityZoneId);
        const end = formatOperationsZonedDateTime(fact.availabilityEndAt, fact.availabilityZoneId);
        const preferred = formatOperationsZonedDateTime(fact.preferredAt, fact.availabilityZoneId);
        return (
          <div key={fact.requestId} className="oe-section" aria-label={`Timing for request ${fact.requestId}`}>
            <p className="oe-mono">
              {operationsRecordReference(fact.requestId)} · v{fact.version}
            </p>
            <dl className="oe-fields">
              <Field label="Original tenant request" wide>
                {fact.preferredVisitTiming ?? <MissingValue>No original text recorded</MissingValue>}
              </Field>
              <Field label="Current tenant availability" wide>
                {start && end
                  ? `${start} → ${end}${fact.availabilityZoneId ? ` · ${fact.availabilityZoneId}` : ''}`
                  : start
                    ? `From ${start}${fact.availabilityZoneId ? ` · ${fact.availabilityZoneId}` : ''}`
                    : <MissingValue>No current availability recorded</MissingValue>}
              </Field>
              <Field label="Tenant preferred time">
                {preferred ?? <MissingValue>No current preference recorded</MissingValue>}
              </Field>
            </dl>
          </div>
        );
      })}
    </div>
  );
};

const ClaimablePreview: React.FC<{
  item: ClaimableWorkItem;
  back: React.ReactNode;
  busy: boolean;
  onClaim: () => void;
}> = ({ item, back, busy, onClaim }) => {
  const isSession = item.targetType === 'SESSION';
  const noun = operationsNoun(item.targetType);
  const city = item.cityDisplayName ?? 'City not recorded';
  const team = item.teamDisplayName ?? 'Team not recorded';
  return (
    <div className="oe-stack">
      {back}
      <DetailHead
        targetType={item.targetType}
        targetId={item.targetId}
        status={item.status}
        city={city}
        team={team}
        unclaimed
        lede={`Claim preview. Only summary information is available until you claim this ${noun}.`}
      />
      <Layout
        actions={
          <aside className="oe-actions" aria-label="Actions">
            <h3 className="oe-h3">Actions</h3>
            <div className="oe-actions-list">
              <button
                type="button"
                className="oe-btn oe-btn--primary oe-btn--md oe-btn--block"
                onClick={onClaim}
                disabled={busy}
              >
                {busy ? <LoaderCircle className="oe-spin" size={16} aria-hidden="true" /> : null}
                {busy ? 'Claiming…' : `Claim ${noun}`}
              </button>
              <p className="oe-action-note">
                Claiming re-reads this item from the server first, so a version that changed since this
                page loaded is never claimed.
              </p>
            </div>
            <p className="oe-footnote">{NOT_AVAILABLE_FOOTNOTE}</p>
          </aside>
        }
        primary={
          <>
            <Section title="Summary">
              <dl className="oe-fields">
                <Field label="City">{city}</Field>
                <Field label="Team">{team}</Field>
                <Field label="Status">
                  <StatusBadge targetType={item.targetType} status={item.status} />
                </Field>
              </dl>
            </Section>
            {isSession ? (
              <Section title={linkedRequestsLabel(item.linkedRequestVersions.length)}>
                <LinkedRequests items={item.linkedRequestVersions} />
              </Section>
            ) : null}
            <div className="oe-section">
              <p className="oe-locked">
                <Lock size={16} aria-hidden="true" />
                <span>
                  <strong>Shown after you claim it.</strong>{' '}
                  {isSession
                    ? 'The schedule, visit items and tenant confirmation become visible once you own this session.'
                    : 'The listing, availability and preferred time become visible once you own this request.'}
                </span>
              </p>
            </div>
          </>
        }
      />
    </div>
  );
};

const OwnedRequest: React.FC<{
  detail: OperationsRequestDetail;
  back: React.ReactNode;
  busy: boolean;
  busyCommand: string | null;
  contactContextKey: string;
  actions: OperationsDetailActions;
}> = ({ detail, back, busy, busyCommand, contactContextKey, actions }) => {
  const city = detail.cityDisplayName ?? 'City not recorded';
  const team = detail.teamDisplayName ?? 'Team not recorded';
  const releasable = isOwnedRequestReleasable(detail);
  const closed = isTerminalOperationsStatus('REQUEST', detail.status);
  const coordinationOpen = detail.status === 'RECEIVED' && detail.sessionId === null;
  const flagged = needsCoordinatorAttention({ targetType: 'REQUEST', status: detail.status });
  const start = formatOperationsZonedDateTime(detail.availabilityStartAt, detail.availabilityZoneId);
  const end = formatOperationsZonedDateTime(detail.availabilityEndAt, detail.availabilityZoneId);
  const windowLabel = formatOperationsZonedWindow(
    detail.availabilityStartAt,
    detail.availabilityEndAt,
    detail.availabilityZoneId
  ) ?? (start ? `From ${start}` : end ? `Until ${end}` : null);
  const blocked = busyCommand !== null || busy;

  return (
    <div className="oe-stack">
      {back}
      <DetailHead
        targetType="REQUEST"
        targetId={detail.requestId}
        status={detail.status}
        city={city}
        team={team}
        lede={detail.listingTitle ?? 'Listing details are not recorded on this request.'}
        flagLabel={flagged ? attentionCategory({ targetType: 'REQUEST', status: detail.status }) : undefined}
      />
      <Layout
        actions={
          <aside className="oe-actions" aria-label="Actions">
            <h3 className="oe-h3">Actions</h3>
            {closed ? (
              <p className="oe-quiet">
                This visit request is closed and kept for history. No ownership actions apply.
              </p>
            ) : coordinationOpen ? (
              <OperationsRequestCoordination
                requestId={detail.requestId}
                blocked={blocked}
                busyCommand={busyCommand}
                onCoordinateNewSession={actions.onCoordinateNewSession}
                onAttachToOwnedDraft={actions.onAttachToOwnedDraft}
                onMarkConfirmedUnavailable={actions.onMarkConfirmedUnavailable}
                loadOwnedCandidates={actions.loadOwnedCandidates}
              />
            ) : releasable ? (
              <div className="oe-actions-list">
                <button
                  type="button"
                  className="oe-btn oe-btn--secondary oe-btn--md oe-btn--block"
                  onClick={actions.onRelease}
                  disabled={busy}
                >
                  {busy ? <LoaderCircle className="oe-spin" size={16} aria-hidden="true" /> : null}
                  {busy ? 'Releasing…' : 'Release visit request'}
                </button>
                <p className="oe-action-note">
                  Releasing returns this visit request to your teams’ available work. It stays available to
                  claim again, and this page stops showing it once released.
                </p>
              </div>
            ) : (
              <p className="oe-quiet">
                {detail.status === 'COORDINATING'
                  ? 'Coordination is already under way. Manage this request from its visit session.'
                  : 'This request is not in a state that can be released from here.'}
              </p>
            )}
            {!closed && coordinationOpen && releasable ? (
              <div className="oe-actions-list">
                <button
                  type="button"
                  className="oe-btn oe-btn--secondary oe-btn--md oe-btn--block"
                  onClick={actions.onRelease}
                  disabled={busy}
                >
                  {busy ? <LoaderCircle className="oe-spin" size={16} aria-hidden="true" /> : null}
                  {busy ? 'Releasing…' : 'Release visit request'}
                </button>
              </div>
            ) : null}
            <p className="oe-footnote">{NOT_AVAILABLE_FOOTNOTE}</p>
          </aside>
        }
        primary={
          <>
            <Section title="Listing">
              <dl className="oe-fields">
                <Field label="Title" wide>
                  {detail.listingTitle ?? <MissingValue>Not recorded</MissingValue>}
                </Field>
                <Field label="Address" wide>
                  {detail.listingAddress ?? <MissingValue>Not provided</MissingValue>}
                </Field>
                <Field label="Locality / sector">
                  {detail.sector ?? <MissingValue>Not provided</MissingValue>}
                </Field>
                <Field label="Listing status">
                  {detail.listingStatus ?? <MissingValue>Not provided</MissingValue>}
                </Field>
                <Field label="Listing city">
                  {detail.listingCity ?? <MissingValue>Not recorded</MissingValue>}
                </Field>
                <Field label="Listing reference">
                  {detail.listingId === null
                    ? <MissingValue>Not recorded</MissingValue>
                    : <span className="oe-mono">{operationsRecordReference(detail.listingId)}</span>}
                </Field>
              </dl>
            </Section>
            <Section title="Availability">
              <dl className="oe-fields">
                <Field label="Original tenant request" wide>
                  {detail.preferredVisitTiming ?? <MissingValue>No original text recorded</MissingValue>}
                </Field>
                <Field label="Availability window" wide>
                  {windowLabel ?? <MissingValue>No window on record</MissingValue>}
                </Field>
                <Field label="Availability zone">
                  {detail.availabilityZoneId ?? <MissingValue>Not recorded</MissingValue>}
                </Field>
                <Field label="Preferred time">
                  {formatOperationsZonedDateTime(detail.preferredAt, detail.availabilityZoneId)
                    ?? <MissingValue>No preference given</MissingValue>}
                </Field>
              </dl>
              <p className="oe-footnote">
                {requestHasCompleteWindow(detail)
                  ? 'This is the tenant’s current availability. It is separate from the original request text above and may change before coordination.'
                  : 'The tenant’s current window is incomplete or missing. The server needs a start, an end and a zone before it will evaluate scheduling.'}
              </p>
            </Section>
            <Section title="Lessor contact">
              <OperationsLessorContactPanel
                contextKey={contactContextKey}
                subject={detail.listingTitle ?? operationsRecordTitle('REQUEST', detail.requestId)}
                reveal={actions.revealRequestContact}
              />
              <p className="oe-footnote">
                Contact is shown only when Pathome can confirm the saved record for this property.
                It is never taken from another source, and it is not stored in this browser.
              </p>
            </Section>
            <Section title="Tenant contact">
              <OperationsLessorContactPanel
                variant="tenant"
                contextKey={contactContextKey}
                subject={operationsRecordReference(detail.requestId)}
                reveal={actions.revealRequestTenantContact}
              />
              <p className="oe-footnote">
                Only the tenant’s account name and mobile are shown, and only when Pathome confirms
                you currently own this request. The disclosure is audited and never stored in this browser.
              </p>
            </Section>
          </>
        }
        secondary={
          <Section title="Coordination">
            <dl className="oe-fields">
              <Field label="City">{city}</Field>
              <Field label="Team">{team}</Field>
              <Field label="Request version">
                {detail.version === null
                  ? <MissingValue>Not recorded</MissingValue>
                  : <span className="oe-mono">v{detail.version}</span>}
              </Field>
              <Field label="Created (server local time)" wide>
                {formatStoredLocalDateTime(detail.createdAt) ?? <MissingValue>Not recorded</MissingValue>}
              </Field>
            </dl>
          </Section>
        }
      />
    </div>
  );
};

const EXCEPTION_NOTICE: Record<string, string> = {
  INTERRUPTED: 'This visit was interrupted.',
  REPAIR_REQUIRED: 'This session needs repair.',
  PROVISIONAL_NO_SHOW: 'A provisional no-show was recorded and is awaiting review.'
};

const OwnedSession: React.FC<{
  detail: OperationsSessionDetail;
  back: React.ReactNode;
  busy: boolean;
  busyCommand: string | null;
  schedulingOpening: boolean;
  schedulingOpenError: string | null;
  contactContextKey: string;
  actions: OperationsDetailActions;
  availability: OperationsAvailabilityEditing;
}> = ({ detail, back, busy, busyCommand, schedulingOpening, schedulingOpenError, contactContextKey, actions, availability }) => {
  const city = detail.city ?? 'City not recorded';
  const team = detail.teamDisplayName ?? 'Team not recorded';
  const releasable = isOwnedSessionReleasable(detail);
  const closed = isTerminalOperationsStatus('SESSION', detail.status);
  const draft = detail.status === 'DRAFT';
  const flagged = needsCoordinatorAttention({
    targetType: 'SESSION',
    status: detail.status,
    scheduledAt: detail.scheduledAt,
    tenantConfirmationState: detail.tenantConfirmationState
  });
  const exception = EXCEPTION_NOTICE[detail.status];
  const activeItems = detail.items.filter((item) => item.removedAt === null);
  const blockers = schedulingBlockersForSession(detail);
  const readyStopCount = activeItems.filter((item) => operationsAvailabilityMeta(item).schedulingReady).length;
  const unavailableStopCount = unavailableActiveStopCount(detail);
  const lede = `${linkedRequestsLabel(detail.linkedRequestVersions.length)} · ${activeItems.length} ${
    activeItems.length === 1 ? 'property' : 'properties'
  } to visit${
    detail.areaName ? ` · ${detail.areaName}` : ''
  }`;
  const visitTime = formatOperationsTime(detail.scheduledAt);
  const duration = formatOperationsDuration(detail.durationMinutes);
  const plannedWindow = visitTime ? `${formatOperationsDate(detail.scheduledAt) ?? ''} · ${visitTime}` : null;
  const hasExecution = Boolean(detail.startedAt || detail.expiresAt || detail.executionStateChangedAt);
  const blocked = busyCommand !== null || busy;

  return (
    <div className="oe-stack">
      {back}
      <DetailHead
        targetType="SESSION"
        targetId={detail.sessionId}
        status={detail.status}
        city={city}
        team={team}
        lede={lede}
        flagLabel={flagged
          ? attentionCategory({
              targetType: 'SESSION',
              status: detail.status,
              scheduledAt: detail.scheduledAt,
              tenantConfirmationState: detail.tenantConfirmationState
            })
          : undefined}
      />
      {exception ? (
        <div className="oe-banner oe-banner--warn" role="note">
          <p>
            <strong>{exception}</strong> The cause is not part of the information this workspace
            receives, and repair, override and rescheduling are not available here.
          </p>
        </div>
      ) : null}
      <Layout
        actions={
          <aside className="oe-actions" aria-label="Actions">
            <h3 className="oe-h3">Actions</h3>
            {closed ? (
              <p className="oe-quiet">
                This visit plan is closed and kept for history. No ownership actions apply.
              </p>
            ) : (
              <div className="oe-actions-list">
                {draft ? (
                  <>
                    <button
                      type="button"
                      className="oe-btn oe-btn--primary oe-btn--md oe-btn--block"
                      onClick={actions.onOpenScheduling}
                      disabled={blocked || schedulingOpening}
                      aria-busy={schedulingOpening}
                    >
                      {schedulingOpening
                        ? <LoaderCircle className="oe-spin" size={16} aria-hidden="true" />
                        : <CalendarPlus size={16} aria-hidden="true" />}
                      {schedulingOpening ? 'Checking latest visit details…' : 'Schedule visit'}
                    </button>
                    {schedulingOpenError ? (
                      <div className="oe-banner oe-banner--warn" role="alert">
                        <p>{schedulingOpenError}</p>
                        <button
                          type="button"
                          className="oe-btn oe-btn--secondary oe-btn--sm"
                          onClick={actions.onOpenScheduling}
                          disabled={blocked || schedulingOpening}
                        >
                          <RefreshCw size={14} aria-hidden="true" />
                          Try again
                        </button>
                      </div>
                    ) : null}
                    <p className="oe-action-note">
                      {blockers.length === 0
                        ? `${readyStopCount} of ${activeItems.length} active stops have a complete confirmed window${
                            unavailableStopCount > 0
                              ? `, and ${unavailableStopCount} ${unavailableStopCount === 1 ? 'stop is' : 'stops are'} recorded unavailable`
                              : ''
                          }. The server evaluates the visit from the confirmed stops only.`
                        : `Scheduling will stop at validation: ${blockers.join(' ')}`}
                    </p>
                    <button
                      type="button"
                      className="oe-btn oe-btn--secondary oe-btn--md oe-btn--block"
                      onClick={() => document.getElementById('oe-itin-title')?.focus()}
                      disabled={blocked}
                    >
                      <ListOrdered size={16} aria-hidden="true" />
                      Properties and availability
                    </button>
                  </>
                ) : null}
                {releasable ? (
                  <>
                    <button
                      type="button"
                      className="oe-btn oe-btn--secondary oe-btn--md oe-btn--block"
                      onClick={actions.onRelease}
                      disabled={busy}
                    >
                      {busy ? <LoaderCircle className="oe-spin" size={16} aria-hidden="true" /> : null}
                      {busy ? 'Releasing…' : 'Release visit plan'}
                    </button>
                    <p className="oe-action-note">
                      Releasing returns this visit plan and its tenant visit requests to your teams’
                      available work. It stays available to claim again, and this page stops showing it once
                      released.
                    </p>
                  </>
                ) : null}
              </div>
            )}
            {!closed && !draft && !releasable ? (
              <p className="oe-quiet">
                {detail.status === 'SCHEDULED'
                  ? 'A visit time is booked, so this page is view only. The booked time, the assigned Ground Executive and release cannot be changed from this workspace.'
                  : detail.status === 'STARTED'
                    ? 'The visit is in progress. This page is view only.'
                    : 'This session is not in a state that can be released from here.'}
              </p>
            ) : null}
            <p className="oe-footnote">{NOT_AVAILABLE_FOOTNOTE}</p>
          </aside>
        }
        primary={
          <>
            <Section title="Schedule and confirmation">
              <dl className="oe-fields">
                <Field label="Visit date and time">
                  {formatOperationsDetailDateTime(detail.scheduledAt)
                    ?? <MissingValue>No visit time booked</MissingValue>}
                </Field>
                <Field label="Tenant confirmation">
                  <TenantConfirmationLabel value={detail.tenantConfirmationState} />
                </Field>
                <Field label="Assigned Ground Executive">
                  {detail.representativeUserId === null
                    ? <MissingValue>Not assigned</MissingValue>
                    : <span className="oe-mono">User {detail.representativeUserId}</span>}
                </Field>
                <Field label="Assigned on">
                  {formatOperationsDetailDateTime(detail.assignedAt)
                    ?? <MissingValue>Not recorded</MissingValue>}
                </Field>
                <Field label="Planned window (IST)">
                  {plannedWindow ?? <MissingValue>Not recorded</MissingValue>}
                </Field>
                <Field label="Duration">
                  {duration ?? <MissingValue>Not recorded</MissingValue>}
                </Field>
                <Field label="Reserved end">
                  {formatOperationsDetailDateTime(detail.reservedEndAt)
                    ?? <MissingValue>Not recorded</MissingValue>}
                </Field>
                <Field label="Visit zone">
                  {detail.zoneId ?? <MissingValue>Not recorded</MissingValue>}
                </Field>
              </dl>
              <p className="oe-footnote">
                Scheduling and tenant confirmation are tracked separately. This contract carries the
                Ground Executive’s user ID and no name.
              </p>
            </Section>

            <Section title="Tenant contact">
              <OperationsLessorContactPanel
                variant="tenant"
                contextKey={contactContextKey}
                subject={operationsRecordReference(detail.sessionId)}
                reveal={actions.revealSessionTenantContact}
              />
              <p className="oe-footnote">
                Shown only while you currently own this visit and Pathome can confirm the tenant’s
                account contact. The scheduled time and the tenant’s confirmation remain separate facts.
              </p>
            </Section>

            <Section title={`Properties to visit (${activeItems.length} active)`}>
              <OperationsDraftItinerary
                session={detail}
                editable={draft}
                blocked={blocked}
                busyCommand={busyCommand}
                onReorder={actions.onReorderItems}
                onRemove={actions.onRemoveItem}
                availability={availability}
                onAddStop={actions.onAddStop}
                revealItemContact={actions.revealItemContact}
                contactContextKey={contactContextKey}
                searchListings={actions.searchListings}
              />
            </Section>

            {hasExecution ? (
              <Section title="Visit progress">
                <dl className="oe-fields">
                  <Field label="Started">
                    {formatOperationsDetailDateTime(detail.startedAt)
                      ?? <MissingValue>Not recorded</MissingValue>}
                  </Field>
                  <Field label="Expires">
                    {formatOperationsDetailDateTime(detail.expiresAt)
                      ?? <MissingValue>Not recorded</MissingValue>}
                  </Field>
                  <Field label="Visit progress last updated">
                    {formatOperationsDetailDateTime(detail.executionStateChangedAt)
                      ?? <MissingValue>Not recorded</MissingValue>}
                  </Field>
                </dl>
              </Section>
            ) : null}
          </>
        }
        secondary={
          <>
            <Section title={linkedRequestsLabel(detail.linkedRequestVersions.length)}>
              <LinkedRequests items={detail.linkedRequestVersions} />
              <p className="oe-footnote">
                Tenant visit requests belong to this visit plan and do not appear separately in your queues.
              </p>
            </Section>
            <Section title="Tenant timing">
              <LinkedRequestTimingFacts facts={detail.linkedRequestTimingFacts} />
              <p className="oe-footnote">
                Each tenant visit request keeps its own original request text and current availability.
                The scheduled visit above is the only confirmed appointment time.
              </p>
            </Section>
            <Section title="Coordination">
              <dl className="oe-fields">
                <Field label="City">{city}</Field>
                <Field label="Team">{team}</Field>
                <Field label="Coordinator">
                  {detail.coordinatorUserId === null
                    ? <MissingValue>Not assigned</MissingValue>
                    : <span className="oe-mono">User {detail.coordinatorUserId}</span>}
                </Field>
                <Field label="Last updated">
                  {formatOperationsDetailDateTime(detail.updatedAt)
                    ?? <MissingValue>Not recorded</MissingValue>}
                </Field>
              </dl>
            </Section>
          </>
        }
      />
    </div>
  );
};
