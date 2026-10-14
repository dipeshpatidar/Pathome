/**
 * Test-only replacement for `src/config/endpoints.ts`.
 *
 * The real module reads `import.meta.env` (a Vite-only global), which Node cannot evaluate. The
 * session tests redirect the OE service's `../config/endpoints` import here so the production
 * service code is exercised unmodified under the Node test runner.
 */
export const API_ROOT_URL = 'https://stub.invalid/api/v1';
