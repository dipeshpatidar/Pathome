export type PropertyDetailBackTarget = -1 | '/';

/** Use in-app history only when React Router has a prior entry; otherwise return to discovery. */
export function resolvePropertyDetailBackTarget(historyState: unknown): PropertyDetailBackTarget {
  if (!historyState || typeof historyState !== 'object') return '/';
  const index = (historyState as { idx?: unknown }).idx;
  return typeof index === 'number' && Number.isSafeInteger(index) && index > 0 ? -1 : '/';
}
