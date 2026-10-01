import type { Property } from '../types';

export const savedHomesVisibleLimitForWidth = (viewportWidth: number): number =>
  viewportWidth >= 768 ? 2 : 1;

export const savedHomesForPresentation = (
  properties: Property[], visibleLimit: number, expanded: boolean
): Property[] => expanded ? properties : properties.slice(0, Math.max(0, visibleLimit));

export const mergeSavedHomes = (current: Property[], incoming: Property[]): Property[] => {
  const seen = new Set<number>();
  return [...current, ...incoming].filter(property => {
    if (seen.has(property.id)) return false;
    seen.add(property.id);
    return true;
  });
};

/** Apply only after the server confirms the persisted favorite mutation. */
export const applyPersistedSavedHomeChange = (
  current: Property[], property: Property, saved: boolean
): Property[] => saved
  ? mergeSavedHomes([property], current)
  : current.filter(item => item.id !== property.id);

export const removeSavedHomesFromDiscovery = (
  properties: Property[], savedIds: ReadonlySet<number>
): Property[] => properties.filter(property => !savedIds.has(property.id));
