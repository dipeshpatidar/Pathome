import { API_ROOT_URL } from '../config/endpoints';
import { createApiRequestError } from './apiError';

export interface LessorCapabilityResponse {
  userId: number;
  enabled: boolean;
  hasLessorProfile: boolean;
  activatedAt: string | null;
}

export const lessorCapabilityService = {
  async get(): Promise<LessorCapabilityResponse> {
    const token = localStorage.getItem('pathome_auth_token');
    if (!token) {
      return { userId: 0, enabled: false, hasLessorProfile: false, activatedAt: null };
    }
    const response = await fetch(`${API_ROOT_URL}/lessor/capability`, {
      method: 'GET',
      headers: {
        Authorization: `Bearer ${token}`,
        Accept: 'application/json'
      }
    });
    if (!response.ok) {
      throw await createApiRequestError(response, 'Unable to check property capabilities.', false, token);
    }
    return response.json();
  }
};
