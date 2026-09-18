const normalizeBaseUrl = (configuredValue: string | undefined, fallback: string): string => {
  const value = configuredValue?.trim();
  if (!value) return fallback;
  return value.endsWith('/') ? value.slice(0, -1) : value;
};

/**
 * Relative defaults keep browser requests on the same host that served the app.
 * Vite proxies these paths during development, including when opened over the LAN.
 */
export const API_ROOT_URL = normalizeBaseUrl(import.meta.env.VITE_API_BASE_URL, '/api/v1');

const OAUTH_ROOT_URL = normalizeBaseUrl(import.meta.env.VITE_OAUTH_BASE_URL, '');
export const GOOGLE_OAUTH_URL = `${OAUTH_ROOT_URL}/oauth2/authorization/google`;
