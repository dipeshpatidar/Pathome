import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { CircleAlert, RefreshCw, Search, ShieldCheck, UserRoundCheck, X } from 'lucide-react';
import { ApiRequestError } from '../services/apiError';
import {
  staffAdministrationService,
  type StaffAccessState,
  type StaffAccount,
  type StaffCapabilityFact,
  type StaffScopeOption
} from '../services/staffAdministrationService';
import {
  captureOperationsSession,
  isOperationsSessionActive,
  isOperationsSessionChangedError,
  subscribeOperationsSession,
  type OperationsSession
} from '../utils/operationsSession';
import {
  factMatchesGrantReview,
  factMatchesReviewedGrant,
  grantState,
  staffGrantCommandForReview,
  type GrantReviewIdentity
} from '../utils/staffPermissionPresentation.ts';
import './ownerStaffPermissions.css';

const CAPABILITIES = [
  { value: 'OPS_COORDINATE', label: 'Team coordination', scope: 'TEAM' },
  { value: 'OPS_ROUTING', label: 'City routing and exceptions', scope: 'CITY' },
  { value: 'OPS_SUPERVISE', label: 'Team supervision', scope: 'TEAM' },
  { value: 'OPS_INTAKE', label: 'City intake reading', scope: 'CITY' },
  { value: 'CITY_TEAM_ADMIN', label: 'City administration', scope: 'CITY' },
  { value: 'STAFF_ADMIN', label: 'Global staff administration', scope: 'GLOBAL' }
] as const;
const REASON_CODE = /^[A-Z][A-Z0-9_]{0,63}$/;

type TeamOptionsState = 'loading' | 'ready' | 'error';

interface GrantReviewSnapshot extends GrantReviewIdentity {
  userId: number;
  cityName: string | null;
  teamName: string | null;
  reasonCode: string;
}

interface StaffOperation {
  id: number;
  label: string;
  session: OperationsSession;
  authorityGeneration: number;
  controller: AbortController;
  signal: AbortSignal;
}

const capabilityLabel = (value: string): string =>
  CAPABILITIES.find((entry) => entry.value === value)?.label ?? value;

const formatDate = (value: string | null): string => {
  if (!value) return 'No end date';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return 'Date unavailable';
  return new Intl.DateTimeFormat('en-IN', {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    hour: 'numeric',
    minute: '2-digit'
  }).format(date);
};

const toInstantOrEmpty = (value: string): string | null => {
  if (!value) return '';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
};

const scopeDescription = (
  capability: string,
  city: StaffScopeOption | null,
  team: StaffScopeOption | null
): string => {
  if (capability === 'STAFF_ADMIN') return 'All Cities and staff administration';
  if (CAPABILITIES.find((entry) => entry.value === capability)?.scope === 'CITY') {
    return city ? `${city.displayName} only` : 'Choose a City';
  }
  return team ? `${team.displayName} in ${city?.displayName ?? 'the selected City'}` : 'Choose a Team';
};

export const OwnerStaffPermissions: React.FC = () => {
  const [authorized, setAuthorized] = useState<boolean | null>(null);
  const [query, setQuery] = useState('');
  const [searching, setSearching] = useState(false);
  const [accounts, setAccounts] = useState<StaffAccount[]>([]);
  const [selected, setSelected] = useState<StaffAccount | null>(null);
  const [access, setAccess] = useState<StaffAccessState | null>(null);
  const [cities, setCities] = useState<StaffScopeOption[]>([]);
  const [teams, setTeams] = useState<StaffScopeOption[]>([]);
  const [teamOptionsState, setTeamOptionsState] = useState<TeamOptionsState>('loading');
  const [cityId, setCityId] = useState<number | null>(null);
  const [teamId, setTeamId] = useState<number | null>(null);
  const [capability, setCapability] = useState('OPS_COORDINATE');
  const [effectiveAt, setEffectiveAt] = useState('');
  const [expiresAt, setExpiresAt] = useState('');
  const [reasonCode, setReasonCode] = useState('');
  const [reviewSnapshot, setReviewSnapshot] = useState<GrantReviewSnapshot | null>(null);
  const [revokeReviewId, setRevokeReviewId] = useState<number | null>(null);
  const [revokeReasonCode, setRevokeReasonCode] = useState('');
  const [busy, setBusy] = useState<string | null>(null);
  const [message, setMessage] = useState('');
  const [messageTone, setMessageTone] = useState<'info' | 'success' | 'error'>('info');
  const [serverTime, setServerTime] = useState<string | null>(null);
  const busyRef = useRef(false);
  const searchingRef = useRef(false);
  const operationSequence = useRef(0);
  const searchSequence = useRef(0);
  const activeStaffOperation = useRef<StaffOperation | null>(null);
  const activeSearchOperation = useRef<StaffOperation | null>(null);
  const teamRequestGeneration = useRef(0);
  const verifyGeneration = useRef(0);
  const authorityGeneration = useRef(0);
  const cityIdRef = useRef<number | null>(cityId);

  const capabilityShape = CAPABILITIES.find((entry) => entry.value === capability)?.scope ?? 'TEAM';
  const selectedCity = useMemo(
    () => cities.find((entry) => entry.id === cityId) ?? null,
    [cities, cityId]
  );
  const selectedTeam = useMemo(
    () => teams.find((entry) => entry.id === teamId && entry.cityId === cityId) ?? null,
    [teams, teamId, cityId]
  );
  const dateConflict = capability !== 'STAFF_ADMIN'
    && effectiveAt.length > 0 && expiresAt.length > 0
    && new Date(expiresAt).getTime() <= new Date(effectiveAt).getTime();
  const reasonValid = REASON_CODE.test(reasonCode);
  const revokeReasonValid = REASON_CODE.test(revokeReasonCode);
  const teamContextReady = capabilityShape !== 'TEAM'
    || (cityId !== null && teamOptionsState === 'ready' && selectedTeam !== null
      && selectedTeam.cityId === cityId && teamId === selectedTeam.id);

  const announce = (text: string, tone: 'info' | 'success' | 'error' = 'info') => {
    setMessage(text);
    setMessageTone(tone);
  };

  const clearProtectedStaffState = useCallback(() => {
    teamRequestGeneration.current += 1;
    activeStaffOperation.current?.controller.abort();
    activeSearchOperation.current?.controller.abort();
    activeStaffOperation.current = null;
    activeSearchOperation.current = null;
    operationSequence.current += 1;
    searchSequence.current += 1;
    busyRef.current = false;
    searchingRef.current = false;
    setBusy(null);
    setSearching(false);
    setAccounts([]);
    setSelected(null);
    setAccess(null);
    setCities([]);
    setTeams([]);
    setTeamOptionsState('loading');
    setCityId(null);
    setTeamId(null);
    setReviewSnapshot(null);
    setRevokeReviewId(null);
    setRevokeReasonCode('');
    setMessage('');
    setMessageTone('info');
  }, []);

  const denyAuthority = useCallback((message: string) => {
    setAuthorized(false);
    setServerTime(null);
    clearProtectedStaffState();
    announce(message, 'error');
  }, [clearProtectedStaffState]);

  const verifyAuthority = useCallback(async () => {
    const verification = ++verifyGeneration.current;
    const authority = ++authorityGeneration.current;
    const session = captureOperationsSession();
    if (!session) {
      setAuthorized(false);
      clearProtectedStaffState();
      return;
    }
    try {
      const result = await staffAdministrationService.capabilities({ session });
      if (verification !== verifyGeneration.current || authority !== authorityGeneration.current
          || !isOperationsSessionActive(session)) return;
      const allowed = result.staffActive && result.capabilities.some((entry) =>
        entry.capability === 'STAFF_ADMIN' && entry.scopeType === 'GLOBAL');
      setServerTime(result.serverTime);
      if (!allowed) {
        denyAuthority('Your account does not have current global staff administration authority.');
        return;
      }
      const cityOptions = await staffAdministrationService.cities({ session });
      if (verification !== verifyGeneration.current || authority !== authorityGeneration.current
          || !isOperationsSessionActive(session)) return;
      setAuthorized(true);
      setCities(cityOptions);
      setCityId((current) => cityOptions.some((entry) => entry.id === current)
        ? current
        : cityOptions[0]?.id ?? null);
    } catch (cause) {
      if (verification !== verifyGeneration.current
          || isOperationsSessionChangedError(cause)
          || !isOperationsSessionActive(session)) return;
      if (cause instanceof ApiRequestError && (cause.status === 401 || cause.status === 403)) {
        denyAuthority('Your global staff administration authority has changed.');
      } else {
        setAuthorized(false);
        clearProtectedStaffState();
        announce('Staff permission access could not be verified.', 'error');
      }
    }
  }, [clearProtectedStaffState, denyAuthority]);

  useEffect(() => {
    void verifyAuthority();
  }, [verifyAuthority]);

  useEffect(() => subscribeOperationsSession(() => {
    verifyGeneration.current += 1;
    authorityGeneration.current += 1;
    setAuthorized(null);
    setServerTime(null);
    clearProtectedStaffState();
    void verifyAuthority();
  }), [clearProtectedStaffState, verifyAuthority]);

  useEffect(() => {
    const onFocus = () => { void verifyAuthority(); };
    window.addEventListener('focus', onFocus);
    return () => window.removeEventListener('focus', onFocus);
  }, [verifyAuthority]);

  useEffect(() => () => {
    operationSequence.current += 1;
    searchSequence.current += 1;
    activeStaffOperation.current?.controller.abort();
    activeSearchOperation.current?.controller.abort();
    activeStaffOperation.current = null;
    activeSearchOperation.current = null;
  }, []);

  useEffect(() => {
    cityIdRef.current = cityId;
  }, [cityId]);

  useEffect(() => {
    const request = ++teamRequestGeneration.current;
    setTeams([]);
    setTeamId(null);
    setReviewSnapshot(null);
    if (cityId === null) {
      setTeamOptionsState('ready');
      return;
    }
    const session = captureOperationsSession();
    if (!session || authorized !== true) {
      setTeamOptionsState('loading');
      return;
    }
    setTeamOptionsState('loading');
    let cancelled = false;
    void staffAdministrationService.teams(cityId, { session }).then((options) => {
      if (cancelled || request !== teamRequestGeneration.current
          || cityIdRef.current !== cityId || !isOperationsSessionActive(session)) return;
      const exactCityOptions = options.filter((entry) => entry.cityId === cityId);
      setTeams(exactCityOptions);
      setTeamId(exactCityOptions[0]?.id ?? null);
      setTeamOptionsState('ready');
    }).catch((cause) => {
      if (cancelled || request !== teamRequestGeneration.current
          || isOperationsSessionChangedError(cause)) return;
      setTeams([]);
      setTeamId(null);
      setTeamOptionsState('error');
    });
    return () => {
      cancelled = true;
    };
  }, [authorized, cityId]);

  const search = async () => {
    const trimmed = query.trim();
    if (trimmed.length < 2 || searchingRef.current) return;
    const session = captureOperationsSession();
    const authority = authorityGeneration.current;
    if (!session || !isOperationsSessionActive(session)) return;
    const controller = new AbortController();
    const operation: StaffOperation = {
      id: ++searchSequence.current,
      label: 'search',
      session,
      authorityGeneration: authority,
      controller,
      signal: controller.signal
    };
    activeSearchOperation.current = operation;
    searchingRef.current = true;
    setSearching(true);
    try {
      const page = await staffAdministrationService.searchAccounts(trimmed, {
        session,
        signal: operation.signal
      });
      if (activeSearchOperation.current !== operation
          || !staffOperationIsCurrent(session, authority)) return;
      setAccounts(page.items);
      if (page.items.length === 0) announce('No matching User account was found.');
    } catch (cause) {
      if (isOperationsSessionChangedError(cause)
          || activeSearchOperation.current !== operation
          || !staffOperationIsCurrent(session, authority)) return;
      if (cause instanceof ApiRequestError && (cause.status === 401 || cause.status === 403)) {
        denyAuthority('Your global staff administration authority has changed.');
      } else {
        announce('The staff account search could not be completed.', 'error');
      }
    } finally {
      if (activeSearchOperation.current === operation) {
        activeSearchOperation.current = null;
        searchingRef.current = false;
        setSearching(false);
      }
    }
  };

  const inspect = async (account: StaffAccount) => {
    if (busyRef.current) return;
    const operation = beginStaffOperation('inspect');
    if (!operation) return;
    setSelected(account);
    setAccess(null);
    setReviewSnapshot(null);
    setRevokeReviewId(null);
    setRevokeReasonCode('');
    try {
      const state = await staffAdministrationService.inspect(account.userId, {
        session: operation.session,
        signal: operation.signal
      });
      if (!staffOperationIsCurrent(operation.session, operation.authorityGeneration)) return;
      setAccess(state);
      announce(`Current permissions loaded for ${account.fullName || account.email || `User #${account.userId}`}.`);
    } catch (cause) {
      if (isOperationsSessionChangedError(cause)
          || !staffOperationIsCurrent(operation.session, operation.authorityGeneration)) return;
      if (cause instanceof ApiRequestError && (cause.status === 401 || cause.status === 403)) {
        denyAuthority('Your global staff administration authority has changed.');
      } else {
        announce('Current staff permissions could not be loaded.', 'error');
      }
    } finally {
      finishStaffOperation(operation);
    }
  };

  const refreshSelected = async () => {
    if (selected) await inspect(selected);
  };

  const currentGrantSnapshot = (): GrantReviewSnapshot | null => {
    if (!selected || !reasonValid || dateConflict) return null;
    if (capabilityShape === 'TEAM' && !teamContextReady) return null;
    if (capabilityShape === 'CITY' && cityId === null) return null;
    const effective = capability === 'STAFF_ADMIN' ? '' : toInstantOrEmpty(effectiveAt);
    const expires = capability === 'STAFF_ADMIN' ? '' : toInstantOrEmpty(expiresAt);
    if (effective === null || expires === null) return null;
    return {
      userId: selected.userId,
      capability,
      scopeType: capabilityShape,
      cityId: capabilityShape === 'GLOBAL' ? null : cityId,
      cityName: capabilityShape === 'GLOBAL' ? null : selectedCity?.displayName ?? null,
      teamId: capabilityShape === 'TEAM' ? teamId : null,
      teamName: capabilityShape === 'TEAM' ? selectedTeam?.displayName ?? null : null,
      effectiveAt: effective,
      expiresAt: expires,
      reasonCode
    };
  };

  const currentSnapshotMatches = (snapshot: GrantReviewSnapshot | null): boolean => {
    const current = currentGrantSnapshot();
    return snapshot !== null && current !== null
      && snapshot.userId === current.userId
      && snapshot.capability === current.capability
      && snapshot.scopeType === current.scopeType
      && snapshot.cityId === current.cityId
      && snapshot.teamId === current.teamId
      && snapshot.effectiveAt === current.effectiveAt
      && snapshot.expiresAt === current.expiresAt
      && snapshot.reasonCode === current.reasonCode;
  };

  const beginStaffOperation = (label: string): StaffOperation | null => {
    if (busyRef.current) return null;
    const session = captureOperationsSession();
    const authority = authorityGeneration.current;
    if (!session || !isOperationsSessionActive(session) || authorized !== true) return null;
    const controller = new AbortController();
    const operation: StaffOperation = {
      id: ++operationSequence.current,
      label,
      session,
      authorityGeneration: authority,
      controller,
      signal: controller.signal
    };
    busyRef.current = true;
    activeStaffOperation.current?.controller.abort();
    activeStaffOperation.current = operation;
    setBusy(label);
    return operation;
  };

  const staffOperationIsCurrent = (session: OperationsSession, authority: number): boolean =>
    isOperationsSessionActive(session) && authority === authorityGeneration.current;

  const finishStaffOperation = (operation: StaffOperation): void => {
    if (activeStaffOperation.current !== operation) return;
    activeStaffOperation.current = null;
    busyRef.current = false;
    setBusy(null);
  };

  const submitGrant = async () => {
    if (!selected || !currentSnapshotMatches(reviewSnapshot)) return;
    const snapshot = reviewSnapshot;
    if (snapshot === null) return;
    const operation = beginStaffOperation('grant');
    if (!operation) return;
    let dispatched = false;
    const beforeIds = new Set(access?.grants.map((entry) => entry.id) ?? []);
    try {
      const result = await staffAdministrationService.createGrant(
        selected.userId,
        staffGrantCommandForReview(snapshot, snapshot.reasonCode),
        {
        session: operation.session,
        signal: operation.signal,
        onDispatch: () => { dispatched = true; }
        }
      );
      if (!staffOperationIsCurrent(operation.session, operation.authorityGeneration)) return;
      setReviewSnapshot(null);
      const state = await staffAdministrationService.inspect(selected.userId, {
        session: operation.session,
        signal: operation.signal
      });
      if (!staffOperationIsCurrent(operation.session, operation.authorityGeneration)) return;
      setAccess(state);
      const readback = state.grants.find((entry) => entry.id === result.id);
      if (!factMatchesGrantReview(result, snapshot)
          || !readback || !factMatchesGrantReview(readback, snapshot)) {
        announce('The server recorded a grant, but the authoritative readback did not show that exact fact. Refresh before making another change.', 'error');
        return;
      }
      announce(`${capabilityLabel(snapshot.capability)} is recorded and visible in current access. Current effectiveness is not asserted by this screen.`, 'success');
    } catch (cause) {
      if (isOperationsSessionChangedError(cause)
          || !staffOperationIsCurrent(operation.session, operation.authorityGeneration)) return;
      if (cause instanceof ApiRequestError && (cause.status === 401 || cause.status === 403)) {
        denyAuthority('Your global staff administration authority has changed.');
        return;
      }
      if (cause instanceof ApiRequestError && cause.status === 409) {
        announce('An overlapping permission already exists or the account state changed. Current access was not overwritten.', 'error');
        return;
      }
      if (dispatched) {
        try {
          const state = await staffAdministrationService.inspect(selected.userId, {
            session: operation.session,
            signal: operation.signal
          });
          if (!staffOperationIsCurrent(operation.session, operation.authorityGeneration)) return;
          setAccess(state);
          const candidates = state.grants.filter((entry) =>
            !beforeIds.has(entry.id) && factMatchesGrantReview(entry, snapshot));
          announce(candidates.length === 1
            ? 'The assignment response was lost, but authoritative readback shows one new matching grant record. Review it before retrying.'
            : 'The assignment outcome is uncertain. Current access was refreshed; do not retry until the state is reviewed.',
          'error');
        } catch (readFailure) {
          if (isOperationsSessionChangedError(readFailure)
              || !staffOperationIsCurrent(operation.session, operation.authorityGeneration)) return;
          announce('The assignment outcome is uncertain and readback failed. Do not retry until access can be refreshed.', 'error');
        }
      } else {
        announce('The assignment was not sent. Review current access before trying again.', 'error');
      }
    } finally {
      finishStaffOperation(operation);
    }
  };

  const submitRevoke = async (grant: StaffCapabilityFact) => {
    if (!selected || revokeReviewId !== grant.id || !revokeReasonValid) return;
    const operation = beginStaffOperation('revoke');
    if (!operation) return;
    let dispatched = false;
    try {
      const result = await staffAdministrationService.revoke(grant.id, revokeReasonCode, {
        session: operation.session,
        signal: operation.signal,
        onDispatch: () => { dispatched = true; }
      });
      if (!staffOperationIsCurrent(operation.session, operation.authorityGeneration)) return;
      const acknowledged = result.id === grant.id && factMatchesReviewedGrant(result, grant);
      setRevokeReviewId(null);
      setRevokeReasonCode('');
      const state = await staffAdministrationService.inspect(selected.userId, {
        session: operation.session,
        signal: operation.signal
      });
      if (!staffOperationIsCurrent(operation.session, operation.authorityGeneration)) return;
      setAccess(state);
      const readback = state.grants.find((entry) => entry.id === result.id);
      const confirmed = acknowledged && readback !== undefined
        && factMatchesReviewedGrant(readback, grant) && Boolean(readback.revokedAt);
      announce(confirmed
        ? `${capabilityLabel(grant.capability)} is confirmed revoked in authoritative history. Other grants remain separate.`
        : 'The server accepted the revocation, but authoritative readback did not confirm that exact grant as revoked.',
      confirmed ? 'success' : 'error');
    } catch (cause) {
      if (isOperationsSessionChangedError(cause)
          || !staffOperationIsCurrent(operation.session, operation.authorityGeneration)) return;
      if (cause instanceof ApiRequestError && (cause.status === 401 || cause.status === 403)) {
        denyAuthority('Your global staff administration authority has changed.');
        return;
      }
      if (dispatched) {
        try {
          const state = await staffAdministrationService.inspect(selected.userId, {
            session: operation.session,
            signal: operation.signal
          });
          if (!staffOperationIsCurrent(operation.session, operation.authorityGeneration)) return;
          setAccess(state);
          const readback = state.grants.find((entry) => entry.id === grant.id);
          const confirmed = readback !== undefined
            && factMatchesReviewedGrant(readback, grant) && Boolean(readback.revokedAt);
          announce(confirmed
            ? `${capabilityLabel(grant.capability)} is confirmed revoked by authoritative readback after a lost response.`
            : 'The revocation outcome is uncertain. Current access was refreshed; do not retry until it is reviewed.',
          confirmed ? 'success' : 'error');
        } catch (readFailure) {
          if (isOperationsSessionChangedError(readFailure)
              || !staffOperationIsCurrent(operation.session, operation.authorityGeneration)) return;
          announce('The revocation outcome is uncertain and readback failed. Do not retry until access can be refreshed.', 'error');
        }
      } else {
        announce('The revocation was not sent. Review current access before trying again.', 'error');
      }
    } finally {
      finishStaffOperation(operation);
    }
  };

  if (authorized === null) {
    return <section className="staff-permissions staff-permissions--status">Checking staff administration access…</section>;
  }
  if (!authorized) {
    return (
      <section className="staff-permissions staff-permissions--status" aria-live="polite">
        <ShieldCheck size={26} aria-hidden="true" />
        <h1>Staff permission management is unavailable</h1>
        <p>The signed-in account needs an active global Staff Admin grant. A legacy Admin label alone does not grant this access.</p>
      </section>
    );
  }

  return (
    <section className="staff-permissions" aria-labelledby="staff-permissions-title">
      <header>
        <div>
          <p className="staff-permissions-eyebrow"><ShieldCheck size={16} /> Owner and Admin</p>
          <h1 id="staff-permissions-title">Staff permissions</h1>
          <p>Find an existing staff account, review its current fixed grants, and assign or revoke one permission at a time.</p>
        </div>
      </header>

      <div className="staff-permissions-notice" role="status" aria-live="polite" data-tone={messageTone}>
        {message || 'No staff account or permission is preselected.'}
      </div>

      <div className="staff-permissions-grid">
        <section className="staff-permissions-card">
          <h2>Find staff</h2>
          <p>Search by User ID, name, or email.</p>
          <form onSubmit={(event) => { event.preventDefault(); void search(); }}>
            <label htmlFor="staff-search">User ID, name, or email</label>
            <div className="staff-search-row">
              <input id="staff-search" value={query} onChange={(event) => setQuery(event.target.value)}
                autoComplete="off" minLength={2} maxLength={120} />
              <button type="submit" disabled={searching || query.trim().length < 2}>
                <Search size={17} aria-hidden="true" /> {searching ? 'Searching…' : 'Search'}
              </button>
            </div>
          </form>
          <div className="staff-account-list">
            {accounts.map((account) => (
              <button key={account.userId} type="button"
                className={selected?.userId === account.userId ? 'selected' : ''}
                onClick={() => void inspect(account)}>
                <span className="staff-account-avatar" aria-hidden="true"><UserRoundCheck size={17} /></span>
                <span>
                  <strong>{account.fullName || `User #${account.userId}`}</strong>
                  <small>{account.email || 'Email unavailable'} · {account.staffActive ? 'Active staff' : account.profileExists ? 'Staff inactive' : 'No staff profile'}</small>
                </span>
              </button>
            ))}
            {accounts.length === 0 && query.trim().length >= 2 && !searching && (
              <p className="staff-permissions-muted">No matching accounts returned.</p>
            )}
          </div>
        </section>

        <section className="staff-permissions-card">
          <div className="staff-card-heading">
            <div>
              <h2>Current access</h2>
              <p>Recorded, future, expired, and revoked facts remain distinct. This list does not assert effective access.</p>
            </div>
            {selected && (
              <button type="button" className="staff-quiet-button" onClick={() => void refreshSelected()} disabled={busy !== null}>
                <RefreshCw size={16} /> Refresh
              </button>
            )}
          </div>
          {!selected && <p className="staff-permissions-muted">Select a staff account to review its permissions.</p>}
          {selected && busy === 'inspect' && <p className="staff-permissions-muted">Loading current staff permissions…</p>}
          {selected && access && (
            <>
              <div className="staff-identity">
                <strong>{selected.fullName || `User #${selected.userId}`}</strong>
                <span>{selected.email || 'Email unavailable'}</span>
                <span className={`staff-status ${access.staffActive ? 'active' : 'inactive'}`}>
                  {access.staffActive ? 'Staff profile active' : 'Staff profile inactive'}
                </span>
              </div>
              <div className="staff-grant-list">
                {access.grants.length === 0 && <p className="staff-permissions-muted">No permission history exists for this account.</p>}
                {access.grants.map((entry) => {
                  const state = grantState(entry, access.staffActive, serverTime);
                  const canRevoke = !entry.revokedAt;
                  return (
                    <article key={entry.id}>
                      <div className="staff-grant-heading">
                        <div>
                          <strong>{capabilityLabel(entry.capability)}</strong>
                          <small>{entry.capability}</small>
                        </div>
                        <span className={`staff-status ${state.tone}`}>{state.label}</span>
                      </div>
                      <p>{entry.scopeType === 'GLOBAL'
                        ? 'All Cities'
                        : entry.scopeType === 'CITY'
                          ? entry.cityDisplayName ?? 'City scope'
                          : `${entry.teamDisplayName ?? 'Team'} · ${entry.cityDisplayName ?? 'City'}`}</p>
                      <dl>
                        <div><dt>Effective</dt><dd>{formatDate(entry.effectiveAt)}</dd></div>
                        <div><dt>Expires on</dt><dd>{formatDate(entry.expiresAt)}</dd></div>
                        {entry.revokedAt && <div><dt>Revoked</dt><dd>{formatDate(entry.revokedAt)}</dd></div>}
                      </dl>
                      {canRevoke && revokeReviewId !== entry.id && (
                        <button type="button" className="staff-danger-button" onClick={() => {
                          setRevokeReviewId(entry.id);
                          setRevokeReasonCode('');
                        }}>
                          Review revocation
                        </button>
                      )}
                      {revokeReviewId === entry.id && (
                        <div className="staff-confirm">
                          <p>Revoke only this {capabilityLabel(entry.capability).toLowerCase()} permission? Unrelated permissions remain.</p>
                          <label htmlFor={`staff-revoke-reason-${entry.id}`}>
                            <span>Reason code</span>
                            <input id={`staff-revoke-reason-${entry.id}`} value={revokeReasonCode}
                              maxLength={64} autoCapitalize="characters"
                              placeholder="e.g. TEAM_COVERAGE_CHANGE"
                              onChange={(event) => setRevokeReasonCode(event.target.value.toUpperCase())} />
                          </label>
                          {revokeReasonCode.length > 0 && !revokeReasonValid && (
                            <p className="staff-date-error">Use an uppercase letter followed by letters, digits, or underscores.</p>
                          )}
                          <div>
                            <button type="button" className="staff-danger-button"
                              onClick={() => void submitRevoke(entry)}
                              disabled={busy !== null || !revokeReasonValid}>
                              Confirm revocation
                            </button>
                            <button type="button" className="staff-quiet-button" onClick={() => {
                              setRevokeReviewId(null);
                              setRevokeReasonCode('');
                            }}>
                              <X size={16} /> Keep permission
                            </button>
                          </div>
                        </div>
                      )}
                    </article>
                  );
                })}
              </div>
            </>
          )}
        </section>
      </div>

      {selected && access?.staffActive && (
        <section className="staff-permissions-card staff-assign">
          <div>
            <h2>Assign permission</h2>
            <p>Each assignment creates one fixed grant. Different permissions and scopes remain independent.</p>
          </div>
          <div className="staff-assign-grid">
            <label htmlFor="staff-permission">
              <span>Permission</span>
              <select id="staff-permission" value={capability} onChange={(event) => {
                setCapability(event.target.value);
                setReviewSnapshot(null);
              }}>
                {CAPABILITIES.map((entry) => <option key={entry.value} value={entry.value}>{entry.label}</option>)}
              </select>
            </label>
            {capabilityShape !== 'GLOBAL' && (
              <label htmlFor="staff-city">
                <span>City</span>
                <select id="staff-city" value={cityId ?? ''} onChange={(event) => {
                  const nextCityId = Number(event.target.value);
                  cityIdRef.current = nextCityId;
                  teamRequestGeneration.current += 1;
                  setCityId(nextCityId);
                  setTeams([]);
                  setTeamId(null);
                  setTeamOptionsState('loading');
                  setReviewSnapshot(null);
                }}>
                  {cities.map((entry) => <option key={entry.id} value={entry.id}>{entry.displayName}</option>)}
                </select>
              </label>
            )}
            {capabilityShape === 'TEAM' && (
              <label htmlFor="staff-team">
                <span>Team</span>
                <select id="staff-team" value={teamId ?? ''} disabled={teamOptionsState !== 'ready'}
                  onChange={(event) => {
                  setTeamId(Number(event.target.value));
                  setReviewSnapshot(null);
                }}>
                  {teams.map((entry) => <option key={entry.id} value={entry.id}>{entry.displayName}</option>)}
                </select>
              </label>
            )}
            {capability !== 'STAFF_ADMIN' && (
              <>
                <label htmlFor="staff-effective-at">
                  <span>Starts</span>
                  <input id="staff-effective-at" type="datetime-local" value={effectiveAt}
                    onChange={(event) => { setEffectiveAt(event.target.value); setReviewSnapshot(null); }} />
                </label>
                <label htmlFor="staff-expires-at">
                  <span>Expires</span>
                  <input id="staff-expires-at" type="datetime-local" value={expiresAt}
                    onChange={(event) => { setExpiresAt(event.target.value); setReviewSnapshot(null); }} />
                </label>
              </>
            )}
            <label htmlFor="staff-assignment-reason">
              <span>Reason code</span>
              <input id="staff-assignment-reason" value={reasonCode} maxLength={64}
                autoCapitalize="characters" placeholder="e.g. CITY_ROUTING_COVERAGE"
                onChange={(event) => {
                  setReasonCode(event.target.value.toUpperCase());
                  setReviewSnapshot(null);
                }} />
            </label>
          </div>
          {capabilityShape === 'TEAM' && teamOptionsState === 'loading' && (
            <p className="staff-permissions-muted">Loading Teams for the selected City…</p>
          )}
          {capabilityShape === 'TEAM' && teamOptionsState === 'error' && (
            <p className="staff-date-error">Team options could not be loaded for this City.</p>
          )}
          {capabilityShape === 'TEAM' && teamOptionsState === 'ready' && teams.length === 0 && (
            <p className="staff-date-error">No Team is available in this City.</p>
          )}
          {reasonCode.length > 0 && !reasonValid && (
            <p className="staff-date-error">Use an uppercase letter followed by letters, digits, or underscores.</p>
          )}
          <div className="staff-review-box">
            <strong>Permission to be recorded</strong>
            <p>{capabilityLabel(capability)}: {scopeDescription(capability, selectedCity, selectedTeam)}</p>
            <small>Technical capability: {capability}</small>
            {dateConflict && <p className="staff-date-error">Expiry must be later than the start time.</p>}
          </div>
          {reviewSnapshot === null ? (
            <button type="button" className="staff-primary-button"
              disabled={busy !== null || currentGrantSnapshot() === null}
              onClick={() => setReviewSnapshot(currentGrantSnapshot())}>
              Review and confirm
            </button>
          ) : (
            <div className="staff-confirm staff-confirm--assign">
              <p>
                Assign {capabilityLabel(reviewSnapshot.capability).toLowerCase()} for
                {' '}{reviewSnapshot.scopeType === 'GLOBAL'
                  ? 'all Cities'
                  : reviewSnapshot.scopeType === 'CITY'
                    ? `${reviewSnapshot.cityName ?? 'the selected City'} only`
                    : `${reviewSnapshot.teamName ?? 'the selected Team'} in ${reviewSnapshot.cityName ?? 'the selected City'}`}.
                This creates one immutable permission record.
              </p>
              <div>
                <button type="button" className="staff-primary-button"
                  onClick={() => void submitGrant()}
                  disabled={busy !== null || !currentSnapshotMatches(reviewSnapshot)}>
                  {busy === 'grant' ? 'Assigning…' : 'Confirm assignment'}
                </button>
                <button type="button" className="staff-quiet-button" onClick={() => setReviewSnapshot(null)}>
                  Cancel
                </button>
              </div>
            </div>
          )}
        </section>
      )}
    </section>
  );
};
