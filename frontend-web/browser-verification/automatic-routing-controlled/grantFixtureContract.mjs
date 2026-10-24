const CAPABILITY_SCOPE = Object.freeze({
  STAFF_ADMIN: 'GLOBAL',
  CITY_TEAM_ADMIN: 'CITY',
  OPS_INTAKE: 'CITY',
  OPS_ROUTING: 'CITY',
  OPS_COORDINATE: 'TEAM',
  OPS_SUPERVISE: 'TEAM'
});

const positiveId = (value) => Number.isSafeInteger(value) && value > 0;

/**
 * Mirrors the accepted StaffGrantCommand shape used by the controlled browser fixture. This is
 * deliberately stricter than the former fixture: a TEAM command cannot carry a City FK.
 */
export const fixedGrantRequestError = (body) => {
  if (typeof body !== 'object' || body === null || Array.isArray(body)) {
    return 'Grant details are required';
  }
  const expectedScope = CAPABILITY_SCOPE[body.capability];
  if (!expectedScope || expectedScope !== body.scopeType) {
    return 'Capability and scope type are incompatible';
  }
  switch (body.scopeType) {
    case 'GLOBAL':
      return body.cityId === null && body.teamId === null
        ? null
        : 'Capability and scope type are incompatible';
    case 'CITY':
      return positiveId(body.cityId) && body.teamId === null
        ? null
        : 'Capability and scope type are incompatible';
    case 'TEAM':
      return positiveId(body.teamId) && body.cityId === null
        ? null
        : 'Capability and scope type are incompatible';
    default:
      return 'Capability and scope type are incompatible';
  }
};
