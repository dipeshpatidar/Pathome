package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.security.PathomeAuthenticationDetails;
import com.indore.pathome.spaces.service.ListingReviewAuthority.Reviewer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * The admission matrix for the approved listing-review authority extension.
 *
 * <p>Two principals are admitted and nothing else: the legacy {@code ROLE_ADMIN} authority exactly as
 * before, and a caller whose live {@code STAFF_ADMIN@GLOBAL} grant {@link StaffAccessService} confirms.
 * The legacy path is proven to make no staff decision at all, so an existing administrator cannot be
 * locked out by staff provisioning state it never had.
 *
 * <p>Every admission and every guarded re-decision here is taken for the credential the container
 * established on this thread, so the harness authenticates the credential it then presents. The second
 * half of the suite is the failure class this binding closes: a credential the caller supplies that is
 * not the authenticated one, and a verdict replayed in a later request, for another principal, or for
 * the same staff User's next credential.
 */
class ListingReviewAuthorityTest {
    private static final Long STAFF_USER_ID = 42L;

    private final StaffAccessService access = mock(StaffAccessService.class);
    private final ListingReviewAuthority authority = new ListingReviewAuthority(access);

    @AfterEach
    void clearTheRequestPrincipal() {
        SecurityContextHolder.clearContext();
    }

    // ================================================================= admission

    @Test
    void aLegacyAdminAuthorityIsAdmittedWithoutAnyStaffDecision() {
        Reviewer reviewer = authority.require(authenticate("ROLE_ADMIN", null));

        assertTrue(reviewer.legacyAdmin());
        assertNull(reviewer.userId());
        verifyNoInteractions(access);
    }

    @Test
    void aLiveGlobalStaffAdminIsAdmitted() {
        when(access.requireGlobalAdmin(STAFF_USER_ID)).thenReturn(new User());

        Reviewer reviewer = authority.require(authenticate("ROLE_TENANT", STAFF_USER_ID));

        assertFalse(reviewer.legacyAdmin());
        assertEquals(STAFF_USER_ID, reviewer.userId());
        verify(access).requireGlobalAdmin(STAFF_USER_ID);
    }

    // ================================================================= refusal

    @Test
    void anOrdinaryTenantIsRefused() {
        when(access.requireGlobalAdmin(STAFF_USER_ID))
                .thenThrow(new AccessDeniedException("Current global Staff Admin authority is required"));

        assertThrows(AccessDeniedException.class, () -> authority.require(authenticate("ROLE_TENANT", STAFF_USER_ID)));
    }

    @Test
    void anAnonymousCallerIsRefused() {
        Authentication anonymous = new AnonymousAuthenticationToken("key", "anonymousUser",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
        authenticate(anonymous);

        assertThrows(AccessDeniedException.class, () -> authority.require(anonymous));
        assertThrows(AccessDeniedException.class, () -> authority.require(authentication("ROLE_ADMIN", null)));
        assertThrows(AccessDeniedException.class, () -> authority.require(null));
        verifyNoInteractions(access);
    }

    @Test
    void aCredentialWithoutAStableUserIdentityIsRefused() {
        assertThrows(AccessDeniedException.class, () -> authority.require(authenticate("ROLE_TENANT", null)));
        assertThrows(AccessDeniedException.class, () -> authority.require(authenticate("ROLE_TENANT", 0L)));
        verifyNoInteractions(access);
    }

    @Test
    void everyLiveStaffRefusalIsHonouredForEveryRefusedAuthorityShape() {
        // A sub-administrator, an inactive staff profile, a revoked, not-yet-effective or expired grant,
        // and every narrower CITY_TEAM_ADMIN / OPS_* capability are one answer: StaffAccessService refuses
        // and the entry decision refuses with it. No capability substitutes for the global one.
        for (String authorityName : List.of("ROLE_SUB_ADMIN", "ROLE_TENANT", "ROLE_LANDLORD", "ROLE_GROUND_BOY")) {
            for (String refusal : List.of("Active staff profile is required",
                    "Current global Staff Admin authority is required")) {
                StaffAccessService refusing = mock(StaffAccessService.class);
                when(refusing.requireGlobalAdmin(STAFF_USER_ID)).thenThrow(new AccessDeniedException(refusal));

                assertThrows(AccessDeniedException.class,
                        () -> new ListingReviewAuthority(refusing)
                                .require(authenticate(authorityName, STAFF_USER_ID)),
                        authorityName + " / " + refusal);
                verify(refusing).requireGlobalAdmin(STAFF_USER_ID);
            }
        }
    }

    // ================================================================= request-bound admission

    @Test
    void aCredentialTheCallerSuppliesThatIsNotTheAuthenticatedRequestIsRefused() {
        // A caller that builds, borrows or keeps a credential and hands it to the component: the decision
        // is taken for the credential that is actually authenticated on this thread, and for no other.
        authenticate("ROLE_TENANT", 99L);

        Authentication borrowedAdministrator = authentication("ROLE_ADMIN", null);
        assertThrows(AccessDeniedException.class, () -> authority.require(borrowedAdministrator));
        assertThrows(AccessDeniedException.class, () -> authority.requireLegacyAdministrator(borrowedAdministrator));
        // ... and an equal-looking credential is still not this request's credential.
        Authentication equallyShaped = authentication("ROLE_TENANT", 99L);
        assertNotSame(equallyShaped, SecurityContextHolder.getContext().getAuthentication());
        assertThrows(AccessDeniedException.class, () -> authority.require(equallyShaped));
        verifyNoInteractions(access);
    }

    @Test
    void aBorrowedLiveStaffCredentialIsRefusedAtAdmissionWithoutAnyStaffDecision() {
        authenticate("ROLE_TENANT", 99L);

        assertThrows(AccessDeniedException.class,
                () -> authority.require(authentication("ROLE_TENANT", STAFF_USER_ID)));
        verifyNoInteractions(access);
    }

    @Test
    void admissionIsRefusedOutsideAnAuthenticatedRequest() {
        SecurityContextHolder.clearContext();

        assertThrows(AccessDeniedException.class, () -> authority.require(authentication("ROLE_ADMIN", null)));
        assertThrows(AccessDeniedException.class, () -> authority.requireLegacyAdministrator(authentication("ROLE_ADMIN", null)));
        assertThrows(AccessDeniedException.class, () -> authority.require(null));
        verifyNoInteractions(access);
    }

    // ================================================================= revalidation under the guard

    @Test
    void revalidationReDecidesTheStaffAuthorityFromLiveFacts() {
        when(access.requireGlobalAdmin(STAFF_USER_ID)).thenReturn(new User());
        Reviewer reviewer = authority.require(authenticate("ROLE_TENANT", STAFF_USER_ID));

        authority.revalidate(reviewer);
        verify(access, times(2)).requireGlobalAdmin(STAFF_USER_ID);

        reset(access);
        when(access.requireGlobalAdmin(STAFF_USER_ID))
                .thenThrow(new AccessDeniedException("Current global Staff Admin authority is required"));
        assertThrows(AccessDeniedException.class, () -> authority.revalidate(reviewer));
    }

    @Test
    void revalidatingALegacyAdminReDecidesThePrincipalAndNeverConsultsTheStaffDatabase() {
        Reviewer reviewer = authority.require(authenticate("ROLE_ADMIN", null));

        authority.revalidate(reviewer);

        verifyNoInteractions(access);
        assertThrows(AccessDeniedException.class, () -> authority.revalidate(null));
    }

    @Test
    void theLegacyReDecisionIsReDecidedFromTheLiveCredentialNotRemembered() {
        // The credential's own authority is what the verdict re-consults, not the verdict's shape: the
        // moment the authenticated credential stops carrying ROLE_ADMIN, the guarded decision refuses.
        Authentication credential = mock(Authentication.class);
        when(credential.isAuthenticated()).thenReturn(true);
        doReturn(List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))).when(credential).getAuthorities();
        authenticate(credential);
        Reviewer reviewer = authority.require(credential);

        doReturn(List.of(new SimpleGrantedAuthority("ROLE_TENANT"))).when(credential).getAuthorities();

        assertThrows(AccessDeniedException.class, () -> authority.revalidate(reviewer));
        assertThrows(AccessDeniedException.class, () -> authority.revalidateLegacyAdministrator(reviewer));
    }

    // ================================================================= forged verdicts

    @Test
    void noCallerCanConstructOrAssertAReviewerVerdict() throws Exception {
        // The bypass this closes was `new Reviewer(null, true)`: a public record constructor let any caller
        // mint a trusted-looking verdict without ever presenting a principal. No accessible shape may remain.
        assertTrue(Modifier.isFinal(Reviewer.class.getModifiers()),
                "a verdict must not be subclassable");
        for (Constructor<?> constructor : Reviewer.class.getDeclaredConstructors()) {
            assertTrue(Modifier.isPrivate(constructor.getModifiers()),
                    "every verdict constructor must be private: " + constructor);
        }
        for (Method method : Reviewer.class.getDeclaredMethods()) {
            if (Modifier.isStatic(method.getModifiers()) && Reviewer.class.equals(method.getReturnType())) {
                assertTrue(Modifier.isPrivate(method.getModifiers()),
                        "every verdict factory must be private: " + method);
            }
        }
        Constructor<Reviewer> forged = Reviewer.class.getDeclaredConstructor(Authentication.class, Long.class,
                boolean.class);
        assertThrows(IllegalAccessException.class, () -> forged.newInstance(authentication("ROLE_ADMIN", null),
                null, true),
                "the exact forged-reviewer expression of the original bypass must be unexpressible");
    }

    @Test
    void aStaffVerdictCannotBeReplayedForTheSameStaffUsersNextCredential() {
        // The same stable User identity, a fresh credential: the verdict was decided for one request and
        // is refused in the next, even though the database facts behind it are unchanged.
        when(access.requireGlobalAdmin(STAFF_USER_ID)).thenReturn(new User());
        Authentication firstRequest = authenticate("ROLE_TENANT", STAFF_USER_ID);
        Reviewer verdict = authority.require(firstRequest);
        Authentication nextRequest = authenticate("ROLE_TENANT", STAFF_USER_ID);
        assertNotSame(firstRequest, nextRequest);

        assertThrows(AccessDeniedException.class, () -> authority.revalidate(verdict));
        verify(access, times(1)).requireGlobalAdmin(STAFF_USER_ID);
    }

    @Test
    void aStaffVerdictCannotBeReplayedForAnotherPrincipal() {
        when(access.requireGlobalAdmin(STAFF_USER_ID)).thenReturn(new User());
        Reviewer verdict = authority.require(authenticate("ROLE_TENANT", STAFF_USER_ID));
        authenticate("ROLE_TENANT", 99L);

        assertThrows(AccessDeniedException.class, () -> authority.revalidate(verdict));
        verify(access, times(1)).requireGlobalAdmin(STAFF_USER_ID);
    }

    @Test
    void aLegacyAdministratorVerdictCannotBeReplayedForAnotherLegacyAdministrator() {
        // Two administrators whose credentials carry exactly the same authority - the legacy rule alone
        // would let the second one inherit the first one's verdict.
        Reviewer verdict = authority.require(authenticate("ROLE_ADMIN", null));
        authenticate("ROLE_ADMIN", null);

        assertThrows(AccessDeniedException.class, () -> authority.revalidate(verdict));
        assertThrows(AccessDeniedException.class, () -> authority.revalidateLegacyAdministrator(verdict));
        verifyNoInteractions(access);
    }

    @Test
    void aVerdictIsRefusedOutsideTheRequestItWasMintedFor() {
        when(access.requireGlobalAdmin(STAFF_USER_ID)).thenReturn(new User());
        Reviewer staffVerdict = authority.require(authenticate("ROLE_TENANT", STAFF_USER_ID));
        Reviewer legacyVerdict = authority.require(authenticate("ROLE_ADMIN", null));
        SecurityContextHolder.clearContext();

        assertThrows(AccessDeniedException.class, () -> authority.revalidate(staffVerdict));
        assertThrows(AccessDeniedException.class, () -> authority.revalidate(legacyVerdict));
        assertThrows(AccessDeniedException.class, () -> authority.revalidateLegacyAdministrator(legacyVerdict));
        verify(access, times(1)).requireGlobalAdmin(STAFF_USER_ID);
    }

    @Test
    void aForgedAdministratorCredentialYieldsNoWorkingVerdict() {
        // A caller that builds its own `ROLE_ADMIN` credential - the object-shaped form of the original
        // bypass - cannot even take the entry decision with it while a tenant is the authenticated caller.
        authenticate("ROLE_TENANT", 99L);

        assertThrows(AccessDeniedException.class, () -> authority.require(authentication("ROLE_ADMIN", null)));
        assertThrows(AccessDeniedException.class,
                () -> authority.requireLegacyAdministrator(authentication("ROLE_ADMIN", null)));
        verifyNoInteractions(access);
    }

    // ================================================================= legacy-only decisions

    @Test
    void onlyTheLegacyAdministratorAuthorityReachesTheNonExtendedOperations() {
        when(access.requireGlobalAdmin(STAFF_USER_ID)).thenReturn(new User());

        Reviewer legacy = authority.requireLegacyAdministrator(authenticate("ROLE_ADMIN", null));
        assertTrue(legacy.legacyAdmin());
        assertNull(legacy.userId());
        assertThrows(AccessDeniedException.class,
                () -> authority.requireLegacyAdministrator(authenticate("ROLE_TENANT", STAFF_USER_ID)));
        assertThrows(AccessDeniedException.class, () -> authority.requireLegacyAdministrator(null));
        verifyNoInteractions(access);
    }

    @Test
    void theLegacyOnlyGuardedReDecisionRefusesALiveStaffVerdictAndAcceptsTheLegacyOne() {
        when(access.requireGlobalAdmin(STAFF_USER_ID)).thenReturn(new User());
        Reviewer staffVerdict = authority.require(authenticate("ROLE_TENANT", STAFF_USER_ID));

        // The staff verdict is genuine, current and backed by a live global grant, and still cannot reach
        // an operation that is not part of the extension.
        assertThrows(AccessDeniedException.class, () -> authority.revalidateLegacyAdministrator(staffVerdict));
        assertThrows(AccessDeniedException.class, () -> authority.revalidateLegacyAdministrator(null));

        Reviewer legacyVerdict = authority.requireLegacyAdministrator(authenticate("ROLE_ADMIN", null));
        authority.revalidateLegacyAdministrator(legacyVerdict);
        verify(access, times(1)).requireGlobalAdmin(STAFF_USER_ID);
    }

    @Test
    void theLegacyOnlyGuardedReDecisionRefusesALegacyVerdictOnceAnotherRequestIsCurrent() {
        Reviewer legacyVerdict = authority.requireLegacyAdministrator(authenticate("ROLE_ADMIN", null));
        authenticate("ROLE_ADMIN", null);

        assertThrows(AccessDeniedException.class, () -> authority.revalidateLegacyAdministrator(legacyVerdict));
    }

    @Test
    void aCredentialCarryingRoleAdminIsALegacyAdministratorEvenWhenItAlsoNamesAStaffUser() {
        // The narrow legacy operation is decided from the credential's own authority, not from any staff
        // identity it may carry: a credential with ROLE_ADMIN is the legacy administrator and keeps the
        // legacy behaviour it always had, with no staff decision taken for it.
        Reviewer verdict = authority.require(authenticate("ROLE_ADMIN", STAFF_USER_ID));
        assertTrue(verdict.legacyAdmin());

        authority.revalidateLegacyAdministrator(verdict);
        verifyNoInteractions(access);
    }

    // ================================================================= ordered revocation evidence

    @Test
    void aStaffVerdictHoldsTheLiveAuthorityEvidenceForTheTransaction() {
        when(access.requireGlobalAdmin(STAFF_USER_ID)).thenReturn(new User());
        Reviewer verdict = authority.require(authenticate("ROLE_TENANT", STAFF_USER_ID));

        authority.holdAuthorityEvidence(verdict);

        verify(access).holdGlobalAdminEvidence(STAFF_USER_ID);
    }

    @Test
    void aLegacyAdministratorHoldsNothingBecauseNothingInTheDatabaseBacksTheDecision() {
        Reviewer verdict = authority.requireLegacyAdministrator(authenticate("ROLE_ADMIN", 4242L));

        authority.holdAuthorityEvidence(verdict);

        verifyNoInteractions(access);
    }

    @Test
    void noEvidenceIsHeldWithoutAVerdict() {
        assertThrows(AccessDeniedException.class, () -> authority.holdAuthorityEvidence(null));
        verifyNoInteractions(access);
    }

    // ================================================================= attributable acting User

    @Test
    void theActingUserIsResolvedFromTheVerifiedIdentityOfTheCredential() {
        when(access.requireGlobalAdmin(STAFF_USER_ID)).thenReturn(new User());
        User resolved = new User();
        when(access.requireAttributableUser(STAFF_USER_ID)).thenReturn(resolved);
        Reviewer staffVerdict = authority.require(authenticate("ROLE_TENANT", STAFF_USER_ID));
        assertSame(resolved, authority.attributableActor(staffVerdict));

        reset(access);
        User administrator = new User();
        when(access.requireAttributableUser(4242L)).thenReturn(administrator);
        Reviewer legacyVerdict = authority.requireLegacyAdministrator(authenticate("ROLE_ADMIN", 4242L));
        assertSame(administrator, authority.attributableActor(legacyVerdict));

        // Both principal classes are attributed the same way: from the identity their own credential
        // carries, resolved against the database, never from a name or a caller-supplied claim.
        verify(access).requireAttributableUser(4242L);
    }

    @Test
    void noActingUserIsResolvedForAVerdictFromAnotherRequest() {
        Reviewer verdict = authority.requireLegacyAdministrator(authenticate("ROLE_ADMIN", 4242L));
        authenticate("ROLE_ADMIN", 4242L);

        assertThrows(AccessDeniedException.class, () -> authority.attributableActor(verdict));
        assertThrows(AccessDeniedException.class, () -> authority.attributableActor(null));
        verifyNoInteractions(access);
    }

    @Test
    void anUnattributableIdentityIsRefusedRatherThanRecorded() {
        when(access.requireAttributableUser(null))
                .thenThrow(new AccessDeniedException("An attributable staff or administrator identity is required"));
        Reviewer verdict = authority.requireLegacyAdministrator(authenticate("ROLE_ADMIN", null));

        assertThrows(AccessDeniedException.class, () -> authority.attributableActor(verdict));
    }

    private static void authenticate(Authentication authentication) {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }

    /** Installs the credential as the authenticated request on this thread and returns that very object. */
    private static Authentication authenticate(String authority, Long userId) {
        Authentication credential = authentication(authority, userId);
        authenticate(credential);
        return credential;
    }

    private static Authentication authentication(String authority, Long userId) {
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(
                "codex.lessor.review@pathome.test", null, List.of(new SimpleGrantedAuthority(authority)));
        token.setDetails(new PathomeAuthenticationDetails(new MockHttpServletRequest(), userId));
        return token;
    }
}
