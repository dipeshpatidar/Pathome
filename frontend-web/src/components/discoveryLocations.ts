export interface DiscoveryCity {
  id: string;
  name: string;
  hasHomes: boolean;
  localities: string[];
}

// This is the existing public discovery catalogue. A location API can replace
// the catalogue later without changing the search interaction.
export const DISCOVERY_CITIES: DiscoveryCity[] = [
  {
    id: 'INDORE',
    name: 'Indore',
    hasHomes: true,
    localities: ['Vijay Nagar', 'Nanda Nagar', 'Bhawarkua', 'Nipania', 'AB Road', 'Super Corridor', 'LIG Circle', 'Old Palasia', 'Rau', 'Mahalaxmi Nagar', 'Scheme 78']
  },
  {
    id: 'BHOPAL',
    name: 'Bhopal',
    hasHomes: false,
    localities: ['MP Nagar', 'Arera Colony', 'Kolar Road', 'Hoshangabad Road']
  },
  {
    id: 'PUNE',
    name: 'Pune',
    hasHomes: false,
    localities: ['Baner', 'Wakad', 'Hinjewadi', 'Kharadi', 'Viman Nagar']
  }
];

export const findDiscoveryCity = (name?: string): DiscoveryCity => {
  const value = name?.trim();
  const known = DISCOVERY_CITIES.find((city) => city.name.toLowerCase() === value?.toLowerCase());
  return known || (value ? { id: value.toUpperCase(), name: value, hasHomes: true, localities: [] } : DISCOVERY_CITIES[0]);
};

export const matchesLocationQuery = (name: string, query: string): boolean =>
  name.toLocaleLowerCase().includes(query.trim().toLocaleLowerCase());
