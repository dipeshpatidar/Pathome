import React, { useEffect, useMemo, useState } from 'react';
import { ChevronRight, Filter, LoaderCircle, RotateCcw, X } from 'lucide-react';
import {
  attentionCategory,
  formatOperationsDateTime,
  linkedRequestsLabel,
  needsCoordinatorAttention,
  operationsRecordReference,
  operationsStatusMeta,
  type ClaimableWorkItem,
  type OperationsMyWorkItem,
  type OperationsScope
} from '../../utils/operationsWorkspacePresentation';
import {
  EMPTY_OPERATIONS_FILTERS,
  hasOperationsFilters,
  normalizeOperationsFilters,
  operationsStatusCodes,
  type OperationsQueueFilters
} from '../../utils/operationsWorkspaceRoute';
import { ScopeLine, StatusBadge, TenantConfirmationLabel, TypeTag } from './OperationsWorkspacePrimitives';

const MAX_LINKED_IDS = 3;

export const OperationsFilterBar: React.FC<{
  filters: OperationsQueueFilters;
  scopes: OperationsScope[];
  totalCount: number;
  unfilteredTotalCount: number;
  loading: boolean;
  onChange: (filters: OperationsQueueFilters) => void;
}> = ({ filters, scopes, totalCount, unfilteredTotalCount, loading, onChange }) => {
  const [type, setType] = useState<'' | 'REQUEST' | 'SESSION'>(filters.targetType ?? '');
  const [status, setStatus] = useState(filters.status ?? '');
  const [city, setCity] = useState(filters.cityId === null ? '' : String(filters.cityId));
  const [team, setTeam] = useState(filters.teamId === null ? '' : String(filters.teamId));
  const [recordRef, setRecordRef] = useState(filters.targetId === null ? '' : String(filters.targetId));
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setType(filters.targetType ?? '');
    setStatus(filters.status ?? '');
    setCity(filters.cityId === null ? '' : String(filters.cityId));
    setTeam(filters.teamId === null ? '' : String(filters.teamId));
    setRecordRef(filters.targetId === null ? '' : String(filters.targetId));
    setError(null);
  }, [filters.targetType, filters.status, filters.cityId, filters.teamId, filters.targetId]);

  const cities = useMemo(() => {
    const seen = new Set<number>();
    return scopes.filter((scope) => {
      if (scope.cityId === null || seen.has(scope.cityId)) return false;
      seen.add(scope.cityId);
      return true;
    });
  }, [scopes]);
  const selectedCityId = city === '' ? null : Number(city);
  const teams = useMemo(
    () => scopes.filter((scope) => selectedCityId === null || scope.cityId === selectedCityId),
    [scopes, selectedCityId]
  );
  const statuses = operationsStatusCodes(type === '' ? null : type);
  const active = hasOperationsFilters(filters);

  const apply = (event: React.FormEvent) => {
    event.preventDefault();
    if (type === '' && recordRef.trim() !== '') {
      setError('Select Visit requests or Visit plans before entering a record number.');
      return;
    }
    const parsedRef = recordRef.trim() === '' ? null : Number(recordRef.trim());
    if (parsedRef !== null && (!/^\d+$/.test(recordRef.trim()) || !Number.isSafeInteger(parsedRef) || parsedRef <= 0)) {
      setError('Record number must be a positive whole number.');
      return;
    }
    setError(null);
    onChange(normalizeOperationsFilters({
      targetType: type === '' ? null : type,
      status: type === '' || status === '' ? null : status,
      cityId: city === '' ? null : Number(city),
      teamId: team === '' ? null : Number(team),
      targetId: parsedRef
    }));
  };

  const removeFilter = (key: keyof OperationsQueueFilters) => {
    const next: OperationsQueueFilters = { ...filters, [key]: null };
    if (key === 'targetType') {
      next.status = null;
      next.targetId = null;
    }
    onChange(normalizeOperationsFilters(next));
  };

  const cityName = cities.find((scope) => scope.cityId === filters.cityId)?.cityName;
  const teamName = scopes.find((scope) => scope.teamId === filters.teamId)?.teamName;
  const statusLabel = filters.status && filters.targetType
    ? operationsStatusMeta(filters.targetType, filters.status).label
    : filters.status;

  return (
    <section className="oe-filters" aria-label="Filter this queue">
      <form className="oe-filter-form" onSubmit={apply}>
        <div className="oe-filter-head">
          <p className="oe-filter-title">
            <Filter size={14} aria-hidden="true" />
            Filters
          </p>
          <p className="oe-filter-count" aria-live="polite">
            {loading ? 'Updating…' : active
              ? <>Showing <span className="oe-num">{totalCount}</span> of <span className="oe-num">{unfilteredTotalCount}</span></>
              : <><span className="oe-num">{unfilteredTotalCount}</span> {unfilteredTotalCount === 1 ? 'record' : 'records'}</>}
          </p>
        </div>
        <div className="oe-filter-grid">
          <label className="oe-filter-field">
            <span>Record type</span>
            <select
              value={type}
              onChange={(event) => {
                const next = event.target.value as '' | 'REQUEST' | 'SESSION';
                setType(next);
                if (next === '' || !(operationsStatusCodes(next) as readonly string[]).includes(status)) setStatus('');
              }}
            >
              <option value="">All records</option>
              <option value="REQUEST">Visit requests</option>
              <option value="SESSION">Visit plans</option>
            </select>
          </label>
          <label className="oe-filter-field">
            <span>Status</span>
            <select
              value={status}
              onChange={(event) => setStatus(event.target.value)}
              disabled={type === ''}
            >
              <option value="">Any status</option>
              {statuses.map((code) => (
                <option key={code} value={code}>
                  {operationsStatusMeta(type as 'REQUEST' | 'SESSION', code).label}
                </option>
              ))}
            </select>
          </label>
          <label className="oe-filter-field">
            <span>City</span>
            <select
              value={city}
              onChange={(event) => {
                const nextCity = event.target.value;
                setCity(nextCity);
                if (nextCity !== '') {
                  const stillValid = scopes.some(
                    (scope) => String(scope.teamId) === team && String(scope.cityId) === nextCity
                  );
                  if (!stillValid) setTeam('');
                }
              }}
            >
              <option value="">All your cities</option>
              {cities.map((scope) => (
                <option key={scope.cityId} value={scope.cityId ?? ''}>{scope.cityName}</option>
              ))}
            </select>
          </label>
          <label className="oe-filter-field">
            <span>Team</span>
            <select value={team} onChange={(event) => setTeam(event.target.value)}>
              <option value="">All your teams</option>
              {teams.map((scope) => (
                <option key={scope.teamId} value={scope.teamId}>{scope.teamName}</option>
              ))}
            </select>
          </label>
          <label className="oe-filter-field">
            <span>Exact record</span>
            <input
              type="text"
              inputMode="numeric"
              autoComplete="off"
              placeholder={type === '' ? 'Choose a type first' : 'e.g. 501'}
              value={recordRef}
              onChange={(event) => setRecordRef(event.target.value)}
              aria-invalid={error ? true : undefined}
              aria-describedby={error ? 'oe-filter-error' : undefined}
            />
          </label>
          <div className="oe-filter-actions">
            <button type="submit" className="oe-btn oe-btn--primary oe-btn--md" disabled={loading}>
              Apply filters
            </button>
            {active ? (
              <button
                type="button"
                className="oe-btn oe-btn--secondary oe-btn--md"
                onClick={() => onChange(EMPTY_OPERATIONS_FILTERS)}
                disabled={loading}
              >
                <RotateCcw size={14} aria-hidden="true" />
                Clear all
              </button>
            ) : null}
          </div>
        </div>
        {scopes.length > 1 ? (
          <p className="oe-filter-hint">
            A Team filter hides records that are still authorized in your other teams.
          </p>
        ) : null}
        {error ? <p id="oe-filter-error" className="oe-filter-error" role="alert">{error}</p> : null}
        {active ? (
          <ul className="oe-filter-chips" aria-label="Active filters">
            {filters.targetType ? (
              <li>
                <button type="button" onClick={() => removeFilter('targetType')} aria-label="Remove record type filter">
                  {filters.targetType === 'REQUEST' ? 'Visit requests' : 'Visit plans'}
                  <X size={12} aria-hidden="true" />
                </button>
              </li>
            ) : null}
            {filters.status ? (
              <li>
                <button type="button" onClick={() => removeFilter('status')} aria-label="Remove status filter">
                  {statusLabel}<X size={12} aria-hidden="true" />
                </button>
              </li>
            ) : null}
            {filters.cityId !== null ? (
              <li>
                <button type="button" onClick={() => removeFilter('cityId')} aria-label="Remove city filter">
                  {cityName ?? `City ${filters.cityId}`}<X size={12} aria-hidden="true" />
                </button>
              </li>
            ) : null}
            {filters.teamId !== null ? (
              <li>
                <button type="button" onClick={() => removeFilter('teamId')} aria-label="Remove team filter">
                  {teamName ?? `Team ${filters.teamId}`}<X size={12} aria-hidden="true" />
                </button>
              </li>
            ) : null}
            {filters.targetId !== null ? (
              <li>
                <button type="button" onClick={() => removeFilter('targetId')} aria-label="Remove exact record filter">
                  {operationsRecordReference(filters.targetId)}<X size={12} aria-hidden="true" />
                </button>
              </li>
            ) : null}
          </ul>
        ) : null}
      </form>
    </section>
  );
};

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
