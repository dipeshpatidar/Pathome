import { API_ROOT_URL } from '../config/endpoints';
import { ApiRequestError, createApiRequestError, notifySessionExpired } from './apiError';

export interface LessorLocalityOption { id: number; city: string; name: string; match: 'canonical' | 'alias' | 'different_city' }
const BASE = `${API_ROOT_URL}/lessor/locations`;

async function get<T>(path: string): Promise<T> {
  const token = localStorage.getItem('pathome_auth_token');
  if (!token) { notifySessionExpired(); throw new ApiRequestError('Sign in to continue.', 401); }
  const response = await fetch(`${BASE}${path}`, { headers: { Authorization: `Bearer ${token}`, Accept: 'application/json' } });
  if (!response.ok) throw await createApiRequestError(response, 'Unable to load locations.');
  return response.json() as Promise<T>;
}

export const lessorLocationService = {
  cities: () => get<string[]>('/cities'),
  suggestions: (city: string, query: string) =>
    get<LessorLocalityOption[]>(`/suggestions?city=${encodeURIComponent(city)}&q=${encodeURIComponent(query)}`)
};
