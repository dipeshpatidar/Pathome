import type {
  TenantAvailabilityCommand,
  TenantAvailabilityView,
  TenantVisitRequest
} from '../services/tenantVisitService';
import {
  compareOperationsInstants,
  instantToZoneInstant,
  isOperationsZone,
  resolveAvailabilityFieldInstant
} from './operationsWorkspacePresentation';

/**
 * The visit zone is a fact chosen by the tenant, never a browser or City assumption. Asia/Kolkata is
 * offered as the visible default because Pathome's current launch market is India, but every saved
 * request keeps the IANA zone it was actually entered in.
 */
export const TENANT_VISIT_ZONE = 'Asia/Kolkata';

/** Zones offered in the two tenant forms. A saved zone outside this list is added when it is opened. */
export const TENANT_VISIT_ZONES = [
  'Asia/Kolkata',
  'Asia/Kathmandu',
  'Asia/Dubai',
  'Asia/Singapore',
  'Europe/London',
  'America/New_York',
  'UTC'
] as const;

export type TenantAvailabilityFieldName = 'start' | 'end' | 'preferred';
export type TenantAvailabilityProvenance = 'UNTOUCHED' | 'EDITED';

export type TenantAvailabilityError =
  | 'missing'
  | 'invalid'
  | 'zone'
  | 'gap'
  | 'ambiguous'
  | 'order'
  | 'preferred-outside';

/**
 * Six independent civil fields. The end and preferred fields carry their own calendar dates so an
 * overnight window, a following-day preference and a year/month rollover remain exactly what the
 * tenant typed.
 */
export interface TenantAvailabilityFields {
  startDate: string;
  startTime: string;
  endDate: string;
  endTime: string;
  preferredDate: string;
  preferredTime: string;
}

export interface TenantAvailabilityFormValue extends TenantAvailabilityFields {
  zoneId: string;
}

export interface TenantAvailabilityOpening {
  zoneId: string | null;
  startAt: string | null;
  endAt: string | null;
  preferredAt: string | null;
}

export interface TenantAvailabilityFactSnapshot {
  requestId: number;
  version: number;
  startAt: string | null;
  endAt: string | null;
  zoneId: string | null;
  preferredAt: string | null;
}

export type TenantAvailabilityBuild =
  | { ok: true; command: TenantAvailabilityCommand }
  | { ok: false; error: TenantAvailabilityError };

export interface RecordedTenantAvailability {
  startAt: string | null;
  endAt: string | null;
  preferredAt: string | null;
  form: TenantAvailabilityFormValue;
  provenance?: Partial<Record<TenantAvailabilityFieldName, TenantAvailabilityProvenance>>;
}

const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;
const TIME_PATTERN = /^([01]\d|2[0-3]):[0-5]\d$/;
const EMPTY_FIELDS: TenantAvailabilityFields = {
  startDate: '',
  startTime: '',
  endDate: '',
  endTime: '',
  preferredDate: '',
  preferredTime: ''
};

const fieldDate = (field: TenantAvailabilityFieldName): keyof TenantAvailabilityFields =>
  field === 'start' ? 'startDate' : field === 'end' ? 'endDate' : 'preferredDate';
const fieldTime = (field: TenantAvailabilityFieldName): keyof TenantAvailabilityFields =>
  field === 'start' ? 'startTime' : field === 'end' ? 'endTime' : 'preferredTime';

const validZoneOrDefault = (zoneId: string | null | undefined): string =>
  isOperationsZone(zoneId) ? (zoneId as string) : '';

const civilInput = (instant: string | null, zoneId: string): { date: string; time: string } | null => {
  if (!instant || !isOperationsZone(zoneId)) return null;
  const zoned = instantToZoneInstant(instant, zoneId);
  if (!zoned) return null;
  return { date: zoned.slice(0, 10), time: zoned.slice(11, 16) };
};

const fieldsFromInstants = (
  zoneId: string,
  values: { startAt: string | null; endAt: string | null; preferredAt: string | null }
): TenantAvailabilityFields => {
  const start = civilInput(values.startAt, zoneId);
  const end = civilInput(values.endAt, zoneId);
  const preferred = civilInput(values.preferredAt, zoneId);
  return {
    startDate: start?.date ?? '',
    startTime: start?.time ?? '',
    endDate: end?.date ?? '',
    endTime: end?.time ?? '',
    preferredDate: preferred?.date ?? '',
    preferredTime: preferred?.time ?? ''
  };
};

/**
 * Opening form for an existing request. A valid saved zone is authoritative and preserved. A missing
 * or malformed saved zone is not silently mapped to a city: the tenant must choose the zone before
 * those recorded instants may be edited again.
 */
export const tenantAvailabilityFormFromRequest = (request: TenantVisitRequest): TenantAvailabilityFormValue => {
  const zoneId = validZoneOrDefault(request.availabilityZoneId);
  if (!zoneId) return { ...EMPTY_FIELDS, zoneId: '' };
  return {
    ...fieldsFromInstants(zoneId, {
      startAt: request.availabilityStartAt,
      endAt: request.availabilityEndAt,
      preferredAt: request.preferredAt
    }),
    zoneId
  };
};

export const tenantAvailabilityProvenanceFromRequest = (
  request: TenantVisitRequest
): Record<TenantAvailabilityFieldName, TenantAvailabilityProvenance> => ({
  start: request.availabilityStartAt ? 'UNTOUCHED' : 'EDITED',
  end: request.availabilityEndAt ? 'UNTOUCHED' : 'EDITED',
  preferred: request.preferredAt ? 'UNTOUCHED' : 'EDITED'
});

/**
 * Builds the command from explicit provenance. `UNTOUCHED` fields are re-expressed from the exact
 * opening instant, preserving seconds, fractions and the correct occurrence of a repeated hour.
 * `EDITED` fields are civil input in the selected zone and are resolved through the precise shared
 * resolver. A changed zone therefore re-labels untouched values without moving them, while an edited
 * wall clock keeps its typed civil intent.
 */
const buildFromProvenance = (
  form: TenantAvailabilityFormValue,
  expectedRequestVersion: number,
  recorded: RecordedTenantAvailability | undefined,
  provenance: Record<TenantAvailabilityFieldName, TenantAvailabilityProvenance>
): TenantAvailabilityBuild => {
  if (!isOperationsZone(form.zoneId)) return { ok: false, error: 'zone' };
  const zoneId = form.zoneId;

  const resolveField = (
    field: TenantAvailabilityFieldName,
    openingInstant: string | null,
    required: boolean
  ): { value: string | null; error: TenantAvailabilityError | null } => {
    const date = form[fieldDate(field)].trim();
    const time = form[fieldTime(field)].trim();
    if (!date && !time) return required ? { value: null, error: 'missing' } : { value: null, error: null };
    if (!DATE_PATTERN.test(date) || !TIME_PATTERN.test(time)) return { value: null, error: 'missing' };
    if (provenance[field] === 'UNTOUCHED') {
      const preserved = instantToZoneInstant(openingInstant, zoneId);
      return preserved ? { value: preserved, error: null } : { value: null, error: 'invalid' };
    }
    const resolved = resolveAvailabilityFieldInstant({
      value: `${date}T${time}`,
      provenance: 'EDITED',
      zoneId,
      initialInstant: openingInstant
    });
    if (resolved.value === null) {
      return {
        value: null,
        error: resolved.error === 'gap' || resolved.error === 'ambiguous' ? resolved.error : 'invalid'
      };
    }
    return { value: resolved.value, error: null };
  };

  const start = resolveField('start', recorded?.startAt ?? null, true);
  if (start.value === null) return { ok: false, error: start.error ?? 'invalid' };
  const end = resolveField('end', recorded?.endAt ?? null, true);
  if (end.value === null) return { ok: false, error: end.error ?? 'invalid' };

  const compared = compareOperationsInstants(start.value, end.value);
  if (compared === null || compared >= 0) return { ok: false, error: 'order' };

  const preferred = resolveField('preferred', recorded?.preferredAt ?? null, false);
  if (preferred.value === null && preferred.error !== null) {
    return { ok: false, error: preferred.error };
  }
  if (preferred.value !== null) {
    const fromStart = compareOperationsInstants(preferred.value, start.value);
    const toEnd = compareOperationsInstants(preferred.value, end.value);
    if (fromStart === null || toEnd === null || fromStart < 0 || toEnd > 0) {
      return { ok: false, error: 'preferred-outside' };
    }
  }

  return {
    ok: true,
    command: {
      expectedRequestVersion,
      availabilityStartAt: start.value,
      availabilityEndAt: end.value,
      availabilityZoneId: zoneId,
      preferredAt: preferred.value
    }
  };
};

/**
 * Creation and simple-call form. Without recorded provenance every non-blank field is an explicit
 * edit, so the selected zone is what gives the typed wall clock its meaning.
 */
export const buildTenantAvailabilityWindow = (
  input: TenantAvailabilityFormValue,
  expectedRequestVersion: number,
  recorded?: RecordedTenantAvailability
): TenantAvailabilityBuild => {
  const provenance: Record<TenantAvailabilityFieldName, TenantAvailabilityProvenance> = recorded?.provenance
    ? {
        start: recorded.provenance.start ?? 'EDITED',
        end: recorded.provenance.end ?? 'EDITED',
        preferred: recorded.provenance.preferred ?? 'EDITED'
      }
    : { start: 'EDITED', end: 'EDITED', preferred: 'EDITED' };
  return buildFromProvenance(input, expectedRequestVersion, recorded, provenance);
};

export const buildTenantAvailabilityDraftCommand = (
  form: TenantAvailabilityFormValue,
  expectedRequestVersion: number,
  opening: TenantAvailabilityOpening,
  provenance: Record<TenantAvailabilityFieldName, TenantAvailabilityProvenance>
): TenantAvailabilityBuild =>
  buildFromProvenance(form, expectedRequestVersion, {
    startAt: opening.startAt,
    endAt: opening.endAt,
    preferredAt: opening.preferredAt,
    form,
    provenance
  }, provenance);

/** An explicit all-null clear remains valid only in the same pre-booking states on the server. */
export const clearTenantAvailability = (expectedRequestVersion: number): TenantAvailabilityCommand => ({
  expectedRequestVersion,
  availabilityStartAt: null,
  availabilityEndAt: null,
  availabilityZoneId: null,
  preferredAt: null
});

/**
 * Applies one field edit and marks only its rendered date/time pair as intentionally edited. Editing
 * a field to text identical to its displayed value still changes provenance: it is now a deliberate
 * civil choice, not the recorded instant.
 */
export const editTenantAvailabilityField = (
  form: TenantAvailabilityFormValue,
  field: TenantAvailabilityFieldName,
  key: keyof TenantAvailabilityFields,
  value: string,
  provenance: Record<TenantAvailabilityFieldName, TenantAvailabilityProvenance>
): { form: TenantAvailabilityFormValue; provenance: Record<TenantAvailabilityFieldName, TenantAvailabilityProvenance> } => ({
  form: { ...form, [key]: value },
  provenance: { ...provenance, [field]: 'EDITED' }
});

/**
 * Changes the authoritative zone. Untouched fields are re-expressed in the new zone from their exact
 * opening instants; edited fields keep the wall clock the tenant typed and acquire the new zone.
 */
export const changeTenantAvailabilityZone = (
  form: TenantAvailabilityFormValue,
  zoneId: string,
  opening: TenantAvailabilityOpening,
  provenance: Record<TenantAvailabilityFieldName, TenantAvailabilityProvenance>
): TenantAvailabilityFormValue => {
  if (!isOperationsZone(zoneId)) return { ...form, zoneId };
  const next = { ...form, zoneId };
  (['start', 'end', 'preferred'] as const).forEach(field => {
    if (provenance[field] !== 'UNTOUCHED') return;
    const instant = field === 'start' ? opening.startAt : field === 'end' ? opening.endAt : opening.preferredAt;
    const civil = civilInput(instant, zoneId);
    next[fieldDate(field)] = civil?.date ?? '';
    next[fieldTime(field)] = civil?.time ?? '';
  });
  return next;
};

/**
 * The current date in a selected visit zone. Browser-local `Date` arithmetic must not decide whether
 * a civil date is past when the visit is in another zone.
 */
export const tenantVisitZoneDate = (zoneId: string, now: Date = new Date()): string | null => {
  if (!isOperationsZone(zoneId)) return null;
  try {
    const parts = new Intl.DateTimeFormat('en-CA', {
      timeZone: zoneId,
      year: 'numeric',
      month: '2-digit',
      day: '2-digit'
    }).formatToParts(now);
    const read = (type: string) => parts.find(part => part.type === type)?.value ?? '';
    const year = read('year');
    const month = read('month');
    const day = read('day');
    return year && month && day ? `${year}-${month}-${day}` : null;
  } catch {
    return null;
  }
};

/** The earliest selectable visit date: tomorrow according to the selected visit zone. */
export const tenantVisitMinimumDate = (zoneId: string, now: Date = new Date()): string => {
  const today = tenantVisitZoneDate(zoneId, now);
  if (!today) return '';
  const [year, month, day] = today.split('-').map(Number);
  const next = new Date(Date.UTC(year, month - 1, day + 1));
  return next.toISOString().slice(0, 10);
};

const civilDateLabel = (date: string): string | null => {
  if (!DATE_PATTERN.test(date)) return null;
  const [year, month, day] = date.split('-').map(Number);
  const value = new Date(Date.UTC(year, month - 1, day));
  return new Intl.DateTimeFormat('en-IN', {
    timeZone: 'UTC',
    weekday: 'short',
    day: 'numeric',
    month: 'short'
  }).format(value);
};

const clockLabel = (time: string): string | null => {
  const match = /^([01]\d|2[0-3]):([0-5]\d)$/.exec(time);
  if (!match) return null;
  const hour = Number(match[1]);
  return `${hour % 12 || 12}:${match[2]} ${hour >= 12 ? 'pm' : 'am'}`;
};

/**
 * One availability window in the zone that actually owns it. Both calendar dates are shown whenever
 * the window crosses midnight; the zone label therefore always describes the clocks beside it.
 */
export const formatTenantAvailabilityWindow = (
  startAt: string | null,
  endAt: string | null,
  zoneId: string | null
): string | null => {
  if (!startAt || !endAt || !isOperationsZone(zoneId)) return null;
  const startZoned = instantToZoneInstant(startAt, zoneId);
  const endZoned = instantToZoneInstant(endAt, zoneId);
  if (!startZoned || !endZoned) return null;
  const startDate = startZoned.slice(0, 10);
  const startTime = startZoned.slice(11, 16);
  const endDate = endZoned.slice(0, 10);
  const endTime = endZoned.slice(11, 16);
  const startDateText = civilDateLabel(startDate);
  const endDateText = civilDateLabel(endDate);
  const startTimeText = clockLabel(startTime);
  const endTimeText = clockLabel(endTime);
  if (!startDateText || !endDateText || !startTimeText || !endTimeText) return null;
  return startDate === endDate
    ? `${startDateText} · ${startTimeText}–${endTimeText} · ${zoneId}`
    : `${startDateText} ${startTimeText} – ${endDateText} ${endTimeText} · ${zoneId}`;
};

export const formatTenantAvailability = (request: TenantVisitRequest): string | null =>
  formatTenantAvailabilityWindow(
    request.availabilityStartAt,
    request.availabilityEndAt,
    request.availabilityZoneId
  );

export const formatTenantPreferredTime = (request: TenantVisitRequest): string | null => {
  if (!request.preferredAt || !isOperationsZone(request.availabilityZoneId)) return null;
  const zoned = instantToZoneInstant(request.preferredAt, request.availabilityZoneId);
  if (!zoned) return null;
  const date = civilDateLabel(zoned.slice(0, 10));
  const time = clockLabel(zoned.slice(11, 16));
  return date && time ? `${date} · ${time} · ${request.availabilityZoneId}` : null;
};

/** Exact equality of the four persisted V37 facts, using the precise instant comparator. */
export const tenantAvailabilityFactsEqual = (
  left: {
    availabilityStartAt: string | null;
    availabilityEndAt: string | null;
    availabilityZoneId: string | null;
    preferredAt: string | null;
  },
  right: {
    availabilityStartAt: string | null;
    availabilityEndAt: string | null;
    availabilityZoneId: string | null;
    preferredAt: string | null;
  }
): boolean => {
  const nullableInstantEqual = (a: string | null, b: string | null): boolean => {
    if (a === null || b === null) return a === b;
    return compareOperationsInstants(a, b) === 0;
  };
  return left.availabilityZoneId === right.availabilityZoneId
    && nullableInstantEqual(left.availabilityStartAt, right.availabilityStartAt)
    && nullableInstantEqual(left.availabilityEndAt, right.availabilityEndAt)
    && nullableInstantEqual(left.preferredAt, right.preferredAt);
};

const tenantAvailabilityValues = (facts: TenantAvailabilityFactSnapshot) => ({
  availabilityStartAt: facts.startAt,
  availabilityEndAt: facts.endAt,
  availabilityZoneId: facts.zoneId,
  preferredAt: facts.preferredAt
});

export const tenantAvailabilityAttemptMatchesView = (
  command: TenantAvailabilityCommand,
  view: TenantAvailabilityView
): boolean => tenantAvailabilityFactsEqual(command, view);

export type TenantAvailabilityDraftStatus =
  | 'editing'
  | 'saving'
  | 'uncertain'
  | 'saved'
  | 'error';

export type TenantAvailabilityReconciliation =
  | 'idle'
  | 'reading'
  | 'matched'
  | 'mismatch'
  | 'unresolved'
  | 'failed';

/**
 * One in-memory tenant availability draft. It lives above the request-card lifecycle so a refresh,
 * a lost acknowledgement or a Request moving to a later history page cannot destroy the tenant's
 * editing work. It is deliberately never written to browser storage.
 */
export interface TenantAvailabilityDraft {
  identityKey: string;
  requestId: number;
  /** Editor generation; late responses from an older generation may never mutate this draft. */
  generation: number;
  editorOpen: boolean;
  openingRequest: TenantVisitRequest;
  opening: TenantAvailabilityOpening;
  openingVersion: number;
  form: TenantAvailabilityFormValue;
  provenance: Record<TenantAvailabilityFieldName, TenantAvailabilityProvenance>;
  attempt: TenantAvailabilityCommand | null;
  dispatchRevision: number | null;
  dispatchFieldRevisions: Record<TenantAvailabilityFieldName, number> | null;
  /** Revision of the last effective zone choice, captured independently at dispatch. */
  zoneRevision: number;
  dispatchZoneRevision: number | null;
  editRevision: number;
  fieldRevisions: Record<TenantAvailabilityFieldName, number>;
  status: TenantAvailabilityDraftStatus;
  error: string;
  notice: string;
  reconciliation: TenantAvailabilityReconciliation;
  /** Newest authoritative snapshot observed, whether or not the tenant has reviewed it. */
  currentFacts: TenantAvailabilityFactSnapshot | null;
  /** The exact authoritative version the tenant has accepted for this current edit. */
  reviewedFacts: TenantAvailabilityFactSnapshot | null;
  /** Latest observed version. Older delayed reads must never move this backwards. */
  lastAcceptedVersion: number;
  /** Latched after a newer snapshot arrives while intent is dirty or uncertain. */
  reviewRequired: boolean;
  reconcileSequence: number;
}

export const tenantAvailabilityFactsFromRequest = (
  request: TenantVisitRequest,
  version: number = request.version
): TenantAvailabilityFactSnapshot => ({
  requestId: request.requestId,
  version,
  startAt: request.availabilityStartAt,
  endAt: request.availabilityEndAt,
  zoneId: request.availabilityZoneId,
  preferredAt: request.preferredAt
});

export const tenantAvailabilityFactsFromView = (
  view: TenantAvailabilityView
): TenantAvailabilityFactSnapshot => ({
  requestId: view.requestId,
  version: view.version,
  startAt: view.availabilityStartAt,
  endAt: view.availabilityEndAt,
  zoneId: view.availabilityZoneId,
  preferredAt: view.preferredAt
});

export const createTenantAvailabilityDraft = (input: {
  request: TenantVisitRequest;
  identityKey: string;
  generation: number;
  editorOpen?: boolean;
}): TenantAvailabilityDraft => {
  const request = input.request;
  const form = tenantAvailabilityFormFromRequest(request);
  const provenance = tenantAvailabilityProvenanceFromRequest(request);
  const zero = { start: 0, end: 0, preferred: 0 };
  return {
    identityKey: input.identityKey,
    requestId: request.requestId,
    generation: input.generation,
    editorOpen: input.editorOpen ?? true,
    openingRequest: request,
    opening: {
      zoneId: request.availabilityZoneId,
      startAt: request.availabilityStartAt,
      endAt: request.availabilityEndAt,
      preferredAt: request.preferredAt
    },
    openingVersion: request.version,
    form,
    provenance,
    attempt: null,
    dispatchRevision: null,
    dispatchFieldRevisions: null,
    zoneRevision: 0,
    dispatchZoneRevision: null,
    editRevision: 0,
    fieldRevisions: zero,
    status: 'editing',
    error: '',
    notice: '',
    reconciliation: 'idle',
    currentFacts: tenantAvailabilityFactsFromRequest(request),
    reviewedFacts: tenantAvailabilityFactsFromRequest(request),
    lastAcceptedVersion: request.version,
    reviewRequired: false,
    reconcileSequence: 0
  };
};

export const editTenantAvailabilityDraft = (
  draft: TenantAvailabilityDraft,
  field: TenantAvailabilityFieldName,
  key: keyof TenantAvailabilityFields,
  value: string
): TenantAvailabilityDraft => {
  const revision = draft.editRevision + 1;
  const edited = editTenantAvailabilityField(draft.form, field, key, value, draft.provenance);
  return {
    ...draft,
    form: edited.form,
    provenance: edited.provenance,
    editRevision: revision,
    fieldRevisions: { ...draft.fieldRevisions, [field]: revision },
    status: draft.status === 'saving' ? 'saving' : draft.status === 'uncertain' ? 'uncertain' : 'editing',
    error: '',
    notice: draft.reviewRequired || draft.status === 'uncertain' ? draft.notice : '',
    // A review requirement is latched. Editing more fields or changing the zone cannot silently
    // turn a newer authoritative snapshot into the write baseline.
    reviewRequired: draft.reviewRequired || draft.status === 'uncertain'
  };
};

export const changeTenantAvailabilityDraftZone = (
  draft: TenantAvailabilityDraft,
  zoneId: string
): TenantAvailabilityDraft => {
  const form = changeTenantAvailabilityZone(draft.form, zoneId, draft.opening, draft.provenance);
  if (!isOperationsZone(zoneId) || zoneId === draft.form.zoneId) {
    return {
      ...draft,
      form,
      error: '',
      notice: draft.reviewRequired ? draft.notice : ''
    };
  }
  const revision = draft.editRevision + 1;
  return {
    ...draft,
    form,
    zoneRevision: revision,
    editRevision: revision,
    status: draft.status === 'saving' ? 'saving' : draft.status === 'uncertain' ? 'uncertain' : 'editing',
    error: '',
    notice: draft.reviewRequired || draft.status === 'uncertain' ? draft.notice : '',
    reviewRequired: draft.reviewRequired || draft.status === 'uncertain'
  };
};

const savedFields = (facts: TenantAvailabilityFactSnapshot) => {
  const zoneId = facts.zoneId;
  if (!isOperationsZone(zoneId)) return { ...EMPTY_FIELDS };
  return fieldsFromInstants(zoneId as string, {
    startAt: facts.startAt,
    endAt: facts.endAt,
    preferredAt: facts.preferredAt
  });
};

/**
 * Adopts one authoritative read result. Fields edited after the command was dispatched keep their
 * newer text and provenance; every other field is rebased on the saved result. The draft and the
 * current saved facts remain separately readable.
 */
export const adoptAuthoritativeTenantAvailability = (
  draft: TenantAvailabilityDraft,
  facts: TenantAvailabilityFactSnapshot
): TenantAvailabilityDraft => {
  if (facts.version < (draft.currentFacts?.version ?? -1)) return draft;
  const zoneEditedAfterDispatch = draft.dispatchZoneRevision != null
    && draft.zoneRevision > draft.dispatchZoneRevision;
  // A genuine newer zone choice survives adoption, but it only relabels authoritative instants:
  // it never turns an obsolete pre-read wall clock into current truth. Each temporal field is
  // therefore adopted independently, and only a real post-dispatch field edit keeps its own text.
  const nextZone = zoneEditedAfterDispatch
    ? draft.form.zoneId
    : (validZoneOrDefault(facts.zoneId) || draft.form.zoneId);
  const civil = savedFields({ ...facts, zoneId: nextZone });
  const nextForm = { ...draft.form, zoneId: nextZone };
  const nextProvenance = { ...draft.provenance };
  (['start', 'end', 'preferred'] as const).forEach(field => {
    const editedAfterDispatch = draft.dispatchRevision !== null
      && draft.fieldRevisions[field] > (draft.dispatchFieldRevisions?.[field] ?? -1);
    if (editedAfterDispatch) return;
    const saved = field === 'start' ? facts.startAt : field === 'end' ? facts.endAt : facts.preferredAt;
    if (saved === null) {
      // An authoritative null clears any displayed civil value and its stale provenance, even when
      // the zone changed after dispatch and no genuine edit exists for this field.
      nextForm[fieldDate(field)] = '';
      nextForm[fieldTime(field)] = '';
      nextProvenance[field] = 'EDITED';
      return;
    }
    nextForm[fieldDate(field)] = civil[fieldDate(field)];
    nextForm[fieldTime(field)] = civil[fieldTime(field)];
    nextProvenance[field] = 'UNTOUCHED';
  });
  return {
    ...draft,
    opening: {
      zoneId: facts.zoneId,
      startAt: facts.startAt,
      endAt: facts.endAt,
      preferredAt: facts.preferredAt
    },
    openingVersion: facts.version,
    form: nextForm,
    provenance: nextProvenance,
    currentFacts: facts,
    reviewedFacts: facts,
    lastAcceptedVersion: facts.version
  };
};

/**
 * Applies one history refresh to the retained draft.
 *
 * A pristine draft may follow the newest snapshot. A draft with unsaved intent keeps its fields,
 * provenance and reviewed opening, records the newer facts only for presentation, and latches the
 * review gate so the next Save cannot submit stale intent against a silently advanced version.
 */
export const observeAuthoritativeTenantAvailability = (
  draft: TenantAvailabilityDraft,
  request: TenantVisitRequest
): TenantAvailabilityDraft => {
  const observedVersion = draft.currentFacts?.version ?? -1;
  if (request.version <= observedVersion) return draft;
  const facts = tenantAvailabilityFactsFromRequest(request);
  const pristine = draft.editRevision === 0
    && draft.attempt === null
    && !draft.reviewRequired
    && draft.status !== 'saving'
    && draft.status !== 'uncertain'
    && draft.reconciliation !== 'reading';
  if (pristine) return adoptAuthoritativeTenantAvailability(draft, facts);
  return {
    ...draft,
    currentFacts: facts,
    lastAcceptedVersion: Math.max(request.version, draft.lastAcceptedVersion),
    reviewRequired: true,
    notice: 'This visit changed while you were editing. Review the current saved times before saving or clearing anything.'
  };
};

/**
 * The exact Request projection used for a draft that is no longer on the loaded history page.
 *
 * When an authoritative snapshot exists, every availability field comes from that snapshot —
 * including explicit nulls. The opening window is only a fallback for a draft that has never
 * observed an authoritative update.
 */
export const tenantRequestForDraft = (draft: TenantAvailabilityDraft): TenantVisitRequest => {
  const facts = draft.currentFacts;
  return facts === null
    ? { ...draft.openingRequest }
    : {
        ...draft.openingRequest,
        version: facts.version,
        availabilityStartAt: facts.startAt,
        availabilityEndAt: facts.endAt,
        availabilityZoneId: facts.zoneId,
        preferredAt: facts.preferredAt
      };
};

/** The version an explicit Save/Clear is allowed to submit. */
export const tenantAvailabilityReviewedVersion = (draft: TenantAvailabilityDraft): number =>
  draft.reviewedFacts?.version ?? draft.openingVersion;

/**
 * Compares the immutable attempted command with the authoritative read. Equality means only that the
 * current saved times match the request; it is not evidence that the historical write was
 * acknowledged.
 */
export const reconcileTenantAvailabilityDraft = (
  draft: TenantAvailabilityDraft,
  facts: TenantAvailabilityFactSnapshot | null,
  outcome: 'matched' | 'mismatch' | 'unresolved' | 'failed'
): TenantAvailabilityDraft => {
  if (facts && facts.version < (draft.currentFacts?.version ?? -1)) {
    return { ...draft, reconciliation: outcome, status: 'uncertain' };
  }
  const matched = facts !== null
    && draft.attempt !== null
    && tenantAvailabilityFactsEqual(draft.attempt, {
      availabilityStartAt: facts.startAt,
      availabilityEndAt: facts.endAt,
      availabilityZoneId: facts.zoneId,
      preferredAt: facts.preferredAt
    });
  if (outcome === 'matched' && matched) {
    return {
      ...adoptAuthoritativeTenantAvailability(draft, facts as TenantAvailabilityFactSnapshot),
      reconciliation: 'matched',
      status: 'uncertain',
      reviewRequired: false,
      notice: 'Current saved times match your earlier request. The earlier request’s acknowledgement remains unconfirmed.',
      error: ''
    };
  }
  if (outcome === 'mismatch' || (outcome === 'matched' && !matched)) {
    return {
      ...draft,
      currentFacts: facts ?? draft.currentFacts,
      lastAcceptedVersion: facts ? Math.max(facts.version, draft.lastAcceptedVersion) : draft.lastAcceptedVersion,
      reconciliation: 'mismatch',
      status: 'uncertain',
      reviewRequired: true,
      notice: 'The saved times differ from your earlier request. Review the current saved times before saving again.',
      error: ''
    };
  }
  if (outcome === 'unresolved') {
    return {
      ...draft,
      currentFacts: facts ?? draft.currentFacts,
      lastAcceptedVersion: facts ? Math.max(facts.version, draft.lastAcceptedVersion) : draft.lastAcceptedVersion,
      reconciliation: 'unresolved',
      status: 'uncertain',
      reviewRequired: true,
      notice: 'This request was not found in your recent visits. Its earlier outcome remains unresolved.',
      error: ''
    };
  }
  return {
    ...draft,
    currentFacts: facts ?? draft.currentFacts,
    lastAcceptedVersion: facts ? Math.max(facts.version, draft.lastAcceptedVersion) : draft.lastAcceptedVersion,
    reconciliation: 'failed',
    status: 'uncertain',
    reviewRequired: true,
    notice: 'The latest saved times could not be read. Your edits are kept; retry the check before saving again.',
    error: ''
  };
};

/** The deliberate review boundary after a mismatch: replace the draft's baseline with saved facts. */
export const rebaseTenantAvailabilityDraft = (draft: TenantAvailabilityDraft): TenantAvailabilityDraft => {
  if (!draft.currentFacts) return draft;
  const adopted = adoptAuthoritativeTenantAvailability({
    ...draft,
    dispatchRevision: null,
    dispatchZoneRevision: null
  }, draft.currentFacts);
  return {
    ...adopted,
    generation: draft.generation + 1,
    attempt: null,
    dispatchRevision: null,
    dispatchFieldRevisions: null,
    zoneRevision: 0,
    dispatchZoneRevision: null,
    editRevision: 0,
    fieldRevisions: { start: 0, end: 0, preferred: 0 },
    status: 'editing',
    error: '',
    notice: '',
    reconciliation: 'idle',
    reviewRequired: false
  };
};

/**
 * Settles the Save/Clear success callback without letting a delayed acknowledgement move the
 * authoritative baseline backwards. If a newer snapshot was observed while the response was in
 * flight, only this operation's pending metadata is released: the newer facts, reviewed baseline,
 * unsaved form, provenance and review latch all remain exactly as they were.
 */
export const settleTenantAvailabilitySaveAcknowledgement = (
  draft: TenantAvailabilityDraft,
  facts: TenantAvailabilityFactSnapshot
): TenantAvailabilityDraft => {
  const observed = draft.currentFacts;
  const hasNewerObservation = observed !== null
    && (facts.version < observed.version
      || (facts.version === observed.version
        && !tenantAvailabilityFactsEqual(tenantAvailabilityValues(observed), tenantAvailabilityValues(facts))));
  if (hasNewerObservation) {
    return {
      ...draft,
      attempt: null,
      dispatchRevision: null,
      dispatchFieldRevisions: null,
      dispatchZoneRevision: null,
      status: 'uncertain',
      error: '',
      notice: 'Your earlier save was accepted, but newer saved times arrived while it was finishing. '
        + 'Review the current saved times before saving or clearing anything.',
      reconciliation: 'idle',
      reviewRequired: true
    };
  }
  const adopted = adoptAuthoritativeTenantAvailability(draft, facts);
  const editedAfterDispatch = draft.dispatchRevision !== null
    && draft.editRevision > draft.dispatchRevision;
  return {
    ...adopted,
    attempt: null,
    dispatchRevision: null,
    dispatchFieldRevisions: null,
    dispatchZoneRevision: null,
    status: 'saved',
    error: '',
    notice: editedAfterDispatch
      ? 'The earlier request is saved. Your newer edits remain here and have not been saved yet.'
      : 'Available times saved.',
    reconciliation: 'idle',
    reviewRequired: false
  };
};

export const tenantAvailabilityErrorCopy = (error: TenantAvailabilityError): string => {
  switch (error) {
    case 'missing':
      return 'Choose both dates, a start time and an end time.';
    case 'zone':
      return 'Choose a valid visit timezone before saving these times.';
    case 'gap':
      return 'That clock time does not exist on the selected date in this timezone. Choose a different time.';
    case 'ambiguous':
      return 'That clock time occurs twice on the selected date in this timezone. Choose a different time.';
    case 'order':
      return 'The end must be after the start.';
    case 'preferred-outside':
      return 'Your preferred time must fall inside the available window.';
    default:
      return 'Enter valid dates and times and try again.';
  }
};
