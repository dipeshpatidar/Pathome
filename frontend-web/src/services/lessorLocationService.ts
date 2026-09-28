import { API_ROOT_URL } from '../config/endpoints';
import { ApiRequestError, createApiRequestError, notifySessionExpired } from './apiError';

export interface LessorLocalityOption { id: number; city: string; name: string; match: 'canonical' | 'alias' | 'different_city' }
const BASE = `${API_ROOT_URL}/lessor/locations`;

async function get<T>(path: string, guest = false): Promise<T> {
  const token = guest ? null : localStorage.getItem('pathome_auth_token');
  if (!token && !guest) { notifySessionExpired(); throw new ApiRequestError('Sign in to continue.', 401); }
  const base = guest ? `${API_ROOT_URL}/lessor/guest/locations` : BASE;
  const response = await fetch(`${base}${path}`, {
    credentials: guest ? 'include' : 'same-origin',
    headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), Accept: 'application/json' }
  });
  if (!response.ok) throw await createApiRequestError(response, 'Unable to load locations.', guest);
  return response.json() as Promise<T>;
}

export const lessorLocationService = {
  cities: (guest = false) => get<string[]>('/cities', guest),
  suggestions: (city: string, query: string, guest = false) =>
    get<LessorLocalityOption[]>(`/suggestions?city=${encodeURIComponent(city)}&q=${encodeURIComponent(query)}`, guest)
};
