/**
 * Pathome Phase 2A - Operations (OE) full-stack acceptance manifest.
 *
 * This file is the single source of truth for the future disposable-environment acceptance run. It is:
 *
 *   * executed by `phase2a/fullstack.mjs` against the real Spring Boot backend on the designated
 *     disposable database, over real HTTP, with no stubbed or fabricated response anywhere; and
 *   * checked statically, without any database, by `tests/oe-harness-static.test.mjs`, which proves that
 *     every declared route really exists in the backend controller sources and that every approved
 *     Phase 2A area is covered.
 *
 * Two kinds of entry exist and they are never conflated:
 *
 *   * `fixture`  - indispensable setup through real APIs, with one explicitly authorized, guarded,
 *                  audited disposable-only Admin/GE role exception (F14); never an acceptance claim.
 *   * `coverage` - the acceptance claim. Its result is what the run reports as verified behaviour.
 *
 * Every entry declares `controller` and `mapping` so the static test can prove the route exists. A
 * `mapping` of `null` means the method annotation carries no path literal (the route is the class-level
 * mapping itself, e.g. a bare `@PostMapping`).
 */

/** The approved Phase 2A workflows. Every area must carry at least one `coverage` scenario. */
export const PHASE2A_AREAS = [
  { id: 'staff-capability-team-scope', title: 'Staff capability and team scoping' },
  { id: 'my-work-claimable-work', title: 'My Work and Claimable Work' },
  { id: 'claim-release', title: 'Claim and Release' },
  { id: 'request-session-detail', title: 'Request and Session detail' },
  { id: 'lessor-contact-privacy', title: 'Lessor contact privacy' },
  { id: 'request-coordination', title: 'Request coordination' },
  { id: 'draft-itinerary-operations', title: 'DRAFT itinerary operations' },
  { id: 'property-availability', title: 'Property availability' },
  { id: 'ge-discovery-assignment', title: 'GE discovery and assignment' },
  { id: 'successful-scheduling', title: 'Successful scheduling' },
  {
    id: 'authorization-conflict-reconciliation',
    title: 'Authorization denial, optimistic conflicts and safe reconciliation'
  }
];

/**
 * Actors sign in over real HTTP. Capability grants use supported APIs; the legacy role-only fixture
 * exception is explicit and audited. JWT roles and live capability facts are both verified.
 */
export const PHASE2A_ACTORS = {
  'anonymous': { description: 'no credential at all' },
  'plain-tenant': { description: 'a registered account with no staff profile' },
  'staff-no-grant': { description: 'an active staff profile with no capability grant' },
  'city-admin': { description: 'CITY_TEAM_ADMIN@CITY(city) only - no Operations authority' },
  'oe-alpha': { description: 'OPS_COORDINATE@TEAM(alpha)' },
  'oe-bravo': { description: 'OPS_COORDINATE@TEAM(bravo) - a different Team' },
  'ge-alpha': { description: 'audited disposable ROLE_GROUND_BOY fixture, API-granted OPS_COORDINATE@TEAM(alpha), active GE scheduling profile' },
  'bootstrap-admin': { description: 'STAFF_ADMIN@GLOBAL from the first-Admin bootstrap' },
  'lessor': { description: 'the listing owner the journey publishes' },
  'reviewer': { description: 'STAFF_ADMIN@GLOBAL created through the staff-administration API' }
};

/**
 * Non-negotiable rules of the acceptance run. A run that breaks any of these is not a genuine
 * full-stack verification and must be reported as failed rather than as passing.
 */
export const PHASE2A_RULES = [
  'Every request goes to the real backend on the designated disposable database; no response is ever stubbed, replayed or fabricated.',
  'Fixtures use supported APIs except the authorized F14 legacy Admin/GE role fixture: exact disposable target, sentinel/run/backend proof, two synthetic activated users, explicit opt-in and append-only audit evidence. No grant SQL or trigger bypass.',
  'Authority is read from live facts. A scenario may never assume a capability that its actor did not obtain through the staff-administration API.',
  'Conflicts are expected to be conflict-safe: after a 409 the next read must expose the authoritative version and the refused command must have changed nothing.',
  'Reads of another actor\'s work must be indistinguishable from reads of a missing target (404), never a disclosure of existence.',
  'The media upload step talks to the approved disposable media destination only; it is refused when that destination is not explicitly approved.'
];

/**
 * The manifest. `stage` orders execution; `expect` lists the acceptable HTTP statuses; `assert` is an
 * optional predicate over `{ status, json, text, headers, ctx }` returning `true` or a failure detail.
 */
const DECLARED_SCENARIOS = [
  /* ------------------------------- fixtures (real APIs, not acceptance claims) ----------------- */
  {
    id: 'F01', stage: 10, kind: 'fixture', actor: 'lessor', area: null,
    controller: 'AuthController', mapping: '/register', apiPath: '/auth/register', method: 'POST',
    expect: [200],
    note: 'a fresh lessor account is registered through public registration'
  },
  {
    id: 'F02', stage: 10, kind: 'fixture', actor: 'lessor', area: null,
    controller: 'LandlordDraftController', mapping: null, apiPath: '/lessor/properties/drafts', method: 'POST',
    expect: [200],
    note: 'the listing draft is created'
  },
  {
    id: 'F03', stage: 10, kind: 'fixture', actor: 'lessor', area: null,
    controller: 'LandlordDraftController', mapping: '/{draftId}/sections/location',
    apiPath: '/lessor/properties/drafts/{draftId}/sections/location', method: 'PATCH',
    expect: [200],
    note: 'the draft location is saved with an unresolved canonical locality (the reviewer links it later)'
  },
  {
    id: 'F04', stage: 10, kind: 'fixture', actor: 'lessor', area: null,
    controller: 'LandlordMediaController', mapping: null,
    apiPath: '/lessor/properties/drafts/{draftId}/media', method: 'POST',
    expect: [200],
    note: 'the cover photo is uploaded to the approved disposable media destination'
  },
  {
    id: 'F05', stage: 10, kind: 'fixture', actor: 'lessor', area: null,
    controller: 'LandlordMediaController', mapping: '/{mediaId}/cover',
    apiPath: '/lessor/properties/drafts/{draftId}/media/{mediaId}/cover', method: 'PUT',
    expect: [200],
    note: 'the uploaded photo is designated as the cover'
  },
  {
    id: 'F06', stage: 10, kind: 'fixture', actor: 'lessor', area: null,
    controller: 'LandlordSubmissionController', mapping: '/submit',
    apiPath: '/lessor/properties/drafts/{draftId}/submit', method: 'POST',
    expect: [200],
    note: 'the submission creates a SUBMITTED listing that is not yet public'
  },
  {
    id: 'F07', stage: 10, kind: 'fixture', actor: 'reviewer', area: null,
    controller: 'LandlordReviewController', mapping: '/listings/{listingId}/start-review',
    apiPath: '/admin/lessor-review/listings/{listingId}/start-review', method: 'POST',
    expect: [200],
    note: 'review starts on the submitted listing'
  },
  {
    id: 'F08', stage: 10, kind: 'fixture', actor: 'reviewer', area: null,
    controller: 'LandlordReviewController', mapping: '/listings/{listingId}/link-locality',
    apiPath: '/admin/lessor-review/listings/{listingId}/link-locality', method: 'POST',
    expect: [200],
    note: 'the canonical locality discovered through the lessor suggestion API is linked'
  },
  {
    id: 'F09', stage: 10, kind: 'fixture', actor: 'reviewer', area: null,
    controller: 'LandlordReviewController', mapping: '/listings/{listingId}/approve',
    apiPath: '/admin/lessor-review/listings/{listingId}/approve', method: 'POST',
    expect: [200],
    note: 'publication approval publishes the listing so a tenant can request a visit'
  },
  {
    id: 'F10', stage: 10, kind: 'fixture', actor: 'bootstrap-admin', area: null,
    controller: 'OperatingTeamProvisioningController', mapping: '/cities/{cityId}/operating-teams',
    apiPath: '/staff/admin/cities/{cityId}/operating-teams', method: 'POST',
    expect: [201, 409],
    note: 'Team alpha is created (409 means it already exists from an earlier run and is reused)'
  },
  {
    id: 'F11', stage: 10, kind: 'fixture', actor: 'bootstrap-admin', area: null,
    controller: 'OperatingTeamProvisioningController', mapping: '/cities/{cityId}/operating-teams',
    apiPath: '/staff/admin/cities/{cityId}/operating-teams', method: 'POST',
    expect: [201, 409],
    note: 'Team bravo is created for the cross-Team isolation controls'
  },
  {
    id: 'F12', stage: 10, kind: 'fixture', actor: 'bootstrap-admin', area: null,
    controller: 'StaffAdminController', mapping: '/users/{userId}/activate',
    apiPath: '/staff/admin/users/{userId}/activate', method: 'POST',
    expect: [200],
    note: 'each staff profile is activated through the staff-administration API'
  },
  {
    id: 'F13', stage: 10, kind: 'fixture', actor: 'bootstrap-admin', area: null,
    controller: 'StaffAdminController', mapping: '/users/{userId}/grants',
    apiPath: '/staff/admin/users/{userId}/grants', method: 'POST',
    expect: [200],
    note: 'OPS_COORDINATE@TEAM grants are created through the audited Admin API, never by hand'
  },
  {
    id: 'F14', stage: 10, kind: 'fixture', actor: 'bootstrap-admin', area: null,
    controller: 'StaffAdminController', mapping: '/users/{userId}/grants',
    apiPath: '/staff/admin/users/{userId}/grants', method: 'POST',
    expect: [200],
    note: 'the GE supervision grant and the CITY_TEAM_ADMIN@CITY control grant are created the same way'
  },
  {
    id: 'F15', stage: 10, kind: 'fixture', actor: 'plain-tenant', area: null,
    controller: 'PropertyController', mapping: '/{id}/visit-requests',
    apiPath: '/properties/{listingId}/visit-requests', method: 'POST',
    expect: [201],
    note: 'the tenant creates a genuine Visit Request against the published listing'
  },
  {
    id: 'F16', stage: 10, kind: 'fixture', actor: 'bootstrap-admin', area: null,
    controller: 'OperationalIntakeRoutingController', mapping: '/visit-requests/{requestId}/route',
    apiPath: '/operations/intake-routing/visit-requests/{requestId}/route', method: 'POST',
    expect: [204],
    note: 'explicit Admin routing assigns the Request to Team alpha with an explicit City and Team version'
  },
  {
    id: 'F17', stage: 10, kind: 'fixture', actor: 'ge-alpha', area: null,
    controller: 'GroundExecutiveSchedulingController', mapping: '/active',
    apiPath: '/operations/ground-executives/{groundExecutiveUserId}/scheduling/active', method: 'PUT',
    expect: [200],
    note: 'the Ground Executive activates its own scheduling profile'
  },
  {
    id: 'F18', stage: 10, kind: 'fixture', actor: 'ge-alpha', area: null,
    controller: 'GroundExecutiveSchedulingController', mapping: '/shifts',
    apiPath: '/operations/ground-executives/{groundExecutiveUserId}/scheduling/shifts', method: 'POST',
    expect: [200],
    note: 'a real available shift window is recorded for the Ground Executive'
  },
  {
    id: 'F19', stage: 10, kind: 'fixture', actor: 'ge-alpha', area: null,
    controller: 'GroundExecutiveSchedulingController', mapping: '/coverage',
    apiPath: '/operations/ground-executives/{groundExecutiveUserId}/scheduling/coverage', method: 'PUT',
    expect: [200],
    note: 'the Ground Executive records coverage for the shift so scheduling can succeed'
  },

  /* ------------------------------- 1. staff capability and team scoping ------------------------ */
  {
    id: 'C01', stage: 20, kind: 'coverage', actor: 'oe-alpha', area: 'staff-capability-team-scope',
    controller: 'StaffCapabilitiesController', mapping: '/me/capabilities',
    apiPath: '/staff/me/capabilities', method: 'GET', expect: [200],
    assert: ({ json }) => (json?.staffActive === true ? true : 'the OE staff profile is not active'),
    note: 'the OE reads its own active staff profile from live facts'
  },
  {
    id: 'C02', stage: 20, kind: 'coverage', actor: 'oe-alpha', area: 'staff-capability-team-scope',
    controller: 'StaffCapabilitiesController', mapping: '/me/capabilities',
    apiPath: '/staff/me/capabilities', method: 'GET', expect: [200],
    assert: ({ json, ctx }) => {
      const team = (json?.capabilities ?? []).filter((c) => c.capability === 'OPS_COORDINATE' && c.scopeType === 'TEAM');
      if (team.length !== 1) return `expected exactly one TEAM-scoped OPS_COORDINATE grant, saw ${team.length}`;
      if (Number(team[0].teamId) !== Number(ctx.teamAlphaId)) return 'the grant is not scoped to Team alpha';
      return (json.capabilities ?? []).some((c) => Number(c.teamId) === Number(ctx.teamBravoId))
        ? 'Team bravo leaked into the OE capability truth'
        : true;
    },
    note: 'the OE holds exactly one OPS_COORDINATE@TEAM grant and cannot see the other Team'
  },
  {
    id: 'C03', stage: 20, kind: 'coverage', actor: 'ge-alpha', area: 'staff-capability-team-scope',
    controller: 'StaffCapabilitiesController', mapping: '/me/capabilities',
    apiPath: '/staff/me/capabilities', method: 'GET', expect: [200],
    assert: ({ json }) => ((json?.capabilities ?? []).some((c) => c.capability === 'OPS_COORDINATE')
      ? true : 'the Ground Executive holds no Operations capability'),
    note: 'the Ground Executive is a staffed Operations actor with an active scheduling profile'
  },
  {
    id: 'C04', stage: 20, kind: 'coverage', actor: 'staff-no-grant', area: 'staff-capability-team-scope',
    controller: 'StaffCapabilitiesController', mapping: '/me/capabilities',
    apiPath: '/staff/me/capabilities', method: 'GET', expect: [200],
    assert: ({ json }) => (json?.staffActive === true && (json?.capabilities ?? []).length === 0
      ? true : 'active staff without a grant must carry zero capabilities'),
    note: 'an active staff profile with no grant is truthfully reported as holding nothing'
  },
  {
    id: 'C05', stage: 20, kind: 'coverage', actor: 'plain-tenant', area: 'staff-capability-team-scope',
    controller: 'StaffCapabilitiesController', mapping: '/me/capabilities',
    apiPath: '/staff/me/capabilities', method: 'GET', expect: [200],
    assert: ({ json }) => (json?.staffActive === false ? true : 'a plain tenant must have no staff profile'),
    note: 'an ordinary tenant has no staff profile and therefore no Operations capability'
  },
  {
    id: 'C06', stage: 20, kind: 'coverage', actor: 'city-admin', area: 'staff-capability-team-scope',
    controller: 'StaffCapabilitiesController', mapping: '/me/capabilities',
    apiPath: '/staff/me/capabilities', method: 'GET', expect: [200],
    assert: ({ json }) => ((json?.capabilities ?? []).some((c) => c.capability === 'OPS_COORDINATE')
      ? 'a City Team Admin must not acquire Operations authority' : true),
    note: 'CITY_TEAM_ADMIN@CITY grants no Operations capability'
  },

  /* ------------------------------- 2. My Work and Claimable Work ------------------------------ */
  {
    id: 'C07', stage: 20, kind: 'coverage', actor: 'oe-alpha', area: 'my-work-claimable-work',
    controller: 'ClaimableWorkController', mapping: null, apiPath: '/operations/claimable-work', method: 'GET',
    expect: [200],
    assert: ({ json, headers, ctx }) => {
      if (!String(headers['cache-control']).includes('no-store')) return 'the claimable read is cacheable';
      const item = (json?.items ?? []).find((i) => Number(i.targetId) === Number(ctx.requestId));
      if (!item) return 'the routed Request is not claimable work for the owning Team';
      return Number(item.teamId) === Number(ctx.teamAlphaId) ? true : 'the claimable item names another Team';
    },
    note: 'the routed Request is claimable work for exactly the owning Team, and the read is never cached'
  },
  {
    id: 'C08', stage: 20, kind: 'coverage', actor: 'oe-bravo', area: 'my-work-claimable-work',
    controller: 'ClaimableWorkController', mapping: null, apiPath: '/operations/claimable-work', method: 'GET',
    expect: [200],
    assert: ({ json, ctx }) => ((json?.items ?? []).some((i) => Number(i.targetId) === Number(ctx.requestId))
      ? 'another Team can see work it does not own' : true),
    note: 'a different Team cannot see the routed Request'
  },
  {
    id: 'C09', stage: 20, kind: 'coverage', actor: 'oe-alpha', area: 'my-work-claimable-work',
    controller: 'ClaimableWorkController', mapping: '/{targetType}/{targetId}',
    apiPath: '/operations/claimable-work/{targetType}/{targetId}', method: 'GET', expect: [200],
    assert: ({ json, ctx }) => (Number(json?.targetId) === Number(ctx.requestId) && Number(json?.expectedVersion) >= 0
      ? true : 'the targeted claimable refresh did not return the Request with its optimistic version'),
    note: 'the targeted claimable refresh exposes the current optimistic version before a command'
  },
  {
    id: 'C10', stage: 20, kind: 'coverage', actor: 'oe-alpha', area: 'my-work-claimable-work',
    controller: 'OperationsMyWorkController', mapping: '/my-work',
    apiPath: '/operations/my-work', method: 'GET', expect: [200],
    assert: ({ json, headers }) => (!String(headers['cache-control']).includes('no-store')
      ? 'My Work is cacheable' : (Array.isArray(json?.items) ? true : 'My Work returned no item list')),
    note: 'My Work is an authoritative, never-cached read that is empty before the first claim'
  },

  /* ------------------------------- 3. Claim and Release --------------------------------------- */
  {
    id: 'C11', stage: 30, kind: 'coverage', actor: 'oe-alpha', area: 'claim-release',
    controller: 'OperationalOwnershipController', mapping: '/visit-requests/{requestId}/claim',
    apiPath: '/operations/visit-requests/{requestId}/claim', method: 'POST', expect: [409],
    note: 'a claim with a stale optimistic version is refused with a conflict'
  },
  {
    id: 'C12', stage: 30, kind: 'coverage', actor: 'oe-alpha', area: 'claim-release',
    controller: 'OperationalOwnershipController', mapping: '/visit-requests/{requestId}/claim',
    apiPath: '/operations/visit-requests/{requestId}/claim', method: 'POST', expect: [204],
    note: 'the owning Team claims the routed Request with its current version'
  },
  {
    id: 'C13', stage: 30, kind: 'coverage', actor: 'oe-alpha', area: 'my-work-claimable-work',
    controller: 'OperationsMyWorkController', mapping: '/my-work',
    apiPath: '/operations/my-work', method: 'GET', expect: [200],
    assert: ({ json, ctx }) => {
      const item = (json?.items ?? []).find((i) => Number(i.targetId) === Number(ctx.requestId));
      if (!item) return 'the claimed Request is missing from My Work';
      return Number(item.expectedVersion) === Number(ctx.claimVersion) + 1
        ? true : 'the post-claim optimistic version did not advance by exactly one';
    },
    note: 'after the claim My Work exposes the post-claim optimistic version (reconciliation, not a cached echo)'
  },
  {
    id: 'C14', stage: 30, kind: 'coverage', actor: 'oe-alpha', area: 'claim-release',
    controller: 'OperationalOwnershipController', mapping: '/visit-requests/{requestId}/claim',
    apiPath: '/operations/visit-requests/{requestId}/claim', method: 'POST', expect: [409],
    note: 'a repeated claim of an already-owned Request is refused, so no duplicate ownership is created'
  },
  {
    id: 'C15', stage: 30, kind: 'coverage', actor: 'oe-bravo', area: 'authorization-conflict-reconciliation',
    controller: 'OperationalOwnershipController', mapping: '/visit-requests/{requestId}/claim',
    apiPath: '/operations/visit-requests/{requestId}/claim', method: 'POST', expect: [403, 404],
    note: 'another Team cannot claim work that is not its own'
  },
  {
    id: 'C16', stage: 45, kind: 'coverage', actor: 'oe-alpha', area: 'claim-release',
    controller: 'OperationalOwnershipController', mapping: '/visit-requests/{requestId}/claim',
    apiPath: '/operations/visit-requests/{secondRequestId}/claim', method: 'POST', expect: [204],
    note: 'the owner claims a second routed Request, so the release cycle is exercised on its own root'
  },
  {
    id: 'C17', stage: 45, kind: 'coverage', actor: 'oe-alpha', area: 'claim-release',
    controller: 'OperationalOwnershipController', mapping: '/visit-requests/{requestId}/release',
    apiPath: '/operations/visit-requests/{secondRequestId}/release', method: 'POST', expect: [204],
    note: 'the owner releases that Request and it returns to claimable work'
  },
  {
    id: 'C18', stage: 45, kind: 'coverage', actor: 'oe-alpha', area: 'claim-release',
    controller: 'OperationalOwnershipController', mapping: '/visit-requests/{requestId}/release',
    apiPath: '/operations/visit-requests/{secondRequestId}/release', method: 'POST', expect: [409],
    note: 'a release with a stale version is refused and the ownership is unchanged'
  },
  {
    id: 'C19', stage: 60, kind: 'coverage', actor: 'oe-alpha', area: 'claim-release',
    controller: 'OperationalOwnershipController', mapping: '/visit-sessions/{sessionId}/claim',
    apiPath: '/operations/visit-sessions/{sessionId}/claim', method: 'POST', expect: [204],
    note: 'the DRAFT Session itself is claimable and claim creates a real Session root ownership'
  },
  {
    id: 'C20', stage: 60, kind: 'coverage', actor: 'oe-alpha', area: 'claim-release',
    controller: 'OperationalOwnershipController', mapping: '/visit-sessions/{sessionId}/release',
    apiPath: '/operations/visit-sessions/{sessionId}/release', method: 'POST', expect: [204],
    note: 'the Session claim is released through the same governed ownership route'
  },
  {
    id: 'C63', stage: 46, kind: 'coverage', actor: 'oe-alpha', area: 'claim-release',
    controller: 'ClaimableWorkController', mapping: null, apiPath: '/operations/claimable-work', method: 'GET',
    expect: [200],
    assert: ({ json, ctx }) => ((json?.items ?? []).some((i) => Number(i.targetId) === Number(ctx.secondRequestId))
      ? true : 'the released Request is not claimable again by its Team'),
    note: 'after the release the Request is claimable again by exactly its Team'
  },

  /* ------------------------------- 4. Request and Session detail ------------------------------ */
  {
    id: 'C21', stage: 40, kind: 'coverage', actor: 'oe-alpha', area: 'request-session-detail',
    controller: 'VisitOperationsController', mapping: '/visit-requests',
    apiPath: '/operations/visit-requests', method: 'GET', expect: [200],
    note: 'the Request collection read answers the owning Team with a real page'
  },
  {
    id: 'C22', stage: 40, kind: 'coverage', actor: 'oe-alpha', area: 'request-session-detail',
    controller: 'OperationsMyWorkController', mapping: '/visit-requests/{requestId}',
    apiPath: '/operations/visit-requests/{requestId}', method: 'GET', expect: [200],
    assert: ({ json, ctx, headers }) => {
      if (Number(json?.requestId) !== Number(ctx.requestId)) return 'the owned detail is the wrong Request';
      return String(headers['cache-control']).includes('no-store') ? true : 'the owned detail is cacheable';
    },
    note: 'the owning Team opens Request detail and the read is never cached'
  },
  {
    id: 'C23', stage: 40, kind: 'coverage', actor: 'oe-bravo', area: 'authorization-conflict-reconciliation',
    controller: 'OperationsMyWorkController', mapping: '/visit-requests/{requestId}',
    apiPath: '/operations/visit-requests/{requestId}', method: 'GET', expect: [404],
    note: 'another Team sees a concealed 404, not the existence of the Request'
  },
  {
    id: 'C24', stage: 60, kind: 'coverage', actor: 'oe-alpha', area: 'request-session-detail',
    controller: 'VisitOperationsController', mapping: '/visit-sessions/{sessionId}',
    apiPath: '/operations/visit-sessions/{sessionId}', method: 'GET', expect: [200],
    assert: ({ json, headers }) => (String(headers['cache-control']).includes('no-store')
      ? true : 'the Session detail read is cacheable'),
    note: 'the owning Team opens Session detail and the read is never cached'
  },
  {
    id: 'C25', stage: 60, kind: 'coverage', actor: 'oe-bravo', area: 'authorization-conflict-reconciliation',
    controller: 'VisitOperationsController', mapping: '/visit-sessions/{sessionId}',
    apiPath: '/operations/visit-sessions/{sessionId}', method: 'GET', expect: [404],
    note: 'another Team cannot open an unowned Session detail'
  },
  {
    id: 'C26', stage: 90, kind: 'coverage', actor: 'oe-alpha', area: 'authorization-conflict-reconciliation',
    controller: 'OperationsMyWorkController', mapping: '/visit-requests/{requestId}',
    apiPath: '/operations/visit-requests/{requestId}', method: 'GET', expect: [404],
    note: 'a nonexistent Request is answered with the same 404 as an unowned one, so existence is never disclosed'
  },

  /* ------------------------------- 5. Lessor contact privacy --------------------------------- */
  {
    id: 'C27', stage: 40, kind: 'coverage', actor: 'oe-alpha', area: 'lessor-contact-privacy',
    controller: 'OperationsLessorContactController', mapping: '/visit-requests/{requestId}/lessor-contact',
    apiPath: '/operations/visit-requests/{requestId}/lessor-contact', method: 'GET', expect: [200],
    assert: ({ headers }) => (String(headers['cache-control']).includes('no-store')
      ? true : 'a route that can emit contact PII must never be cacheable'),
    note: 'the owning Team reads the lessor contact for its own Request, never cacheable'
  },
  {
    id: 'C28', stage: 40, kind: 'coverage', actor: 'oe-bravo', area: 'lessor-contact-privacy',
    controller: 'OperationsLessorContactController', mapping: '/visit-requests/{requestId}/lessor-contact',
    apiPath: '/operations/visit-requests/{requestId}/lessor-contact', method: 'GET', expect: [403, 404],
    note: 'another Team receives no contact and no confirmation that the Request exists'
  },
  {
    id: 'C29', stage: 40, kind: 'coverage', actor: 'anonymous', area: 'lessor-contact-privacy',
    controller: 'OperationsLessorContactController', mapping: '/visit-requests/{requestId}/lessor-contact',
    apiPath: '/operations/visit-requests/{requestId}/lessor-contact', method: 'GET', expect: [401],
    note: 'contact detail is never readable without a credential'
  },
  {
    id: 'C30', stage: 60, kind: 'coverage', actor: 'oe-alpha', area: 'lessor-contact-privacy',
    controller: 'OperationsLessorContactController', mapping: '/visit-sessions/{sessionId}/items/{itemId}/lessor-contact',
    apiPath: '/operations/visit-sessions/{sessionId}/items/{itemId}/lessor-contact', method: 'GET', expect: [200],
    assert: ({ headers }) => (String(headers['cache-control']).includes('no-store')
      ? true : 'a route that can emit contact PII must never be cacheable'),
    note: 'the owning Team reads the lessor contact of a Session stop, never cacheable'
  },
  {
    id: 'C31', stage: 60, kind: 'coverage', actor: 'oe-bravo', area: 'lessor-contact-privacy',
    controller: 'OperationsLessorContactController', mapping: '/visit-sessions/{sessionId}/items/{itemId}/lessor-contact',
    apiPath: '/operations/visit-sessions/{sessionId}/items/{itemId}/lessor-contact', method: 'GET',
    expect: [403, 404],
    note: 'another Team receives no stop contact for a Session it does not own'
  },

  /* ------------------------------- 6. Request coordination ----------------------------------- */
  {
    id: 'C32', stage: 50, kind: 'coverage', actor: 'oe-alpha', area: 'request-coordination',
    controller: 'VisitOperationsController', mapping: '/visit-requests/{requestId}/coordinate',
    apiPath: '/operations/visit-requests/{requestId}/coordinate', method: 'POST', expect: [200],
    assert: ({ json }) => (String(json?.status ?? '').toUpperCase() === 'DRAFT'
      ? true : `coordination must produce a DRAFT Session, saw ${json?.status}`),
    note: 'coordination replaces the claimed Request with a genuine DRAFT Session'
  },
  {
    id: 'C33', stage: 90, kind: 'coverage', actor: 'oe-alpha', area: 'request-coordination',
    controller: 'VisitOperationsController', mapping: '/visit-requests/{requestId}/coordinate',
    apiPath: '/operations/visit-requests/{requestId}/coordinate', method: 'POST', expect: [409],
    note: 'a repeated coordination of the same Request is refused and creates no second Session'
  },
  {
    id: 'C34', stage: 90, kind: 'coverage', actor: 'oe-bravo', area: 'request-coordination',
    controller: 'VisitOperationsController', mapping: '/visit-requests/{requestId}/coordinate',
    apiPath: '/operations/visit-requests/{requestId}/coordinate', method: 'POST', expect: [403, 404, 409],
    note: 'another Team cannot coordinate a Request it does not own'
  },
  {
    id: 'C35', stage: 90, kind: 'coverage', actor: 'oe-alpha', area: 'request-coordination',
    controller: 'VisitOperationsController', mapping: '/visit-requests/{requestId}/unavailable',
    apiPath: '/operations/visit-requests/{requestId}/unavailable', method: 'POST', expect: [200, 409],
    note: 'the owner can mark a Request unavailable, and a stale version is refused with a conflict'
  },

  /* ------------------------------- 7. DRAFT itinerary operations ----------------------------- */
  {
    id: 'C36', stage: 60, kind: 'coverage', actor: 'oe-alpha', area: 'draft-itinerary-operations',
    controller: 'VisitOperationsController', mapping: '/visit-sessions/{sessionId}/items',
    apiPath: '/operations/visit-sessions/{sessionId}/items', method: 'POST', expect: [200],
    assert: ({ json }) => ((json?.items ?? []).length >= 1
      ? true : 'the added stop is not present in the returned Session'),
    note: 'a nearby property is added to the DRAFT itinerary'
  },
  {
    id: 'C37', stage: 60, kind: 'coverage', actor: 'oe-alpha', area: 'draft-itinerary-operations',
    controller: 'VisitOperationsController', mapping: '/visit-sessions/{sessionId}/items/order',
    apiPath: '/operations/visit-sessions/{sessionId}/items/order', method: 'PUT', expect: [200],
    note: 'the itinerary order is an explicit, version-checked command'
  },
  {
    id: 'C38', stage: 60, kind: 'coverage', actor: 'oe-alpha', area: 'draft-itinerary-operations',
    controller: 'VisitOperationsController', mapping: '/visit-sessions/{sessionId}/items/{itemId}',
    apiPath: '/operations/visit-sessions/{sessionId}/items/{itemId}', method: 'DELETE', expect: [200],
    note: 'a stop is removed with a reason and the removal is recorded, not silently dropped'
  },
  {
    id: 'C39', stage: 90, kind: 'coverage', actor: 'oe-alpha', area: 'draft-itinerary-operations',
    controller: 'VisitOperationsController', mapping: '/visit-sessions/{sessionId}/items',
    apiPath: '/operations/visit-sessions/{sessionId}/items', method: 'POST', expect: [409],
    note: 'after scheduling the itinerary is no longer editable, so no post-schedule drift is possible'
  },
  {
    id: 'C40', stage: 60, kind: 'coverage', actor: 'oe-bravo', area: 'draft-itinerary-operations',
    controller: 'VisitOperationsController', mapping: '/visit-sessions/{sessionId}/items',
    apiPath: '/operations/visit-sessions/{sessionId}/items', method: 'POST', expect: [403, 404],
    note: 'another Team cannot mutate an unowned itinerary'
  },

  /* ------------------------------- 8. Property availability ---------------------------------- */
  {
    id: 'C41', stage: 60, kind: 'coverage', actor: 'oe-alpha', area: 'property-availability',
    controller: 'VisitOperationsController', mapping: '/visit-sessions/{sessionId}/items/{itemId}/availability',
    apiPath: '/operations/visit-sessions/{sessionId}/items/{itemId}/availability', method: 'PUT', expect: [200],
    assert: ({ json, ctx }) => {
      if (Number(json?.sessionId) !== Number(ctx.sessionId)) return 'the availability update returned another Session';
      const item = (json?.items ?? []).find((i) => Number(i.itemId) === Number(ctx.itemId));
      if (!item) return 'the availability update did not return the updated stop';
      return item.propertyAvailabilityStartAt
        ? true : 'the confirmed availability window was not applied to the stop';
    },
    note: 'the OE records a confirmed availability window for a stop (window plus source)'
  },
  {
    id: 'C42', stage: 60, kind: 'coverage', actor: 'oe-alpha', area: 'property-availability',
    controller: 'VisitOperationsController', mapping: '/visit-sessions/{sessionId}/items/{itemId}/availability',
    apiPath: '/operations/visit-sessions/{sessionId}/items/{itemId}/availability', method: 'PUT', expect: [400],
    note: 'an availability command missing the window, the zone or the source is rejected as malformed'
  },
  {
    id: 'C43', stage: 60, kind: 'coverage', actor: 'oe-bravo', area: 'property-availability',
    controller: 'VisitOperationsController', mapping: '/visit-sessions/{sessionId}/items/{itemId}/availability',
    apiPath: '/operations/visit-sessions/{sessionId}/items/{itemId}/availability', method: 'PUT',
    expect: [403, 404],
    note: 'another Team cannot record availability on an unowned stop'
  },

  /* ------------------------------- 9. GE discovery and assignment ---------------------------- */
  {
    id: 'C44', stage: 70, kind: 'coverage', actor: 'oe-alpha', area: 'ge-discovery-assignment',
    controller: 'VisitOperationsController', mapping: '/visit-sessions/{sessionId}/recommendations',
    apiPath: '/operations/visit-sessions/{sessionId}/recommendations', method: 'POST', expect: [200],
    note: 'the OE discovers real Ground Executive candidates for the DRAFT itinerary'
  },
  {
    id: 'C45', stage: 70, kind: 'coverage', actor: 'oe-alpha', area: 'ge-discovery-assignment',
    controller: 'VisitOperationsController', mapping: '/visit-sessions/{sessionId}/recommendations/approval',
    apiPath: '/operations/visit-sessions/{sessionId}/recommendations/approval', method: 'POST', expect: [200],
    note: 'the recommendation is explicitly approved, so a discovered candidate is never silently assigned'
  },
  {
    id: 'C46', stage: 70, kind: 'coverage', actor: 'oe-alpha', area: 'ge-discovery-assignment',
    controller: 'VisitOperationsController', mapping: '/visit-sessions/{sessionId}/oe-recommendations',
    apiPath: '/operations/visit-sessions/{sessionId}/oe-recommendations', method: 'POST', expect: [200],
    note: 'the owner-facing candidate read returns real, non-fabricated candidates'
  },
  {
    id: 'C47', stage: 70, kind: 'coverage', actor: 'oe-alpha', area: 'ge-discovery-assignment',
    controller: 'VisitOperationsController', mapping: '/visit-sessions/{sessionId}/oe-recommendations/selection',
    apiPath: '/operations/visit-sessions/{sessionId}/oe-recommendations/selection', method: 'POST', expect: [200],
    note: 'an explicit candidate selection is recorded through the governed route'
  },
  {
    id: 'C48', stage: 70, kind: 'coverage', actor: 'oe-alpha', area: 'ge-discovery-assignment',
    controller: 'VisitOperationsController', mapping: '/visit-sessions/{sessionId}/assignment',
    apiPath: '/operations/visit-sessions/{sessionId}/assignment', method: 'POST', expect: [200],
    assert: ({ json, ctx }) => (Number(json?.representativeUserId) === Number(ctx.geUserId)
      ? true : 'the Session does not name the assigned Ground Executive'),
    note: 'a Ground Executive with an active scheduling profile is assigned to the Session'
  },
  {
    id: 'C49', stage: 90, kind: 'coverage', actor: 'oe-bravo', area: 'ge-discovery-assignment',
    controller: 'VisitOperationsController', mapping: '/visit-sessions/{sessionId}/assignment',
    apiPath: '/operations/visit-sessions/{sessionId}/assignment', method: 'POST', expect: [403, 404, 409],
    note: 'another Team cannot assign a Ground Executive to an unowned Session'
  },

  /* ------------------------------- 10. Successful scheduling --------------------------------- */
  {
    id: 'C50', stage: 80, kind: 'coverage', actor: 'oe-alpha', area: 'successful-scheduling',
    controller: 'VisitOperationsController', mapping: '/visit-sessions/{sessionId}/schedule',
    apiPath: '/operations/visit-sessions/{sessionId}/schedule', method: 'POST', expect: [200],
    assert: ({ json }) => {
      if (String(json?.status ?? '').toUpperCase() !== 'SCHEDULED') return `the Session did not reach SCHEDULED (${json?.status})`;
      return json?.scheduledAt ? true : 'the scheduled Session carries no schedule time';
    },
    note: 'the Session is genuinely scheduled against the confirmed availability and the assigned Ground Executive'
  },
  {
    id: 'C51', stage: 80, kind: 'coverage', actor: 'oe-alpha', area: 'successful-scheduling',
    controller: 'VisitOperationsController', mapping: '/visit-sessions/{sessionId}',
    apiPath: '/operations/visit-sessions/{sessionId}', method: 'GET', expect: [200],
    assert: ({ json, ctx }) => (Number(json?.version) === Number(ctx.scheduledVersion)
      ? true : 're-reading the Session did not return the authoritative post-schedule version'),
    note: 'an independent re-read returns the authoritative scheduled state'
  },
  {
    id: 'C52', stage: 90, kind: 'coverage', actor: 'oe-alpha', area: 'successful-scheduling',
    controller: 'VisitOperationsController', mapping: '/visit-sessions/{sessionId}/schedule',
    apiPath: '/operations/visit-sessions/{sessionId}/schedule', method: 'POST', expect: [409],
    note: 'a repeated schedule command is refused, so no second itinerary or assignment is created'
  },
  {
    id: 'C53', stage: 90, kind: 'coverage', actor: 'oe-alpha', area: 'successful-scheduling',
    controller: 'VisitOperationsController', mapping: '/visit-sessions/{sessionId}/schedule',
    apiPath: '/operations/visit-sessions/{sessionId}/schedule', method: 'PUT', expect: [200],
    note: 'a version-checked reschedule moves the scheduled Session to a new window'
  },
  {
    id: 'C54', stage: 90, kind: 'coverage', actor: 'oe-alpha', area: 'successful-scheduling',
    controller: 'VisitOperationsController', mapping: '/visit-sessions/{sessionId}/schedule',
    apiPath: '/operations/visit-sessions/{sessionId}/schedule', method: 'PUT', expect: [409],
    note: 'a reschedule with a stale version is refused and the scheduled state is unchanged'
  },

  /* ------------------------------- 11. denial, conflict and reconciliation ------------------- */
  {
    id: 'C55', stage: 30, kind: 'coverage', actor: 'anonymous', area: 'authorization-conflict-reconciliation',
    controller: 'ClaimableWorkController', mapping: null, apiPath: '/operations/claimable-work', method: 'GET',
    expect: [401],
    note: 'every Operations route refuses an anonymous caller at the security layer'
  },
  {
    id: 'C56', stage: 30, kind: 'coverage', actor: 'plain-tenant', area: 'authorization-conflict-reconciliation',
    controller: 'ClaimableWorkController', mapping: null, apiPath: '/operations/claimable-work', method: 'GET',
    expect: [403],
    note: 'an ordinary tenant holds no Operations authority'
  },
  {
    id: 'C57', stage: 30, kind: 'coverage', actor: 'staff-no-grant', area: 'authorization-conflict-reconciliation',
    controller: 'ClaimableWorkController', mapping: null, apiPath: '/operations/claimable-work', method: 'GET',
    expect: [403],
    note: 'an active staff profile without a grant holds no Operations authority'
  },
  {
    id: 'C58', stage: 30, kind: 'coverage', actor: 'city-admin', area: 'authorization-conflict-reconciliation',
    controller: 'ClaimableWorkController', mapping: null, apiPath: '/operations/claimable-work', method: 'GET',
    expect: [403],
    note: 'no narrower or wider capability substitutes for OPS_COORDINATE@TEAM'
  },
  {
    id: 'C59', stage: 40, kind: 'coverage', actor: 'anonymous', area: 'authorization-conflict-reconciliation',
    controller: 'OperationsMyWorkController', mapping: '/my-work',
    apiPath: '/operations/my-work', method: 'GET', expect: [401],
    note: 'My Work is never readable without a credential'
  },
  {
    id: 'C60', stage: 40, kind: 'coverage', actor: 'plain-tenant', area: 'authorization-conflict-reconciliation',
    controller: 'OperationsMyWorkController', mapping: '/my-work',
    apiPath: '/operations/my-work', method: 'GET', expect: [403],
    note: 'My Work is refused for a caller with no Operations authority'
  },
  {
    id: 'C61', stage: 90, kind: 'coverage', actor: 'oe-bravo', area: 'authorization-conflict-reconciliation',
    controller: 'OperationsMyWorkController', mapping: '/my-work',
    apiPath: '/operations/my-work', method: 'GET', expect: [200],
    assert: ({ json, ctx }) => ((json?.items ?? []).some((i) => Number(i.targetId) === Number(ctx.requestId)
      || Number(i.targetId) === Number(ctx.sessionId))
      ? 'another account can see work it does not own' : true),
    note: 'after an account switch the new account sees only its own work (cross-account isolation)'
  },
  {
    id: 'C62', stage: 90, kind: 'coverage', actor: 'oe-alpha', area: 'authorization-conflict-reconciliation',
    controller: 'VisitOperationsController', mapping: '/visit-sessions/{sessionId}',
    apiPath: '/operations/visit-sessions/{sessionId}', method: 'GET', expect: [200],
    assert: ({ headers }) => (String(headers['cache-control']).includes('no-store')
      ? true : 'a refused or accepted protected read must never be cacheable'),
    note: 'every conflicting command leaves the authoritative state readable and never cacheable'
  }

];

export const PHASE2A_META = {
  manifestVersion: '2.0.0',
  requiredMediaDependency: 'the media upload fixture (F04/F05) requires an explicitly approved disposable media destination; without it the run fails closed rather than uploading to a real account',
  forbiddenInSuite: ['route.fulfill', 'http.createServer', 'nock', 'msw', 'jest.mock', 'stub(', 'fakeResponse']
};

// Contract corrections preserve all 82 IDs. Admin-only operations use a separate disposable legacy
// Admin session root; OE selection is the operation that schedules the owned root.
const corrections = {
  C12: { note: 'claim succeeds only when authoritative reads prove the exact owner, state and version transition' },
  C28: { note: 'another Team receives no contact data, including nested objects and arrays' },
  C41: { note: 'availability succeeds only when the authoritative persisted window, state, zone and source match the submitted values' },
  F14: { note: 'audited disposable-only legacy Admin/GE role fixture plus supported capability grants' },
  F17: { actor: 'legacy-admin', note: 'authorized Admin activates the actual Ground Executive scheduling profile' },
  F18: { actor: 'legacy-admin', note: 'authorized Admin records offset-aware GE shifts' },
  F19: { actor: 'legacy-admin', note: 'authorized Admin records canonical GE coverage' },
  C06: { assert: ({ json }) => Array.isArray(json?.capabilities) && json.capabilities.some(c => c.capability === 'CITY_TEAM_ADMIN')
    && !json.capabilities.some(c => c.capability === 'OPS_COORDINATE') },
  C08: { assert: ({ json, ctx }) => Array.isArray(json?.items) && !json.items.some(i => Number(i.targetId) === Number(ctx.requestId)) },
  C10: { assert: ({ json, headers }) => Array.isArray(json?.items) && json.items.length === 0 && String(headers['cache-control']).includes('no-store') },
  C13: { assert: ({ json, ctx }) => (json?.items ?? []).some(i => Number(i.targetId) === Number(ctx.requestId)
    && i.expectedVersion === ctx.claimBaseVersion + 1) },
  C20: { stage: 55, note: 'release the newly coordinated owned DRAFT root before testing its claim' },
  C19: { stage: 56, note: 'claim the released Session using fresh Session and linked Request versions' },
  C18: { stage: 45.1, note: 'a stale release is rejected while the independent Request is still owned' },
  C17: { stage: 45.2 },
  C26: { apiPath: '/operations/visit-requests/{missingRequestId}', note: 'a separate missing ID yields the same concealed error contract as another Team' },
  C27: { assert: ({ json, ctx, headers }) => Number(json?.listingId) === Number(ctx.listingId)
    && json.status === 'CONTACT_AVAILABLE' && typeof json.mobile === 'string' && typeof json.name === 'string'
    && String(headers['cache-control']).includes('no-store') },
  C30: { assert: ({ json, ctx, headers }) => Number(json?.listingId) === Number(ctx.listingId)
    && json.status === 'CONTACT_AVAILABLE' && typeof json.mobile === 'string' && String(headers['cache-control']).includes('no-store') },
  C33: { expect: [404], note: 'a linked Request is concealed as a standalone OE coordination target' },
  C34: { expect: [403, 404] },
  C35: { apiPath: '/operations/visit-requests/{secondRequestId}/unavailable', expect: [200],
    assert: ({ json, ctx }) => Number(json?.requestId) === Number(ctx.secondRequestId) && json.status === 'UNAVAILABLE',
    note: 'claim the independent released Request and truthfully mark it unavailable' },
  C36: { assert: ({ json, ctx }) => (json?.items ?? []).some(i => i.listingId === ctx.nearbyListingId && !i.removedAt && i.derivedFromRequestId === ctx.requestId) },
  C37: { assert: ({ json, ctx }) => JSON.stringify((json?.items ?? []).filter(i => !i.removedAt).sort((a,b) => a.position-b.position).map(i => i.itemId)) === JSON.stringify(ctx.submittedOrder) },
  C38: { assert: ({ json, ctx }) => (json?.items ?? []).some(i => i.itemId === ctx.derivedItemId && i.removedAt)
    && (json?.items ?? []).some(i => i.sourceRequestId === ctx.requestId && !i.removedAt) },
  C44: { actor: 'legacy-admin' }, C45: { actor: 'legacy-admin' },
  C48: { actor: 'legacy-admin', note: 'Admin verifies idempotent assignment on its independently scheduled root' },
  C49: { expect: [403], note: 'Admin-only assignment refuses an OE without Admin authority' },
  C50: { method: 'GET', mapping: '/visit-sessions/{sessionId}', apiPath: '/operations/visit-sessions/{sessionId}',
    note: 'independent authoritative read proves OE selection scheduled the owned Session' },
  C52: { actor: 'legacy-admin', expect: [200], note: 'an identical Admin scheduling retry returns the same reservation without mutation' },
  C53: { actor: 'legacy-admin' }, C54: { actor: 'legacy-admin' },
  C61: { assert: ({ json, ctx }) => Array.isArray(json?.items) && !json.items.some(i =>
    (i.targetType === 'REQUEST' && Number(i.targetId) === Number(ctx.requestId))
    || (i.targetType === 'SESSION' && Number(i.targetId) === Number(ctx.primarySessionId))),
    note: 'a separate credential sees only its own API work; browser account switching is a separate B scenario' },
  C62: { assert: ({ json, ctx, headers }) => Number(json?.sessionId) === Number(ctx.primarySessionId)
    && json.version === ctx.finalPrimaryVersion && String(headers['cache-control']).includes('no-store'),
    note: 'the primary Session remains at its independently read authoritative version after refused commands' }
};
PHASE2A_ACTORS['legacy-admin'] = { description: 'audited disposable-only ROLE_ADMIN fixture; production authorization is unchanged' };
export const SCENARIOS = DECLARED_SCENARIOS.map(s => ({ ...s, ...(corrections[s.id] ?? {}) }));
