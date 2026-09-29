import { API_ROOT_URL } from '../config/endpoints';
import { ApiRequestError, createApiRequestError, notifySessionExpired } from './apiError';

export interface LandlordContact {
  fullName: string | null;
  phoneNumber: string | null;
  complete: boolean;
}

const BASE = `${API_ROOT_URL}/lessor/contact`;

function authHeaders(): Record<string, string> {
  const token = localStorage.getItem('pathome_auth_token');
  if (!token) {
    notifySessionExpired();
    throw new ApiRequestError('Sign in to continue your property draft.', 401);
  }
  return { Authorization: `Bearer ${token}`, Accept: 'application/json' };
}

export const lessorContactService = {
  async getContact(): Promise<LandlordContact> {
    const token = localStorage.getItem('pathome_auth_token');
    const response = await fetch(BASE, {
      method: 'GET',
      headers: authHeaders()
    });
    if (!response.ok) {
      throw await createApiRequestError(response, 'Unable to check your contact details.', false, token);
    }
    return response.json();
  },

  async updateContact(data: { fullName: string; phoneNumber: string }): Promise<LandlordContact> {
    const token = localStorage.getItem('pathome_auth_token');
    const response = await fetch(BASE, {
      method: 'PUT',
      headers: {
        ...authHeaders(),
        'Content-Type': 'application/json'
      },
      body: JSON.stringify(data)
    });
    if (!response.ok) {
      throw await createApiRequestError(response, 'Unable to save your contact details.', false, token);
    }
    return response.json();
  }
};
