export interface GrantFactIdentity {
  capability: string;
  scopeType: string;
  cityId: number | null;
  teamId: number | null;
  effectiveAt: string | null;
  expiresAt: string | null;
  revokedAt: string | null;
}

export interface GrantReviewIdentity {
  capability: string;
  scopeType: 'GLOBAL' | 'CITY' | 'TEAM';
  cityId: number | null;
  teamId: number | null;
  effectiveAt: string;
  expiresAt: string;
}

export interface StaffGrantCommandIdentity {
  capability: string;
  scopeType: 'GLOBAL' | 'CITY' | 'TEAM';
  cityId: number | null;
  teamId: number | null;
  effectiveAt: string | null;
  expiresAt: string | null;
  reasonCode: string;
}

export const sameInstant = (actual: string | null, expected: string): boolean => {
  if (!expected || !actual) return false;
  const actualMillis = new Date(actual).getTime();
  const expectedMillis = new Date(expected).getTime();
  return Number.isFinite(actualMillis) && actualMillis === expectedMillis;
};

/**
 * The server may choose the effective time when the operator leaves it blank. That is different
 * from a requested no-expiry grant, whose authoritative expiry must remain null.
 */
const effectiveTimeMatches = (actual: string | null, expected: string): boolean => {
  if (expected) return sameInstant(actual, expected);
  if (!actual) return false;
  return Number.isFinite(new Date(actual).getTime());
};

const expiryMatches = (actual: string | null, expected: string): boolean =>
  expected ? sameInstant(actual, expected) : actual === null;

/**
 * Converts the immutable UI review into the accepted backend command shape. A TEAM command carries
 * only its Team FK; the backend derives the City from the authoritative Team. The review snapshot
 * deliberately retains the selected City for confirmation and readback matching.
 */
export const staffGrantCommandForReview = (
  snapshot: GrantReviewIdentity,
  reasonCode: string
): StaffGrantCommandIdentity => ({
  capability: snapshot.capability,
  scopeType: snapshot.scopeType,
  cityId: snapshot.scopeType === 'CITY' ? snapshot.cityId : null,
  teamId: snapshot.scopeType === 'TEAM' ? snapshot.teamId : null,
  effectiveAt: snapshot.effectiveAt || null,
  expiresAt: snapshot.expiresAt || null,
  reasonCode
});

export const grantState = (
  grant: GrantFactIdentity,
  staffActive: boolean,
  serverTime: string | null
): { label: string; tone: string } => {
  const parsedServerTime = serverTime ? new Date(serverTime).getTime() : Number.NaN;
  if (grant.revokedAt) return { label: 'Revoked · historical', tone: 'revoked' };
  if (!staffActive) return { label: 'Recorded · staff inactive', tone: 'inactive' };
  if (Number.isNaN(parsedServerTime)) {
    return { label: 'Recorded · date status unverified', tone: 'recorded' };
  }
  const referenceTime = parsedServerTime;
  if (grant.expiresAt && new Date(grant.expiresAt).getTime() <= referenceTime) {
    return { label: 'Expired · historical', tone: 'expired' };
  }
  if (grant.effectiveAt && new Date(grant.effectiveAt).getTime() > referenceTime) {
    return { label: 'Starts later · not yet effective', tone: 'future' };
  }
  return { label: 'Recorded · effectiveness not verified', tone: 'recorded' };
};

export const factMatchesGrantReview = (
  fact: GrantFactIdentity,
  snapshot: GrantReviewIdentity
): boolean => fact.capability === snapshot.capability
  && fact.scopeType === snapshot.scopeType
  && fact.cityId === snapshot.cityId
  && fact.teamId === snapshot.teamId
  && effectiveTimeMatches(fact.effectiveAt, snapshot.effectiveAt)
  && expiryMatches(fact.expiresAt, snapshot.expiresAt);

/** Exact identity of an already-reviewed grant, including both persisted instants and revocation. */
export const factMatchesReviewedGrant = (
  fact: GrantFactIdentity,
  reviewed: GrantFactIdentity
): boolean => fact.capability === reviewed.capability
  && fact.scopeType === reviewed.scopeType
  && fact.cityId === reviewed.cityId
  && fact.teamId === reviewed.teamId
  && sameInstant(fact.effectiveAt, reviewed.effectiveAt ?? '')
  && expiryMatches(fact.expiresAt, reviewed.expiresAt ?? '');
