import React from 'react';
import { CalendarPlus, CircleAlert, ListOrdered, LoaderCircle, Lock, MapPin, RefreshCw, SearchX } from 'lucide-react';
import {
  attentionCategory,
  formatOperationsDate,
  formatOperationsDetailDateTime,
  formatOperationsDuration,
  formatOperationsTime,
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
  type OperationsTargetType
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
  type DiscoveryListingOption
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

/** Window input collected by the availability editor, in the shape the command builder expects. */
export interface OperationsAvailabilityWindowInput {
  availabilityStartAt: string | null;
  availabilityEndAt: string | null;
  availabilityZoneId: string | null;
  availabilitySource: 'PHONE' | 'WHATSAPP' | 'DIRECT' | 'OTHER' | null;
}

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
  /** Identity and version of the visible root; a change clears revealed lessor contact. */
  contactContextKey: string;
  actions: OperationsDetailActions;
  onSaveAvailability: (
    itemId: number,
    choice: 'window' | 'confirm-only' | 'unavailable',
    window: OperationsAvailabilityWindowInput
  ) => void;
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
  contactContextKey,
  actions,
  onSaveAvailability,
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
        contactContextKey={contactContextKey}
        actions={actions}
        onSaveAvailability={onSaveAvailability}
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
  if (items.length === 0) return <p className="oe-quiet">No linked requests.</p>;
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
  const start = formatOperationsDetailDateTime(detail.availabilityStartAt);
  const end = formatOperationsDetailDateTime(detail.availabilityEndAt);
  const windowLabel = start && end ? `${start} → ${end}` : start ? `From ${start}` : end ? `Until ${end}` : null;
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
                This request is closed and kept as read-only history. No ownership actions apply.
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
                  {busy ? 'Releasing…' : 'Release request'}
                </button>
                <p className="oe-action-note">
                  Releasing returns this request to your teams’ claimable work. It stays available to
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
                  {busy ? 'Releasing…' : 'Release request'}
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
                <Field label="Availability window" wide>
                  {windowLabel ?? <MissingValue>No window on record</MissingValue>}
                </Field>
                <Field label="Availability zone">
                  {detail.availabilityZoneId ?? <MissingValue>Not recorded</MissingValue>}
                </Field>
                <Field label="Preferred time">
                  {formatOperationsDetailDateTime(detail.preferredAt)
                    ?? <MissingValue>No preference given</MissingValue>}
                </Field>
              </dl>
              <p className="oe-footnote">
                {requestHasCompleteWindow(detail)
                  ? 'The tenant’s window is complete, so this request can be evaluated for scheduling once it is on a session.'
                  : 'The tenant’s window is incomplete or missing. The server needs a start, an end and a zone before it will evaluate scheduling.'}
              </p>
            </Section>
            <Section title="Lessor contact">
              <OperationsLessorContactPanel
                contextKey={contactContextKey}
                subject={detail.listingTitle ?? operationsRecordTitle('REQUEST', detail.requestId)}
                reveal={actions.revealRequestContact}
              />
              <p className="oe-footnote">
                Contact is shown only when the backend resolves a canonical, trusted record for this
                property. It is never taken from another source, and it is not stored in this browser.
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
  contactContextKey: string;
  actions: OperationsDetailActions;
  onSaveAvailability: (
    itemId: number,
    choice: 'window' | 'confirm-only' | 'unavailable',
    window: OperationsAvailabilityWindowInput
  ) => void;
}> = ({ detail, back, busy, busyCommand, contactContextKey, actions, onSaveAvailability }) => {
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
  const lede = `${detail.items.length} ${detail.items.length === 1 ? 'visit item' : 'visit items'}${
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
                This session is closed and kept as read-only history. No ownership actions apply.
              </p>
            ) : (
              <div className="oe-actions-list">
                {draft ? (
                  <>
                    <button
                      type="button"
                      className="oe-btn oe-btn--primary oe-btn--md oe-btn--block"
                      onClick={actions.onOpenScheduling}
                      disabled={blocked}
                    >
                      <CalendarPlus size={16} aria-hidden="true" />
                      Schedule visit
                    </button>
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
                      Itinerary and availability
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
                      {busy ? 'Releasing…' : 'Release session'}
                    </button>
                    <p className="oe-action-note">
                      Releasing returns this session and its linked requests to your teams’ claimable
                      work. It stays available to claim again, and this page stops showing it once
                      released.
                    </p>
                  </>
                ) : null}
              </div>
            )}
            {!closed && !draft && !releasable ? (
              <p className="oe-quiet">
                {detail.status === 'SCHEDULED'
                  ? 'A visit time is booked, so this session is read-only here. The booked time, the assigned Ground Executive and release cannot be changed from this workspace.'
                  : detail.status === 'STARTED'
                    ? 'The visit is in progress. This view is read-only.'
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
                    ?? <MissingValue>Not scheduled yet</MissingValue>}
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

            <Section title={`Visit items (${activeItems.length} active)`}>
              <OperationsDraftItinerary
                session={detail}
                editable={draft}
                blocked={blocked}
                busyCommand={busyCommand}
                onReorder={actions.onReorderItems}
                onRemove={actions.onRemoveItem}
                onSaveAvailability={onSaveAvailability}
                onAddStop={actions.onAddStop}
                revealItemContact={actions.revealItemContact}
                contactContextKey={contactContextKey}
                searchListings={actions.searchListings}
              />
            </Section>

            {hasExecution ? (
              <Section title="Execution">
                <dl className="oe-fields">
                  <Field label="Started">
                    {formatOperationsDetailDateTime(detail.startedAt)
                      ?? <MissingValue>Not recorded</MissingValue>}
                  </Field>
                  <Field label="Expires">
                    {formatOperationsDetailDateTime(detail.expiresAt)
                      ?? <MissingValue>Not recorded</MissingValue>}
                  </Field>
                  <Field label="Execution state changed">
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
                Linked requests belong to this session and do not appear separately in your queues.
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
                <Field label="Session version">
                  {detail.version === null
                    ? <MissingValue>Not recorded</MissingValue>
                    : <span className="oe-mono">v{detail.version}</span>}
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
