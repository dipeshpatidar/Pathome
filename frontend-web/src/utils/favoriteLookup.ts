export const uniquePositiveFavoriteIds = (ids: number[]): number[] =>
  [...new Set(ids.filter(id => Number.isSafeInteger(id) && id > 0))];

export const unresolvedFavoriteIds = (ids: number[], resolvedIds: ReadonlySet<number>): number[] =>
  uniquePositiveFavoriteIds(ids).filter(id => !resolvedIds.has(id));

export const fetchFavoriteIdsInBatches = async (
  ids: number[],
  fetchBatch: (batch: number[]) => Promise<number[]>,
  batchSize = 100
): Promise<number[]> => {
  if (!Number.isSafeInteger(batchSize) || batchSize < 1 || batchSize > 100) {
    throw new RangeError('Favorite lookup batch size must be between 1 and 100');
  }
  const uniqueIds = uniquePositiveFavoriteIds(ids);
  const favorites = new Set<number>();
  for (let offset = 0; offset < uniqueIds.length; offset += batchSize) {
    const batch = uniqueIds.slice(offset, offset + batchSize);
    const result = await fetchBatch(batch);
    for (const id of result) {
      if (batch.includes(id)) favorites.add(id);
    }
  }
  return [...favorites];
};

export const mergeFavoriteLookupState = (
  resolvedIds: ReadonlySet<number>,
  favoriteIds: ReadonlySet<number>,
  requestedIds: number[],
  returnedFavoriteIds: number[]
): { resolvedIds: Set<number>; favoriteIds: Set<number> } => {
  const requested = uniquePositiveFavoriteIds(requestedIds);
  const nextResolved = new Set(resolvedIds);
  const nextFavorites = new Set(favoriteIds);
  for (const id of requested) {
    nextResolved.add(id);
    nextFavorites.delete(id);
  }
  for (const id of returnedFavoriteIds) {
    if (requested.includes(id)) nextFavorites.add(id);
  }
  return { resolvedIds: nextResolved, favoriteIds: nextFavorites };
};
