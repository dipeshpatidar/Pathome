import { API_ROOT_URL } from '../config/endpoints';
import { ApiRequestError, createApiRequestError } from './apiError';
import { Property } from '../types';
import { mapDiscoveryProperty } from './propertyService';
import { fetchFavoriteIdsInBatches, uniquePositiveFavoriteIds } from '../utils/favoriteLookup';

const MAX_BATCH_SIZE = 100;

const authenticatedRequest = (token: string): HeadersInit => ({
  Accept: 'application/json',
  Authorization: `Bearer ${token}`
});

const getToken = (): string => {
  const token = localStorage.getItem('pathome_auth_token');
  if (!token) throw new ApiRequestError('Please sign in to save properties.', 401);
  return token;
};

export const favoriteService = {
  async listSavedProperties(page: number, signal?: AbortSignal): Promise<{ properties: Property[]; page: number; hasMore: boolean }> {
    if (!Number.isSafeInteger(page) || page < 0) throw new ApiRequestError('Unable to load saved homes. Please try again.');
    const token = getToken();
    const query = new URLSearchParams({ page: String(page), size: '6' });
    const response = await fetch(`${API_ROOT_URL}/favorites/properties?${query.toString()}`, {
      headers: authenticatedRequest(token), signal
    });
    if (!response.ok) throw await createApiRequestError(response, 'Unable to load saved homes. Please try again.', false, token);
    const payload = await response.json() as { properties?: unknown; page?: unknown; hasMore?: unknown };
    if (!Array.isArray(payload?.properties)
      || payload.properties.some((item: any) => !Number.isSafeInteger(item?.id) || item.id <= 0)
      || payload.page !== page || typeof payload.hasMore !== 'boolean') {
      throw new ApiRequestError('Unable to load saved homes. Please try again.');
    }
    return {
      properties: payload.properties.map(mapDiscoveryProperty),
      page,
      hasMore: payload.hasMore
    };
  },

  async listForProperties(propertyIds: number[], signal?: AbortSignal): Promise<number[]> {
    if (propertyIds.some(id => !Number.isSafeInteger(id) || id <= 0)) {
      throw new ApiRequestError('Unable to load saved properties. Please try again.');
    }
    const uniqueIds = uniquePositiveFavoriteIds(propertyIds);
    if (uniqueIds.length === 0) return [];
    const token = getToken();
    return fetchFavoriteIdsInBatches(uniqueIds, async batch => {
      const query = new URLSearchParams();
      batch.forEach(id => query.append('propertyIds', String(id)));
      const response = await fetch(`${API_ROOT_URL}/favorites?${query.toString()}`, {
        headers: authenticatedRequest(token), signal
      });
      if (!response.ok) throw await createApiRequestError(response, 'Unable to load saved properties. Please try again.', false, token);
      const payload = await response.json() as { propertyIds?: unknown };
      if (!Array.isArray(payload?.propertyIds)
        || payload.propertyIds.some(id => !Number.isSafeInteger(id) || !batch.includes(id as number))) {
        throw new ApiRequestError('Unable to load saved properties. Please try again.');
      }
      return payload.propertyIds as number[];
    }, MAX_BATCH_SIZE);
  },

  async save(propertyId: number): Promise<void> {
    const token = getToken();
    const response = await fetch(`${API_ROOT_URL}/favorites/${propertyId}`, {
      method: 'PUT', headers: authenticatedRequest(token)
    });
    if (!response.ok) throw await createApiRequestError(response, 'Unable to save this property. Please try again.', false, token);
  },

  async remove(propertyId: number): Promise<void> {
    const token = getToken();
    const response = await fetch(`${API_ROOT_URL}/favorites/${propertyId}`, {
      method: 'DELETE', headers: authenticatedRequest(token)
    });
    if (!response.ok) throw await createApiRequestError(response, 'Unable to remove this saved property. Please try again.', false, token);
  }
};
