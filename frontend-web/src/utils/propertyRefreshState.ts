export type RefreshMediaIdentity = { url: string; type: 'IMAGE' | 'VIDEO' };
export type PropertyRefreshFailure = 'unavailable' | 'transient' | 'denied';

export const reconcileActiveMediaIndex = (
  previous: ReadonlyArray<RefreshMediaIdentity>,
  next: ReadonlyArray<RefreshMediaIdentity>,
  previousIndex: number
): number => {
  if (next.length === 0) return 0;
  const selected = previous[previousIndex];
  if (selected) {
    const stableIndex = next.findIndex(item => item.url === selected.url && item.type === selected.type);
    if (stableIndex >= 0) return stableIndex;
  }
  return Math.max(0, Math.min(previousIndex, next.length - 1));
};

export const classifyPropertyRefreshFailure = (status: unknown): PropertyRefreshFailure => {
  const numeric = Number(status);
  if (numeric === 404 || numeric === 410) return 'unavailable';
  if (numeric === 401 || numeric === 403) return 'denied';
  return 'transient';
};

type VersionedProperty = { id: number; listingVersion?: number | null };

const isPropertyIdentity = (property: VersionedProperty): boolean =>
  Number.isSafeInteger(property.id) && property.id > 0;

export interface PropertyRefreshCoordinator<T extends VersionedProperty> {
  begin(listingId: number): number;
  accept(listingId: number, sequence: number, property: T): boolean;
  unavailable(listingId: number, sequence: number): boolean;
  isUnavailable(listingId: number): boolean;
  latest(listingId: number): T | undefined;
  trackedIds(): number[];
}

export interface PropertyRefreshKnowledge<T extends VersionedProperty> {
  latest(listingId: number): T | undefined;
  isUnavailable(listingId: number): boolean;
}

/**
 * Shared ordering for every refresh source. Request order fences unavailable facts; a comparable
 * listingVersion rejects older successful facts even when an older request finishes last.
 */
export const createPropertyRefreshCoordinator = <T extends VersionedProperty>():
PropertyRefreshCoordinator<T> => {
  const latestSequence = new Map<number, number>();
  const knownVersion = new Map<number, number>();
  const unavailableAt = new Map<number, number>();
  const acceptedProperties = new Map<number, T>();
  let nextSequence = 0;

  return {
    begin(listingId) {
      const sequence = ++nextSequence;
      latestSequence.set(listingId, sequence);
      return sequence;
    },
    accept(listingId, sequence, property) {
      if (!isPropertyIdentity(property) || property.id !== listingId
          || !Number.isSafeInteger(sequence)) return false;
      const removedAt = unavailableAt.get(listingId);
      if (removedAt !== undefined && sequence < removedAt) return false;
      const version = property.listingVersion;
      if (typeof version === 'number' && Number.isSafeInteger(version) && version >= 0) {
        const known = knownVersion.get(listingId);
        if (known !== undefined && version < known) return false;
        if (known === version && sequence < (latestSequence.get(listingId) ?? sequence)) return false;
        knownVersion.set(listingId, version);
        if (removedAt !== undefined) unavailableAt.delete(listingId);
      } else if (removedAt !== undefined || knownVersion.has(listingId)
          || sequence !== latestSequence.get(listingId)) {
        // Without a comparable version, only the current request can establish new facts.
        return false;
      }
      acceptedProperties.set(listingId, property);
      return true;
    },
    unavailable(listingId, sequence) {
      if (latestSequence.get(listingId) !== sequence) return false;
      unavailableAt.set(listingId, sequence);
      return true;
    },
    isUnavailable(listingId) {
      return unavailableAt.has(listingId);
    },
    latest(listingId) {
      return acceptedProperties.get(listingId);
    },
    trackedIds() {
      return [...new Set([
        ...latestSequence.keys(),
        ...acceptedProperties.keys(),
        ...unavailableAt.keys()
      ])];
    }
  };
};

const comparableListingVersion = (property: VersionedProperty): number | null => {
  const version = property.listingVersion;
  return typeof version === 'number' && Number.isSafeInteger(version) && version >= 0
    ? version : null;
};

/**
 * Reconciles a response with the exact state currently displayed. A comparable JPA Listing version
 * is authoritative; if the response does not carry one, retaining the displayed fact is the
 * truthful fallback because the response cannot prove that it is newer.
 */
export const preferNewestPropertySnapshot = <T extends VersionedProperty>(
  displayed: Readonly<T>,
  incoming: Readonly<T>
): T => {
  if (!isPropertyIdentity(displayed) || !isPropertyIdentity(incoming)
      || displayed.id !== incoming.id) return displayed as T;
  const displayedVersion = comparableListingVersion(displayed);
  const incomingVersion = comparableListingVersion(incoming);
  if (incomingVersion === null) return displayedVersion === null ? incoming as T : displayed as T;
  if (displayedVersion === null || incomingVersion >= displayedVersion) return incoming as T;
  return displayed as T;
};

const reconcileIncomingSnapshots = <T extends VersionedProperty>(
  displayed: ReadonlyArray<T>,
  incoming: ReadonlyArray<T>,
  unavailable: ReadonlySet<number>,
  append: boolean
): T[] => {
  const next = append
    ? displayed.filter(isPropertyIdentity).map(item => item as T)
    : [];
  const displayedById = new Map(displayed
    .filter(isPropertyIdentity)
    .map(item => [item.id, item as T]));
  const positions = new Map(next.map((item, index) => [item.id, index]));
  incoming.forEach(item => {
    if (!isPropertyIdentity(item) || unavailable.has(item.id)) return;
    const position = positions.get(item.id);
    if (position === undefined) {
      positions.set(item.id, next.length);
      const current = displayedById.get(item.id);
      next.push(current ? preferNewestPropertySnapshot<T>(current, item) : item);
    } else {
      next[position] = preferNewestPropertySnapshot<T>(next[position], item);
    }
  });
  return next;
};

/** Replaces discovery membership while preserving any newer facts already displayed per Listing. */
export const reconcileDiscoveryProperties = <T extends VersionedProperty>(
  displayed: ReadonlyArray<T>,
  incoming: ReadonlyArray<T>,
  unavailable: ReadonlySet<number> = new Set()
): T[] => reconcileIncomingSnapshots(displayed, incoming, unavailable, false);

/** Appends a later page once, reconciling duplicate identities with the newest known facts. */
export const appendDiscoveryProperties = <T extends VersionedProperty>(
  displayed: ReadonlyArray<T>,
  incoming: ReadonlyArray<T>,
  unavailable: ReadonlySet<number> = new Set()
): T[] => reconcileIncomingSnapshots(displayed, incoming, unavailable, true);

export const reconcileRevalidatedProperties = <T extends VersionedProperty>(
  current: ReadonlyArray<T>,
  updates: ReadonlyMap<number, T>,
  unavailable: ReadonlySet<number>
): T[] => current
  .filter(item => isPropertyIdentity(item) && !unavailable.has(item.id))
  .map((item): T => {
    const update = updates.get(item.id);
    if (!update) return item;
    return preferNewestPropertySnapshot<T>(item, update);
  });

export interface PropertyRefreshRequestIdentity {
  generation: number;
  requestId: number;
  sessionGeneration: number;
}

export const propertyRefreshRequestIsCurrent = (
  request: PropertyRefreshRequestIdentity,
  current: PropertyRefreshRequestIdentity
): boolean => request.generation === current.generation
  && request.requestId === current.requestId
  && request.sessionGeneration === current.sessionGeneration;

export type PropertyRefreshCommitMode = 'replace' | 'append' | 'revalidate';

/**
 * Pure, generation-scoped state commit. The caller supplies the state React is committing and the
 * request that produced the response, so a batched or intervening write is always reconsidered
 * against the facts actually displayed instead of a snapshot captured before the state updater ran.
 */
export const decidePropertyRefreshCommit = <T extends VersionedProperty>(input: {
  current: ReadonlyArray<T>;
  incoming: ReadonlyArray<T>;
  mode: PropertyRefreshCommitMode;
  request: PropertyRefreshRequestIdentity;
  active: PropertyRefreshRequestIdentity;
  owned?: boolean;
  unavailable?: ReadonlySet<number>;
  matches?: (property: T) => boolean;
  allowInsert?: boolean;
  insertIds?: ReadonlySet<number>;
  knowledge?: PropertyRefreshKnowledge<T>;
}): T[] => {
  if (input.owned === false || !propertyRefreshRequestIsCurrent(input.request, input.active)) {
    return input.current as T[];
  }
  const unavailable = input.unavailable ?? new Set<number>();
  const isUnavailable = (listingId: number): boolean =>
    input.knowledge
      ? input.knowledge.isUnavailable(listingId)
      : unavailable.has(listingId);
  const matches = (property: T): boolean => !input.matches || input.matches(property);
  const acceptedFact = (property: T): T => input.knowledge?.latest(property.id) ?? property;
  if (input.mode === 'replace') {
    const displayedById = new Map(input.current
      .filter(isPropertyIdentity)
      .map(item => [item.id, item as T]));
    const next: T[] = [];
    const positions = new Map<number, number>();
    input.incoming.forEach(item => {
      if (!isPropertyIdentity(item) || isUnavailable(item.id)) return;
      const known = acceptedFact(item);
      const displayed = displayedById.get(item.id);
      const candidate = displayed
        ? preferNewestPropertySnapshot<T>(displayed, known)
        : known;
      if (!matches(candidate)) return;
      const position = positions.get(item.id);
      if (position === undefined) {
        positions.set(item.id, next.length);
        next.push(candidate);
      } else {
        next[position] = preferNewestPropertySnapshot<T>(next[position], candidate);
      }
    });
    return next;
  }
  if (input.mode === 'append') {
    const next: T[] = [];
    const positions = new Map<number, number>();
    input.current.forEach(item => {
      if (!isPropertyIdentity(item) || isUnavailable(item.id)) return;
      const position = positions.get(item.id);
      if (position === undefined) {
        positions.set(item.id, next.length);
        next.push(item as T);
      } else {
        next[position] = preferNewestPropertySnapshot<T>(next[position], item);
      }
    });
    input.incoming.forEach(item => {
      if (!isPropertyIdentity(item) || isUnavailable(item.id)) return;
      const candidate = acceptedFact(item);
      const position = positions.get(item.id);
      if (position === undefined) {
        if (matches(candidate)) {
          positions.set(item.id, next.length);
          next.push(candidate);
        }
        return;
      }
      const committed = preferNewestPropertySnapshot<T>(next[position], candidate);
      if (matches(committed)) next[position] = committed;
      else positions.delete(item.id);
    });
    return next.filter(item => positions.has(item.id));
  }
  const updates = new Map(input.incoming
    .filter(item => isPropertyIdentity(item) && !isUnavailable(item.id))
    .map(item => [item.id, acceptedFact(item)]));
  const next = input.current.flatMap(item => {
    if (!isPropertyIdentity(item) || isUnavailable(item.id)) return [];
    const incoming = updates.get(item.id);
    if (!incoming) return [item as T];
    const committed = preferNewestPropertySnapshot<T>(item, incoming);
    if (committed === item || matches(committed)) return [committed];
    return [];
  });
  if (!input.allowInsert) return next;
  const present = new Set(next.map(item => item.id));
  input.incoming.forEach(item => {
    if (!isPropertyIdentity(item) || present.has(item.id) || isUnavailable(item.id)) return;
    if (input.insertIds && !input.insertIds.has(item.id)) return;
    const candidate = input.knowledge ? input.knowledge.latest(item.id) : item;
    if (!candidate) return;
    if (matches(candidate)) {
      present.add(item.id);
      next.push(candidate);
    }
  });
  return next;
};

/** Runs bounded workers in input order without issuing a full-card request burst. */
export const settleWithConcurrency = async <T, R>(
  values: ReadonlyArray<T>,
  concurrency: number,
  worker: (value: T, index: number) => Promise<R>
): Promise<PromiseSettledResult<R>[]> => {
  if (!Number.isSafeInteger(concurrency) || concurrency < 1) {
    throw new RangeError('Refresh concurrency must be a positive integer');
  }
  const outcomes: PromiseSettledResult<R>[] = new Array(values.length);
  let nextIndex = 0;
  const run = async () => {
    while (nextIndex < values.length) {
      const index = nextIndex++;
      try {
        outcomes[index] = { status: 'fulfilled', value: await worker(values[index], index) };
      } catch (reason) {
        outcomes[index] = { status: 'rejected', reason };
      }
    }
  };
  await Promise.all(Array.from(
    { length: Math.min(concurrency, values.length) },
    () => run()
  ));
  return outcomes;
};

export interface DiscoveryRefreshFilters {
  city?: string;
  sector?: string;
  q?: string;
  bhk?: string;
  propertyType?: string;
  furnishing?: string;
  minRent?: number;
  maxRent?: number;
  rentalOnly?: boolean;
}

export const propertyMatchesDiscoveryFilters = (
  property: {
    title: string;
    sector: string;
    city?: string;
    description?: string;
    bhk?: string;
    propertyType?: string;
    furnishingStatus?: string;
    monthlyRent?: number;
    listingType?: string;
  },
  filters: DiscoveryRefreshFilters
): boolean => {
  if (filters.rentalOnly && property.listingType !== 'RENT') return false;
  if (filters.city && property.city?.toLowerCase() !== filters.city.toLowerCase()) return false;
  if (filters.sector && !property.sector.toLowerCase().includes(filters.sector.toLowerCase())) return false;
  const query = filters.q?.trim().toLowerCase();
  if (query && !`${property.title} ${property.sector} ${property.description ?? ''}`
    .toLowerCase().includes(query)) return false;
  if (filters.bhk && property.bhk !== filters.bhk) return false;
  if (filters.propertyType
    && property.propertyType?.toLowerCase() !== filters.propertyType.toLowerCase()) return false;
  const requestedFurnishing = filters.furnishing?.replace(/[_-]/g, ' ').toUpperCase();
  const actualFurnishing = property.furnishingStatus?.replace(/[_-]/g, ' ').toUpperCase();
  if (requestedFurnishing && (requestedFurnishing === 'FURNISHED'
    ? actualFurnishing !== 'FULLY FURNISHED' && actualFurnishing !== 'SEMI FURNISHED'
    : actualFurnishing !== requestedFurnishing)) return false;
  const rent = Number(property.monthlyRent ?? 0);
  return (!filters.minRent || rent >= filters.minRent)
    && (!filters.maxRent || rent <= filters.maxRent);
};
