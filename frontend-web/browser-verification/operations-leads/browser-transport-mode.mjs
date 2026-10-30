export const BROWSER_TRANSPORT_MODE = Object.freeze({
  CONTROLLED: 'CONTROLLED',
  STRICT_GENUINE: 'STRICT_GENUINE'
});

export const STRICT_GENUINE_FLAG = 'PATHOME_BROWSER_STRICT_GENUINE';

export function browserTransportMode(environment = {}) {
  const value = environment[STRICT_GENUINE_FLAG];
  if (value === undefined || value === 'false') {
    return BROWSER_TRANSPORT_MODE.CONTROLLED;
  }
  if (value === 'true') {
    return BROWSER_TRANSPORT_MODE.STRICT_GENUINE;
  }
  throw new Error(`${STRICT_GENUINE_FLAG} must be exactly "true" or "false"`);
}

export function allowsApiInterception(mode) {
  return mode === BROWSER_TRANSPORT_MODE.CONTROLLED;
}

export async function installTransportRoutes({
  mode,
  page,
  context,
  whatsappHandler,
  apiRoutes = []
}) {
  if (!page || typeof page.route !== 'function'
    || !context || typeof context.route !== 'function'
    || typeof whatsappHandler !== 'function'
    || !Array.isArray(apiRoutes)) {
    throw new Error('transport route registration contract is incomplete');
  }
  await context.route('https://wa.me/**', whatsappHandler);
  if (!allowsApiInterception(mode)) return [];

  const installed = [];
  for (const entry of apiRoutes) {
    if (!Array.isArray(entry) || typeof entry[0] !== 'string'
      || typeof entry[1] !== 'function') {
      throw new Error('API route registration entry is malformed');
    }
    await page.route(entry[0], entry[1]);
    installed.push(entry[0]);
  }
  return installed;
}
