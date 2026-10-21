package com.indore.pathome.spaces.security;

import com.indore.pathome.spaces.config.SecurityConfig;
import com.indore.pathome.spaces.controller.LandlordReviewController;
import com.indore.pathome.spaces.entity.ListingWorkflowStatus;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.service.ListingReviewAuthority;
import com.indore.pathome.spaces.service.ListingReviewAuthority.Reviewer;
import com.indore.pathome.spaces.service.LandlordReviewService;
import com.indore.pathome.spaces.service.StaffAccessService;
import com.indore.pathome.spaces.service.BackOfficePropertyAuthority;
import com.indore.pathome.spaces.security.PathomeAuthenticationDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * The listing-review request matchers plus the {@link ListingReviewAuthority} entry decision.
 *
 * <p>The authority component is the real one backed by a mocked {@link StaffAccessService}, so the
 * admitted/refused verdicts here are produced by the same code the application runs. The service itself is
 * mocked, so "no service call" is provable: a caller that is refused before the controller method is
 * invoked leaves the review service untouched. The verdict handed to the service is captured rather than
 * asserted by value, because only the authority can mint one - which is exactly the property under test.
 */
@WebMvcTest(controllers = LandlordReviewController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, ListingReviewAuthority.class})
class LandlordReviewSecurityTest {
    @Autowired private MockMvc mockMvc;
    @MockBean private LandlordReviewService reviews;
    @MockBean private StaffAccessService staffAccess;
    @MockBean private BackOfficePropertyAuthority propertyAuthority;
    @MockBean private OAuth2AuthenticationSuccessHandler oAuth2SuccessHandler;
    @MockBean private JwtUtils jwtUtils;

    private static final Long STAFF_USER_ID = 42L;
    private static final Long ADMIN_USER_ID = 4242L;
    private static final String APPROVED_QUEUE = "/api/v1/admin/lessor-review/listings";
    private static final String APPROVED_START = "/api/v1/admin/lessor-review/listings/1/start-review";
    private static final String NON_APPROVED_CHANGES = "/api/v1/admin/lessor-review/listings/1/request-changes";
    private static final String NON_APPROVED_REVISION = "/api/v1/admin/lessor-review/revisions/draft-1/approve";

    @BeforeEach
    void admitProvisionedLegacyAdministrator() {
        when(propertyAuthority.require(eq(BackOfficePropertyAuthority.Action.LEGACY_REVIEW),
                any(Authentication.class))).thenAnswer(call -> {
            Authentication credential = call.getArgument(1);
            return new BackOfficePropertyAuthority.Actor(
                    credential, ADMIN_USER_ID, "admin@pathome.test", "ROLE_ADMIN");
        });
    }

    // ================================================================= anonymous

    @Test
    void anonymousCannotReadTheReviewQueues() throws Exception {
        mockMvc.perform(get("/api/v1/admin/lessor-review/revisions")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(APPROVED_QUEUE)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin/lessor-review/listings/1")).andExpect(status().isUnauthorized());
        verifyNoInteractions(reviews);
    }

    @Test
    void anonymousCannotStartReviewLinkALocalityOrApprove() throws Exception {
        mockMvc.perform(post(APPROVED_START)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/admin/lessor-review/listings/1/link-locality")
                .contentType("application/json").content("{\"canonicalLocalityId\":9}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/admin/lessor-review/listings/1/approve")).andExpect(status().isUnauthorized());
        verifyNoInteractions(reviews);
    }

    // ================================================================= legacy admin (preserved)

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminMayOpenBothReviewQueuesAndTheirDetails() throws Exception {
        mockMvc.perform(get("/api/v1/admin/lessor-review/revisions")
                .with(authentication(adminAuthentication()))).andExpect(status().isOk());
        mockMvc.perform(get(APPROVED_QUEUE)
                .with(authentication(adminAuthentication()))).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/lessor-review/revisions/draft-1")
                .with(authentication(adminAuthentication()))).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/lessor-review/listings/1")
                .with(authentication(adminAuthentication()))).andExpect(status().isOk());

        ArgumentCaptor<Reviewer> verdicts = ArgumentCaptor.forClass(Reviewer.class);
        verify(reviews).revisions(eq(0), verdicts.capture());
        verify(reviews).listings(eq(0), verdicts.capture());
        verify(reviews).revisionDetail(eq("draft-1"), verdicts.capture());
        verify(reviews).listingDetail(eq(1L), verdicts.capture());
        assertTrue(verdicts.getAllValues().stream().allMatch(Reviewer::legacyAdmin));
        assertTrue(verdicts.getAllValues().stream().allMatch(verdict -> ADMIN_USER_ID.equals(verdict.userId())));
        verifyNoInteractions(staffAccess);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminMayStillStartReviewLinkALocalityAndApprove() throws Exception {
        mockMvc.perform(post(APPROVED_START)
                .with(authentication(adminAuthentication()))).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/admin/lessor-review/listings/1/link-locality")
                .with(authentication(adminAuthentication()))
                .contentType("application/json").content("{\"canonicalLocalityId\":9}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/admin/lessor-review/listings/1/approve")
                .with(authentication(adminAuthentication()))).andExpect(status().isOk());

        ArgumentCaptor<Reviewer> verdicts = ArgumentCaptor.forClass(Reviewer.class);
        verify(reviews, times(2)).decideListing(eq(1L), any(ListingWorkflowStatus.class), isNull(),
                verdicts.capture());
        verify(reviews).linkLocality(eq(1L), eq(9L), verdicts.capture());
        assertTrue(verdicts.getAllValues().stream().allMatch(Reviewer::legacyAdmin));
        verifyNoInteractions(staffAccess);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminKeepsTheNonApprovedOperations() throws Exception {
        mockMvc.perform(post(NON_APPROVED_REVISION)
                .with(authentication(adminAuthentication()))).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/admin/lessor-review/revisions/draft-1/request-changes")
                .with(authentication(adminAuthentication()))
                .contentType("application/json").content("{\"note\":\"Please clarify the available date.\"}"))
                .andExpect(status().isOk());
        ArgumentCaptor<Reviewer> verdicts = ArgumentCaptor.forClass(Reviewer.class);
        verify(reviews).approveRevision(eq("draft-1"), verdicts.capture());
        verify(reviews).requestRevisionChanges(eq("draft-1"), eq("Please clarify the available date."),
                verdicts.capture());
        assertTrue(verdicts.getAllValues().stream().allMatch(Reviewer::legacyAdmin));
        verifyNoInteractions(staffAccess);
    }

    // ================================================================= refused principals

    @Test
    @WithMockUser(roles = "TENANT")
    void aLandlordCannotReadTheReviewQueues() throws Exception {
        mockMvc.perform(get(APPROVED_QUEUE)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/lessor-review/revisions")).andExpect(status().isForbidden());
        verifyNoInteractions(reviews);
    }

    @Test
    @WithMockUser(roles = "SUB_ADMIN")
    void aSubAdministratorCannotReachTheReviewQueues() throws Exception {
        mockMvc.perform(get(APPROVED_QUEUE)).andExpect(status().isForbidden());
        mockMvc.perform(post(APPROVED_START)).andExpect(status().isForbidden());
        verifyNoInteractions(reviews);
    }

    @Test
    void aStaffCallerWithoutTheGlobalGrantIsRefused() throws Exception {
        when(staffAccess.requireGlobalAdmin(STAFF_USER_ID))
                .thenThrow(new AccessDeniedException("Current global Staff Admin authority is required"));

        mockMvc.perform(get(APPROVED_QUEUE).with(authentication(staffAuthentication(STAFF_USER_ID))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(APPROVED_START).with(authentication(staffAuthentication(STAFF_USER_ID))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(reviews);
    }

    @Test
    @WithMockUser(roles = "TENANT")
    void landlordCannotApproveOwnRevision() throws Exception {
        mockMvc.perform(post(NON_APPROVED_REVISION)).andExpect(status().isForbidden());
        verifyNoInteractions(reviews);
    }

    // ================================================================= global staff admin

    @Test
    void aGlobalStaffAdminIsAdmittedToExactlyTheFiveApprovedHttpRoutes() throws Exception {
        when(staffAccess.requireGlobalAdmin(STAFF_USER_ID)).thenReturn(new User());

        mockMvc.perform(get(APPROVED_QUEUE).with(authentication(staffAuthentication(STAFF_USER_ID))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/lessor-review/listings/1").with(
                authentication(staffAuthentication(STAFF_USER_ID)))).andExpect(status().isOk());
        mockMvc.perform(post(APPROVED_START).with(authentication(staffAuthentication(STAFF_USER_ID))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/admin/lessor-review/listings/1/link-locality")
                .with(authentication(staffAuthentication(STAFF_USER_ID)))
                .contentType("application/json").content("{\"canonicalLocalityId\":9}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/admin/lessor-review/listings/1/approve")
                .with(authentication(staffAuthentication(STAFF_USER_ID)))).andExpect(status().isOk());

        ArgumentCaptor<Reviewer> verdicts = ArgumentCaptor.forClass(Reviewer.class);
        verify(reviews).listings(eq(0), verdicts.capture());
        verify(reviews).listingDetail(eq(1L), verdicts.capture());
        verify(reviews, times(2)).decideListing(eq(1L), any(ListingWorkflowStatus.class), isNull(),
                verdicts.capture());
        verify(reviews).linkLocality(eq(1L), eq(9L), verdicts.capture());
        assertTrue(verdicts.getAllValues().stream().allMatch(verdict -> !verdict.legacyAdmin()));
        assertTrue(verdicts.getAllValues().stream()
                .allMatch(verdict -> STAFF_USER_ID.equals(verdict.userId())));
        verify(staffAccess, times(5)).requireGlobalAdmin(STAFF_USER_ID);
        mockMvc.perform(get("/api/v1/admin/lessor-review/revisions").with(
                authentication(staffAuthentication(STAFF_USER_ID)))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/lessor-review/revisions/draft-1").with(
                authentication(staffAuthentication(STAFF_USER_ID)))).andExpect(status().isForbidden());
    }

    @Test
    void aGlobalStaffAdminIsStillRefusedOnTheNonApprovedReviewOperations() throws Exception {
        when(staffAccess.requireGlobalAdmin(STAFF_USER_ID)).thenReturn(new User());

        mockMvc.perform(post(NON_APPROVED_CHANGES)
                .with(authentication(staffAuthentication(STAFF_USER_ID)))
                .contentType("application/json").content("{\"note\":\"Please clarify the available date.\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(NON_APPROVED_REVISION).with(authentication(staffAuthentication(STAFF_USER_ID))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(reviews);
    }

    @Test
    void aGlobalStaffAdminGainsNoGeneralAdministrativeAccess() throws Exception {
        when(staffAccess.requireGlobalAdmin(STAFF_USER_ID)).thenReturn(new User());

        mockMvc.perform(get("/api/v1/admin/drafts").with(authentication(staffAuthentication(STAFF_USER_ID))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/failed-uploads").with(
                authentication(staffAuthentication(STAFF_USER_ID)))).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/drafts").with(authentication(staffAuthentication(STAFF_USER_ID))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(reviews);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void recognizedTimeoutsUseTheEstablished503ContractOnEveryReviewMutation() throws Exception {
        for (SQLException cause : List.of(
                new SQLException("canceling statement due to lock timeout", "55P03"),
                new SQLException("canceling statement due to statement timeout", "57014"))) {
            RuntimeException failure = new org.springframework.transaction.TransactionSystemException(
                    "wrapped transaction completion failure", cause);
            doThrow(failure).when(reviews).decideListing(any(), any(), any(), any());
            doThrow(failure).when(reviews).linkLocality(any(), any(), any());
            doThrow(failure).when(reviews).approveRevision(any(), any());
            doThrow(failure).when(reviews).requestRevisionChanges(any(), any(), any());
            for (String path : List.of(APPROVED_START,
                    "/api/v1/admin/lessor-review/listings/1/approve",
                    "/api/v1/admin/lessor-review/listings/1/link-locality", NON_APPROVED_CHANGES,
                    NON_APPROVED_REVISION, "/api/v1/admin/lessor-review/revisions/draft-1/request-changes")) {
                mockMvc.perform(post(path).with(authentication(adminAuthentication()))
                                .contentType("application/json")
                                .content("{\"canonicalLocalityId\":9,\"note\":\"Please clarify the available date.\"}"))
                        .andExpect(status().isServiceUnavailable())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.status").value(503))
                        .andExpect(jsonPath("$.error").value("Service Unavailable"))
                        .andExpect(jsonPath("$.message").value(
                                "The operation could not be completed because the database was busy. Please try again shortly."))
                        .andExpect(jsonPath("$.path").value(path))
                        .andExpect(jsonPath("$.timestamp").exists());
            }
        }
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void cancellationsAndUnrelatedDatabaseFailuresAreNotReviewTimeouts() throws Exception {
        for (SQLException cause : List.of(
                new SQLException("canceling statement due to user request", "57014"),
                new SQLException("duplicate key timeout wording is not authoritative", "23505"),
                new SQLException("deadlock detected", "40P01"),
                new SQLException("serialization failure", "40001"),
                new SQLException("unrelated audit failure", "P0001"))) {
            // This wrapper also represents user cancellation in Hibernate's PostgreSQL dialect.
            doThrow(new org.springframework.dao.QueryTimeoutException("wrapper", cause))
                    .when(reviews).decideListing(any(), any(), any(), any());
            mockMvc.perform(post(APPROVED_START)
                            .with(authentication(adminAuthentication())))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.message").value(
                            "We could not complete this request right now. Please try again shortly."));
        }
        doThrow(new IllegalArgumentException("Invalid review input"))
                .when(reviews).decideListing(any(), any(), any(), any());
        mockMvc.perform(post(APPROVED_START)
                .with(authentication(adminAuthentication()))).andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void reviewTimeoutTranslationDoesNotExpandToReadsOrOtherPaths() throws Exception {
        RuntimeException failure = new org.springframework.dao.CannotAcquireLockException("wrapper",
                new SQLException("canceling statement due to lock timeout", "55P03"));
        when(reviews.listings(anyInt(), any())).thenThrow(failure);
        mockMvc.perform(get(APPROVED_QUEUE)
                .with(authentication(adminAuthentication()))).andExpect(status().isInternalServerError());
        var request = new MockHttpServletRequest("POST", "/api/v1/properties/1/photos");
        var handler = new com.indore.pathome.spaces.exception.GlobalExceptionHandler();
        org.junit.jupiter.api.Assertions.assertEquals(500,
                handler.handleGenericException(failure, request).getStatusCode().value());
    }

    private static Authentication staffAuthentication(Long userId) {
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(
                "codex.lessor.review.staff@pathome.test", null,
                List.of(new SimpleGrantedAuthority("ROLE_TENANT")));
        token.setDetails(new PathomeAuthenticationDetails(new MockHttpServletRequest(), userId));
        return token;
    }

    private static Authentication adminAuthentication() {
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(
                "admin@pathome.test", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        token.setDetails(new PathomeAuthenticationDetails(new MockHttpServletRequest(), ADMIN_USER_ID));
        return token;
    }
}
