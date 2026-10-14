import React from 'react';
import { ChevronRight, LoaderCircle } from 'lucide-react';
import {
  attentionCategory,
  formatOperationsDateTime,
  linkedRequestsLabel,
  needsCoordinatorAttention,
  operationsRecordReference,
  type ClaimableWorkItem,
  type OperationsMyWorkItem
} from '../../utils/operationsWorkspacePresentation';
import { ScopeLine, StatusBadge, TenantConfirmationLabel, TypeTag } from './OperationsWorkspacePrimitives';

const MAX_LINKED_IDS = 3;

export const workItemKey = (item: { targetType: string; targetId: number }): string =>
  `${item.targetType}-${item.targetId}`;

/**
 * Owned work (My work and Needs attention). The contract carries status, schedule, tenant
 * confirmation and update time, so each row is built from those facts only — never from a listing
 * title or property count the summary does not return.
 */
export const OwnedList: React.FC<{
  items: OperationsMyWorkItem[];
  flagged: boolean;
  label: string;
  onOpen: (item: OperationsMyWorkItem) => void;
}> = ({ items, flagged, label, onOpen }) => (
  <section className={`oe-list oe-list--owned${flagged ? ' oe-list--flag' : ''}`} aria-label={label}>
    <div className="oe-list-head" aria-hidden="true">
      <span className="a-id">Record</span>
      <span className="a-status">Status</span>
      <span className="a-when">Schedule</span>
      <span className="a-conf">Tenant</span>
      <span className="a-ctx">City · Team</span>
    </div>
    <ul className="oe-rows">
      {items.map((item) => (
        <OwnedRow key={workItemKey(item)} item={item} onOpen={onOpen} />
      ))}
    </ul>
  </section>
);

const OwnedRow: React.FC<{ item: OperationsMyWorkItem; onOpen: (item: OperationsMyWorkItem) => void }> = ({
  item,
  onOpen
}) => {
  const isSession = item.targetType === 'SESSION';
  const scheduled = formatOperationsDateTime(item.scheduledAt);
  const updated = formatOperationsDateTime(item.updatedAt);
  const attention = needsCoordinatorAttention({
    targetType: item.targetType,
    status: item.status,
    scheduledAt: item.scheduledAt,
    tenantConfirmationState: item.tenantConfirmationState
  });

  return (
    <li className="oe-row oe-row--owned">
      <div className="a-id oe-id">
        <TypeTag targetType={item.targetType} />
        <button
          type="button"
          className="oe-row-link"
          data-row-key={workItemKey(item)}
          onClick={() => onOpen(item)}
        >
          <span className="oe-ref">{operationsRecordReference(item.targetId)}</span>
          <span className="oe-sr">, view details</span>
        </button>
        {isSession ? <span className="oe-row-meta">{linkedRequestsLabel(item.linkedRequestVersions.length)}</span> : null}
        {attention ? (
          <span className="oe-attn-cat">
            <span className="oe-sr">Needs attention: </span>
            {attentionCategory({
              targetType: item.targetType,
              status: item.status,
              scheduledAt: item.scheduledAt,
              tenantConfirmationState: item.tenantConfirmationState
            })}
          </span>
        ) : null}
      </div>

      <div className="a-status">
        <span className="oe-sr">Status: </span>
        <StatusBadge targetType={item.targetType} status={item.status} />
      </div>

      <div className="a-when oe-when">
        {isSession ? (
          <>
            <span className={scheduled ? 'oe-when-primary' : 'oe-quiet'}>{scheduled ?? 'Not scheduled'}</span>
            {updated ? <span className="oe-when-secondary">Updated {updated}</span> : null}
          </>
        ) : (
          <span className={updated ? 'oe-when-primary' : 'oe-quiet'}>
            {updated ? `Updated ${updated}` : 'Update time not recorded'}
          </span>
        )}
      </div>

      <div className="a-conf">
        {isSession ? <TenantConfirmationLabel value={item.tenantConfirmationState} /> : null}
      </div>

      <div className="a-ctx">
        <ScopeLine
          city={item.cityDisplayName ?? 'City not recorded'}
          team={item.teamDisplayName ?? 'Team not recorded'}
        />
      </div>

      <ChevronRight className="a-chev oe-row-chevron" size={16} aria-hidden="true" />
    </li>
  );
};

/**
 * Claimable work. The contract returns identity, status, scope and linked Request versions only,
 * so scope leads and nothing about the customer, lessor or Ground Executive is shown.
 */
export const ClaimableList: React.FC<{
  items: ClaimableWorkItem[];
  label: string;
  busyKey: string | null;
  onOpen: (item: ClaimableWorkItem) => void;
  onClaim: (item: ClaimableWorkItem) => void;
}> = ({ items, label, busyKey, onOpen, onClaim }) => (
  <section className="oe-list oe-list--claim" aria-label={label}>
    <div className="oe-list-head" aria-hidden="true">
      <span className="a-id">Record</span>
      <span className="a-status">Status</span>
      <span className="a-ctx">City · Team</span>
      <span className="a-link">Contains</span>
    </div>
    <ul className="oe-rows">
      {items.map((item) => (
        <ClaimableRow
          key={workItemKey(item)}
          item={item}
          busy={busyKey === workItemKey(item)}
          blocked={busyKey !== null}
          onOpen={onOpen}
          onClaim={onClaim}
        />
      ))}
    </ul>
  </section>
);

const ClaimableRow: React.FC<{
  item: ClaimableWorkItem;
  busy: boolean;
  blocked: boolean;
  onOpen: (item: ClaimableWorkItem) => void;
  onClaim: (item: ClaimableWorkItem) => void;
}> = ({ item, busy, blocked, onOpen, onClaim }) => {
  const isSession = item.targetType === 'SESSION';
  const linked = item.linkedRequestVersions;
  const shown = linked.slice(0, MAX_LINKED_IDS);
  const extra = linked.length - shown.length;

  return (
    <li className="oe-row oe-row--claim">
      <div className="a-id oe-id">
        <TypeTag targetType={item.targetType} />
        <button
          type="button"
          className="oe-row-link"
          data-row-key={workItemKey(item)}
          onClick={() => onOpen(item)}
        >
          <span className="oe-ref">{operationsRecordReference(item.targetId)}</span>
          <span className="oe-sr">, view claim preview</span>
        </button>
      </div>

      <div className="a-status">
        <span className="oe-sr">Status: </span>
        <StatusBadge targetType={item.targetType} status={item.status} />
      </div>

      <div className="a-ctx">
        <ScopeLine
          city={item.cityDisplayName ?? 'City not recorded'}
          team={item.teamDisplayName ?? 'Team not recorded'}
        />
      </div>

      <div className="a-link oe-link-cell">
        {isSession ? (
          <>
            <span className="oe-link-count">{linkedRequestsLabel(linked.length)}</span>
            <span className="oe-link-ids oe-mono">
              {shown.map((entry) => `#${entry.requestId}`).join(' · ')}
              {extra > 0 ? ` · +${extra}` : ''}
            </span>
          </>
        ) : (
          <span className="oe-quiet">Standalone request</span>
        )}
      </div>

      <div className="a-act">
        <button
          type="button"
          className="oe-btn oe-btn--secondary oe-btn--sm"
          onClick={() => onClaim(item)}
          disabled={blocked}
        >
          {busy ? <LoaderCircle className="oe-spin" size={14} aria-hidden="true" /> : null}
          {busy ? 'Claiming…' : 'Claim'}
        </button>
        <ChevronRight className="oe-row-chevron" size={16} aria-hidden="true" />
      </div>
    </li>
  );
};
