import type { Property } from '../types';
import { PROPERTY_TYPE_LABELS, type RentalPropertyType } from './rentalSearch.ts';

/** Discovery's structured `sector` is the public locality projection. */
export const landingLocality = (property: Property): string | null => {
  const sector = typeof property.sector === 'string' ? property.sector.trim() : '';
  if (!sector) return null;
  // Some older listings contain a listing headline in the locality column.
  // Exclude those records; never try to derive a locality from their title.
  if (/\b\d+\s*(?:BHK|RK)\b/i.test(sector) || sector.toLocaleLowerCase() === property.title?.trim().toLocaleLowerCase()) return null;
  return sector;
};

export const landingLocalities = (properties: Property[], city: string): string[] => {
  const names = new Map<string, string>();
  for (const property of properties) {
    if (property.city?.trim().toLocaleLowerCase() !== city.trim().toLocaleLowerCase()) continue;
    const name = landingLocality(property);
    if (name) names.set(name.toLocaleLowerCase(), names.get(name.toLocaleLowerCase()) ?? name);
  }
  return [...names.values()].sort((a, b) => a.localeCompare(b));
};

export const landingPropertyTitle = (property: Property): string => {
  const type = PROPERTY_TYPE_LABELS[property.propertyType as RentalPropertyType];
  const bhk = property.bhk?.trim().replace(/^(\d+)\s*(BHK|RK)$/i, '$1 $2');
  const structured = [bhk, type].filter(Boolean).join(' ');
  return structured || property.title?.trim() || 'Rental home';
};
