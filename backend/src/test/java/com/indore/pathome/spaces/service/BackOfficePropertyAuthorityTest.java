package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.EmployeeProfile;
import com.indore.pathome.spaces.entity.Listing;
import com.indore.pathome.spaces.entity.PropertyUploadDraft;
import com.indore.pathome.spaces.entity.StaffAccessGrant;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.repository.EmployeeProfileRepository;
import com.indore.pathome.spaces.repository.ListingRepository;
import com.indore.pathome.spaces.repository.PropertyUploadDraftRepository;
import com.indore.pathome.spaces.repository.StaffAccessGrantRepository;
import com.indore.pathome.spaces.repository.UserRepository;
import com.indore.pathome.spaces.security.PathomeAuthenticationDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class BackOfficePropertyAuthorityTest {
    private static final Long USER_ID = 42L;
    private static final String EMAIL = "admin@pathome.test";

    private final UserRepository users = mock(UserRepository.class);
    private final EmployeeProfileRepository profiles = mock(EmployeeProfileRepository.class);
    private final StaffAccessGrantRepository grants = mock(StaffAccessGrantRepository.class);
    private final PropertyUploadDraftRepository drafts = mock(PropertyUploadDraftRepository.class);
    private final ListingRepository listings = mock(ListingRepository.class);
    private final BackOfficePropertyAuthority authority =
            new BackOfficePropertyAuthority(users, profiles, grants, drafts, listings,
                    mock(PlatformTransactionManager.class));

    @BeforeEach
    void currentAdminFacts() {
        when(users.findRoleValueById(USER_ID)).thenReturn(Optional.of("ROLE_ADMIN"));
        when(users.findEmailValueById(USER_ID)).thenReturn(Optional.of(EMAIL));
        when(profiles.existsByUserIdAndStaffActiveTrue(USER_ID)).thenReturn(true);
        when(grants.hasEffectiveGlobalAdmin(USER_ID)).thenReturn(true);
        authenticate("ROLE_ADMIN", EMAIL, USER_ID);
    }

    @AfterEach
    void clearPrincipal() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void activeProvisionedAdminRetainsUploaderAndAdministrativeActions() {
        assertEquals("ROLE_ADMIN", authority.require(
                BackOfficePropertyAuthority.Action.SINGLE_PARSE).persistedRole());
        assertEquals("ROLE_ADMIN", authority.require(
                BackOfficePropertyAuthority.Action.MANUAL_PROPERTY).persistedRole());
        assertEquals("ROLE_ADMIN", authority.require(
                BackOfficePropertyAuthority.Action.FAILED_UPLOAD).persistedRole());
    }

    @Test
    void deactivatedProfileDeniesARetainedValidAdminCredential() {
        when(profiles.existsByUserIdAndStaffActiveTrue(USER_ID)).thenReturn(false);

        assertThrows(AccessDeniedException.class, () -> authority.require(
                BackOfficePropertyAuthority.Action.SINGLE_PARSE));
    }

    @Test
    void persistedRoleDemotionDeniesAnUnexpiredAdminCredential() {
        when(users.findRoleValueById(USER_ID)).thenReturn(Optional.of("ROLE_TENANT"));

        assertThrows(AccessDeniedException.class, () -> authority.require(
                BackOfficePropertyAuthority.Action.SINGLE_CREATE));
    }

    @Test
    void revokedOrAbsentGlobalGrantDeniesLegacyAdministrativeActions() {
        when(grants.hasEffectiveGlobalAdmin(USER_ID)).thenReturn(false);

        assertThrows(AccessDeniedException.class, () -> authority.require(
                BackOfficePropertyAuthority.Action.LEGACY_REVIEW));
    }

    @Test
    void narrowingSubAdminPolicyAllowsOnlyUploaderAndOwnDraftActions() {
        when(users.findRoleValueById(USER_ID)).thenReturn(Optional.of("ROLE_SUB_ADMIN"));
        authenticate("ROLE_SUB_ADMIN", EMAIL, USER_ID);

        assertEquals("ROLE_SUB_ADMIN", authority.require(
                BackOfficePropertyAuthority.Action.SINGLE_PARSE).persistedRole());
        assertEquals("ROLE_SUB_ADMIN", authority.require(
                BackOfficePropertyAuthority.Action.BATCH_CREATE).persistedRole());
        assertEquals("ROLE_SUB_ADMIN", authority.require(
                BackOfficePropertyAuthority.Action.OWN_DRAFT).persistedRole());
        assertThrows(AccessDeniedException.class, () -> authority.require(
                BackOfficePropertyAuthority.Action.MANUAL_PROPERTY));
        assertThrows(AccessDeniedException.class, () -> authority.require(
                BackOfficePropertyAuthority.Action.FAILED_UPLOAD));
        assertThrows(AccessDeniedException.class, () -> authority.require(
                BackOfficePropertyAuthority.Action.LEGACY_REVIEW));
    }

    @Test
    void grantOnlyStaffAdministratorDoesNotReceiveUploaderAuthority() {
        when(users.findRoleValueById(USER_ID)).thenReturn(Optional.of("ROLE_TENANT"));
        authenticate("ROLE_TENANT", EMAIL, USER_ID);

        assertThrows(AccessDeniedException.class, () -> authority.require(
                BackOfficePropertyAuthority.Action.SINGLE_PARSE));
    }

    @Test
    void credentialEmailMustMatchTheFreshPersistedUserIdentity() {
        authenticate("ROLE_ADMIN", "old-email@pathome.test", USER_ID);

        assertThrows(AccessDeniedException.class, () -> authority.require(
                BackOfficePropertyAuthority.Action.SINGLE_PARSE));
    }

    @Test
    void subAdminMediaRequiresProvenOwnershipOfTheOriginDraft() {
        when(users.findRoleValueById(USER_ID)).thenReturn(Optional.of("ROLE_SUB_ADMIN"));
        authenticate("ROLE_SUB_ADMIN", EMAIL, USER_ID);
        Listing ownListing = listingWithOrigin("draft-owned:card-1");
        PropertyUploadDraft ownDraft = new PropertyUploadDraft();
        ownDraft.setDraftId("draft-owned");
        ownDraft.setAdminId(EMAIL);
        when(drafts.findByDraftId("draft-owned")).thenReturn(Optional.of(ownDraft));

        assertDoesNotThrow(() -> authority.requireUploaderMedia(ownListing));

        Listing foreignListing = listingWithOrigin("draft-foreign:card-1");
        PropertyUploadDraft foreignDraft = new PropertyUploadDraft();
        foreignDraft.setDraftId("draft-foreign");
        foreignDraft.setAdminId("someone-else@pathome.test");
        when(drafts.findByDraftId("draft-foreign")).thenReturn(Optional.of(foreignDraft));
        assertThrows(AccessDeniedException.class, () -> authority.requireUploaderMedia(foreignListing));
    }

    @Test
    void guardedMutationUsesProfileGrantUserThenFreshScalarOrder() {
        EmployeeProfile profile = mock(EmployeeProfile.class);
        when(profile.isStaffActive()).thenReturn(true);
        when(profiles.findSharedByUserId(USER_ID)).thenReturn(Optional.of(profile));
        when(grants.findEffectiveGlobalAdminSharedByUserId(USER_ID))
                .thenReturn(List.of(mock(StaffAccessGrant.class)));
        when(users.findSharedById(USER_ID)).thenReturn(Optional.of(new User()));

        authority.requireForMutation(BackOfficePropertyAuthority.Action.SINGLE_CREATE, null);

        InOrder order = inOrder(profiles, grants, users);
        order.verify(profiles).findSharedByUserId(USER_ID);
        order.verify(grants).findEffectiveGlobalAdminSharedByUserId(USER_ID);
        order.verify(users).findSharedById(USER_ID);
        order.verify(users, atLeastOnce()).findRoleValueById(USER_ID);
    }

    @Test
    void missingStableIdentityFailsClosed() {
        Authentication credential = new UsernamePasswordAuthenticationToken(
                EMAIL, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(credential);

        assertThrows(AccessDeniedException.class, () -> authority.require(
                BackOfficePropertyAuthority.Action.SINGLE_PARSE));
        verify(users, never()).findRoleValueById(anyLong());
    }

    @Test
    void deletedPersistedUserFailsClosedWithARetainedCredential() {
        when(users.findRoleValueById(USER_ID)).thenReturn(Optional.empty());
        when(users.findEmailValueById(USER_ID)).thenReturn(Optional.empty());

        assertThrows(AccessDeniedException.class, () -> authority.require(
                BackOfficePropertyAuthority.Action.SINGLE_PARSE));
    }

    private static Listing listingWithOrigin(String originDraftId) {
        Listing listing = new com.indore.pathome.spaces.entity.RentalDetails();
        listing.setId(77L);
        listing.setOriginDraftId(originDraftId);
        return listing;
    }

    private static void authenticate(String role, String email, Long userId) {
        UsernamePasswordAuthenticationToken credential = new UsernamePasswordAuthenticationToken(
                email, null, List.of(new SimpleGrantedAuthority(role)));
        credential.setDetails(new PathomeAuthenticationDetails(new MockHttpServletRequest(), userId));
        SecurityContextHolder.getContext().setAuthentication(credential);
    }
}
