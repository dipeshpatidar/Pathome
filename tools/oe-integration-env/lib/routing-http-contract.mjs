const RESPONSE_SPECS = new Map([
  ['GET /api/v1/lessor/guest/drafts/resume', { statuses: [204] }],
  ['GET /api/v1/properties', { statuses: [200] }],
  ['POST /api/v1/auth/login', { statuses: [200], body: 'login' }],
  ['GET /api/v1/staff/me/capabilities', { statuses: [200], body: 'capabilities' }],
  ['GET /api/v1/favorites/properties', { statuses: [200] }],
  ['GET /api/v1/notifications', { statuses: [200] }],
  ['GET /api/v1/tenant/visit-requests', { statuses: [200] }],
  ['GET /api/v1/tenant/visit-sessions', { statuses: [200] }],
  ['GET /api/v1/tenant/visit-sessions/outcomes', { statuses: [200] }],
  ['GET /api/v1/lessor/capability', { statuses: [200] }],
  ['GET /api/v1/lessor/properties/drafts', { statuses: [200] }],
  ['GET /api/v1/operations/automatic-routing/tasks', { statuses: [200], body: 'page' }],
  ['GET /api/v1/operations/automatic-routing/tasks/unclaimed', { statuses: [200], body: 'page' }],
  ['GET /api/v1/operations/automatic-routing/coverage/cities', { statuses: [200], body: 'array' }],
  ['GET /api/v1/operations/automatic-routing/coverage', { statuses: [200], body: 'page' }],
  ['GET /api/v1/operations/automatic-routing/coverage/options', { statuses: [200], body: 'options' }],
  ['GET /api/v1/operations/intake-routing/visit-requests/2/candidates', {
    statuses: [200], body: 'object'
  }]
]);

export class RoutingHttpContractError extends Error {
  constructor(message) {
    super(message);
    this.name = 'RoutingHttpContractError';
  }
}

export function routingResponseSpec(method, path) {
  const normalizedMethod = String(method).toUpperCase();
  const pathname = new URL(path, 'http://127.0.0.1').pathname;
  const spec = RESPONSE_SPECS.get(`${normalizedMethod} ${pathname}`)
    ?? (normalizedMethod === 'GET'
      && /^\/api\/v1\/operations\/intake-routing\/visit-requests\/[1-9]\d*\/candidates$/.test(pathname)
      ? { statuses: [200], body: 'object' }
      : null);
  if (!spec) {
    throw new RoutingHttpContractError(
      `Unexpected API call ${normalizedMethod} ${pathname}`);
  }
  return spec;
}

export function assertExpectedStatus(method, path, status) {
  const spec = routingResponseSpec(method, path);
  if (!spec.statuses.includes(status)) {
    throw new RoutingHttpContractError(
      `${method} ${new URL(path, 'http://127.0.0.1').pathname} returned ${status}; `
      + `expected ${spec.statuses.join(' or ')}`);
  }
  return true;
}

function requireField(value, field) {
  if (!value || !(field in value)) {
    throw new RoutingHttpContractError(`Required response field '${field}' is missing`);
  }
}

export function assertRequiredBody(method, path, body) {
  const spec = routingResponseSpec(method, path);
  if (!spec.body) return true;
  if (body === null || typeof body !== 'object') {
    throw new RoutingHttpContractError(`${method} ${path} response is not valid JSON`);
  }
  switch (spec.body) {
    case 'login':
      requireField(body, 'token');
      if (typeof body.token !== 'string' || !body.token.includes('.')) {
        throw new RoutingHttpContractError('Login response did not contain a settled token');
      }
      break;
    case 'capabilities':
      requireField(body, 'staffActive');
      requireField(body, 'capabilities');
      if (typeof body.staffActive !== 'boolean' || !Array.isArray(body.capabilities)) {
        throw new RoutingHttpContractError('Capabilities response contract is malformed');
      }
      break;
    case 'page':
      requireField(body, 'items');
      requireField(body, 'totalCount');
      if (!Array.isArray(body.items) || !Number.isSafeInteger(body.totalCount)) {
        throw new RoutingHttpContractError('Paged response contract is malformed');
      }
      break;
    case 'array':
      if (!Array.isArray(body)) {
        throw new RoutingHttpContractError('Array response contract is malformed');
      }
      break;
    case 'object':
      if (Array.isArray(body)) {
        throw new RoutingHttpContractError('Object response contract is malformed');
      }
      break;
    case 'options':
      requireField(body, 'city');
      requireField(body, 'localities');
      requireField(body, 'teams');
      if (!Array.isArray(body.localities) || !Array.isArray(body.teams)) {
        throw new RoutingHttpContractError('Coverage options response contract is malformed');
      }
      break;
    default:
      throw new RoutingHttpContractError('Unknown response body contract');
  }
  return true;
}

export function validateObservedResponses(records) {
  const unexpected = [];
  for (const record of records) {
    try {
      assertExpectedStatus(record.method, record.path, record.status);
    } catch (error) {
      unexpected.push(error.message);
    }
  }
  if (unexpected.length) {
    throw new RoutingHttpContractError(
      `Unexpected API response status: ${unexpected.join('; ')}`);
  }
  return true;
}

export function requireObservedResponse(records, method, path) {
  const normalizedMethod = String(method).toUpperCase();
  const pathname = new URL(path, 'http://127.0.0.1').pathname;
  const matches = records.filter(record => record.method === normalizedMethod
    && new URL(record.path, 'http://127.0.0.1').pathname === pathname);
  if (!matches.length) {
    throw new RoutingHttpContractError(`Required response ${normalizedMethod} ${pathname} was missing`);
  }
  for (const record of matches) {
    assertExpectedStatus(record.method, record.path, record.status);
  }
  return matches;
}

export async function awaitSettled(promise, label, timeoutMs = 15000) {
  let timer;
  try {
    return await Promise.race([
      promise,
      new Promise((_, reject) => {
        timer = setTimeout(() => reject(
          new RoutingHttpContractError(`Required response ${label} timed out`)), timeoutMs);
      })
    ]);
  } finally {
    clearTimeout(timer);
  }
}
