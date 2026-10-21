package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.lessor.LandlordDraftData;
import com.indore.pathome.spaces.entity.*;
import com.indore.pathome.spaces.exception.DraftConflictException;
import com.indore.pathome.spaces.repository.*;
import com.indore.pathome.spaces.security.PathomeAuthenticationDetails;
import com.indore.pathome.spaces.service.ListingReviewAuthority.Reviewer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LandlordReviewServiceTest {
    private static final Long STAFF_USER_ID = 7L;
    private static final Long LEGACY_ADMIN_USER_ID = 4242L;

    private final ListingRepository listings = mock(ListingRepository.class);
    private final PropertyUploadDraftRepository drafts = mock(PropertyUploadDraftRepository.class);
    private final PropertyDraftMediaRepository media = mock(PropertyDraftMediaRepository.class);
    private final LandlordDraftService draftService = mock(LandlordDraftService.class);
    private final LandlordSubmissionService submissions = mock(LandlordSubmissionService.class);
    private final LandlordLocationService locations = mock(LandlordLocationService.class);
    private final StaffAccessService access = mock(StaffAccessService.class);
    private final BackOfficePropertyAuthority propertyAuthority = mock(BackOfficePropertyAuthority.class);
    private final OperationalAuditService audit = mock(OperationalAuditService.class);
    private final ListingReviewAuthority reviewAuthority = spy(
            new ListingReviewAuthority(access, propertyAuthority));
    private final User attributableActor = new User();
    private final LandlordReviewService service = new LandlordReviewService(listings, drafts, media,
            draftService, submissions, locations, new ListingWorkflowService(),
            mock(PropertyMediaAssetRepository.class), reviewAuthority, audit, mock(TransactionalCommandBounds.class));

    @BeforeEach
    void admitLegacyAdministrator() {
        when(propertyAuthority.require(eq(BackOfficePropertyAuthority.Action.LEGACY_REVIEW),
                any(Authentication.class))).thenAnswer(call -> {
            Authentication credential = call.getArgument(1);
            Long userId = credential.getDetails() instanceof PathomeAuthenticationDetails details
                    ? details.getUserId() : LEGACY_ADMIN_USER_ID;
            return new BackOfficePropertyAuthority.Actor(
                    credential, userId == null ? LEGACY_ADMIN_USER_ID : userId,
                    "admin@pathome.test", "ROLE_ADMIN");
        });
    }

    @AfterEach
    void clearTheRequestPrincipal() {
        SecurityContextHolder.clearContext();
    }


    @Test
    void revisionApprovalRequiresCurrentVersionAndKeepsLivePublicationState() {
        PropertyUploadDraft draft = new PropertyUploadDraft();
        draft.setDraftId("revision-1");
        draft.setLandlordUserId(5L);
        draft.setPublishedPropertyId(42L);
        draft.setRevisionBaseVersion(3L);
        draft.setStatus("REVIEW");
        when(drafts.findByDraftIdForUpdate("revision-1")).thenReturn(Optional.of(draft));
        RentalDetails live = new RentalDetails();
        live.setId(42L);
        live.setOwnerUserId(5L);
        live.setVersion(3L);
        live.setWorkflowStatus(ListingWorkflowStatus.PUBLISHED);
        live.setStatus(ListingStatus.ACTIVE);
        when(listings.lockOwnedId(42L, 5L)).thenReturn(Optional.of(42L));
        when(listings.findByIdAndOwnerUserId(42L, 5L)).thenReturn(Optional.of(live));
        when(listings.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        var data = new LandlordDraftData(
                new LandlordDraftData.Basics(PropertyType.FLAT, RentalMode.LONG_TERM_RENTAL, "2BHK"),
                new LandlordDraftData.Pricing(new BigDecimal("20000"), BigDecimal.ZERO),
                new LandlordDraftData.Location("Indore", 10L, "Vijay Nagar", "Private road", ""),
                new LandlordDraftData.Details(LocalDate.now(), "", null, null, null, "", ""));
        when(draftService.readData(draft)).thenReturn(data);
        when(media.findByDraftIdAndLandlordUserIdOrderBySortOrderAscIdAsc("revision-1", 5L))
                .thenReturn(List.of());
        Locality locality = new Locality();
        locality.setId(10L);
        when(locations.requireMatchingLocality("Indore", 10L)).thenReturn(locality);

        live.setVersion(4L);
        assertThrows(DraftConflictException.class,
                () -> service.approveRevision("revision-1", legacyAdministrator()));
        verify(submissions, never()).applyRevision(any(), any(), anyList(), any());
        live.setVersion(3L);
        var approved = service.approveRevision("revision-1", legacyAdministrator());
        assertEquals(ListingWorkflowStatus.PUBLISHED, approved.status());
        assertEquals(ListingStatus.ACTIVE, live.getStatus());
        assertEquals("APPROVED", draft.getStatus());
        verify(submissions).applyRevision(eq(live), eq(data), anyList(), eq(locality));
    }

    @Test
    void revisionRejectionRequiresActionableNoteAndCanBeReopened() {
        PropertyUploadDraft draft = new PropertyUploadDraft();
        draft.setDraftId("revision-2");
        draft.setLandlordUserId(5L);
        draft.setPublishedPropertyId(42L);
        draft.setStatus("REVIEW");
        when(drafts.findByDraftIdForUpdate("revision-2")).thenReturn(Optional.of(draft));
        when(drafts.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        assertThrows(IllegalArgumentException.class,
                () -> service.requestRevisionChanges("revision-2", "short", legacyAdministrator()));
        var changed = service.requestRevisionChanges("revision-2",
                "Please clarify the available date.", legacyAdministrator());
        assertEquals("CHANGES_REQUIRED", changed.status());
        assertEquals("Please clarify the available date.", draft.getReviewNote());
    }

    @Test
    void adminMustLinkSameCityCanonicalLocalityBeforePublishing() {
        RentalDetails listing = new RentalDetails();
        listing.setId(55L);
        listing.setOwnerUserId(5L);
        listing.setCity("Indore");
        listing.setSector("Rani Pura");
        listing.setTitle("2BHK flat in Rani Pura");
        listing.setWorkflowStatus(ListingWorkflowStatus.UNDER_REVIEW);
        listing.setStatus(ListingStatus.PENDING);
        listing.setLocationResolution(LocationResolution.MANUAL_PENDING);
        listing.setRentalMode(RentalMode.LONG_TERM_RENTAL);
        when(listings.lockLandlordReviewId(55L)).thenReturn(Optional.of(55L));
        when(listings.findById(55L)).thenReturn(Optional.of(listing));
        when(listings.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        Reviewer admin = legacyAdministrator();
        assertThrows(IllegalStateException.class,
                () -> service.decideListing(55L, ListingWorkflowStatus.PUBLISHED, null, admin));
        assertEquals(ListingStatus.PENDING, listing.getStatus());
        when(locations.requireMatchingLocality("Indore", 90L))
                .thenThrow(new IllegalArgumentException("City mismatch"));
        assertThrows(IllegalArgumentException.class, () -> service.linkLocality(55L, 90L, admin));
        assertNull(listing.getCanonicalLocalityId());
        Locality canonical = new Locality();
        canonical.setId(91L);
        canonical.setCity("Indore");
        canonical.setSectorName("Ranipura");
        when(locations.requireMatchingLocality("Indore", 91L)).thenReturn(canonical);
        service.linkLocality(55L, 91L, admin);
        assertEquals(91L, listing.getCanonicalLocalityId());
        assertEquals(LocationResolution.CANONICAL, listing.getLocationResolution());
        assertEquals("Ranipura", listing.getSector());
        service.decideListing(55L, ListingWorkflowStatus.PUBLISHED, null, admin);
        assertEquals(ListingStatus.ACTIVE, listing.getStatus());
    }

    @Test
    void relinkingToADifferentCanonicalLocalityIsRefusedAndChangesNothing() {
        RentalDetails listing = reviewableListing(81L, "Indore");
        listing.setCanonicalLocalityId(91L);
        listing.setWorkflowStatus(ListingWorkflowStatus.UNDER_REVIEW);
        when(listings.lockLandlordReviewId(81L)).thenReturn(Optional.of(81L));
        when(listings.findById(81L)).thenReturn(Optional.of(listing));
        when(listings.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        Locality other = new Locality();
        other.setId(92L);
        other.setCity("Indore");
        other.setSectorName("Palasia");
        when(locations.requireMatchingLocality("Indore", 92L)).thenReturn(other);

        DraftConflictException refused = assertThrows(DraftConflictException.class,
                () -> service.linkLocality(81L, 92L, legacyAdministrator()));

        assertEquals("81", refused.getDraftId());
        assertEquals(91L, listing.getCanonicalLocalityId(), "the existing link must survive the refusal");
        assertEquals("Rani Pura", listing.getSector());
        assertEquals(LocationResolution.MANUAL_PENDING, listing.getLocationResolution());
        verify(listings, never()).saveAndFlush(any());
        verifyNoInteractions(media);

        // The documented retry - the very same locality that is already linked - still succeeds.
        Locality same = new Locality();
        same.setId(91L);
        same.setCity("Indore");
        same.setSectorName("Ranipura");
        when(locations.requireMatchingLocality("Indore", 91L)).thenReturn(same);
        var linked = service.linkLocality(81L, 91L, legacyAdministrator());
        assertEquals(ListingWorkflowStatus.UNDER_REVIEW, linked.status());
        assertEquals(91L, listing.getCanonicalLocalityId());
        assertEquals("Ranipura", listing.getSector());
        verify(listings).saveAndFlush(listing);
    }

    // ================================================ listing-review authority extension (staff path)

    @Test
    void everyReviewReadReDecidesTheAuthorityAndRefusesBeforeTouchingReviewData() {
        Reviewer verdict = staffAdministrator();
        doThrow(new AccessDeniedException("Current global Staff Admin authority is required"))
                .when(access).requireGlobalAdmin(STAFF_USER_ID);

        assertThrows(AccessDeniedException.class, () -> service.listings(0, verdict));
        assertThrows(AccessDeniedException.class, () -> service.revisions(0, verdict));
        assertThrows(AccessDeniedException.class, () -> service.listingDetail(55L, verdict));
        assertThrows(AccessDeniedException.class, () -> service.revisionDetail("revision-1", verdict));

        verifyNoInteractions(listings, drafts, media);
        verify(access, times(3)).requireGlobalAdmin(STAFF_USER_ID);
    }

    @Test
    void aReviewDecisionReDecidesTheAuthorityAfterTheListingRowLock() {
        Reviewer verdict = staffAdministrator();
        RentalDetails listing = reviewableListing(77L, "Indore");
        listing.setWorkflowStatus(ListingWorkflowStatus.SUBMITTED);
        when(listings.lockLandlordReviewId(77L)).thenReturn(Optional.of(77L));
        when(listings.findById(77L)).thenReturn(Optional.of(listing));
        when(listings.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));

        var decision = service.decideListing(77L, ListingWorkflowStatus.UNDER_REVIEW, null, verdict);

        assertEquals(ListingWorkflowStatus.UNDER_REVIEW, decision.status());
        InOrder guarded = inOrder(listings, reviewAuthority);
        guarded.verify(listings).lockLandlordReviewId(77L);
        guarded.verify(reviewAuthority).revalidate(verdict);
        guarded.verify(listings).findById(77L);
        guarded.verify(listings).saveAndFlush(any());
    }

    @Test
    void canonicalLocalityLinkingReDecidesTheAuthorityAfterTheListingRowLock() {
        Reviewer verdict = staffAdministrator();
        RentalDetails listing = reviewableListing(80L, "Indore");
        when(listings.lockLandlordReviewId(80L)).thenReturn(Optional.of(80L));
        when(listings.findById(80L)).thenReturn(Optional.of(listing));
        when(listings.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        Locality canonical = new Locality();
        canonical.setId(91L);
        canonical.setCity("Indore");
        canonical.setSectorName("Ranipura");
        when(locations.requireMatchingLocality("Indore", 91L)).thenReturn(canonical);

        service.linkLocality(80L, 91L, verdict);

        assertEquals(91L, listing.getCanonicalLocalityId());
        InOrder guarded = inOrder(listings, reviewAuthority, locations);
        guarded.verify(listings).lockLandlordReviewId(80L);
        guarded.verify(reviewAuthority).revalidate(verdict);
        guarded.verify(listings).findById(80L);
        guarded.verify(locations).requireMatchingLocality("Indore", 91L);
    }

    @Test
    void aGrantRevokedBeforeTheLockStopsTheMutationBeforeAnythingIsFlushed() {
        Reviewer verdict = staffAdministrator();
        RentalDetails listing = reviewableListing(78L, "Indore");
        when(listings.lockLandlordReviewId(78L)).thenReturn(Optional.of(78L));
        doThrow(new AccessDeniedException("Current global Staff Admin authority is required"))
                .when(access).requireGlobalAdmin(STAFF_USER_ID);

        assertThrows(AccessDeniedException.class, () -> service.linkLocality(78L, 91L, verdict));
        assertThrows(AccessDeniedException.class,
                () -> service.decideListing(78L, ListingWorkflowStatus.PUBLISHED, null, verdict));

        verify(listings, never()).saveAndFlush(any());
        assertNull(listing.getCanonicalLocalityId());
        assertEquals(ListingStatus.PENDING, listing.getStatus());
        assertNull(listing.getPublishedAt());
    }

    // ============================================ the legacy-only operations at the service boundary

    /**
     * The direct-service bypass of the three {@code ROLE_ADMIN}-only operations. A caller holding a live
     * {@code STAFF_ADMIN@GLOBAL} grant can obtain a genuine, current, correctly minted staff verdict and
     * hand it straight to the service - and the service must refuse it exactly as the HTTP rule does,
     * because requesting listing changes and the two revision decisions are not part of the extension.
     */
    @Test
    void aLiveStaffVerdictCannotDriveTheThreeLegacyOnlyOperationsThroughTheServiceBoundary() {
        Reviewer staff = staffAdministrator();
        RentalDetails listing = reviewableListing(84L, "Indore");
        when(listings.lockLandlordReviewId(84L)).thenReturn(Optional.of(84L));
        when(listings.findById(84L)).thenReturn(Optional.of(listing));
        PropertyUploadDraft draft = new PropertyUploadDraft();
        draft.setDraftId("revision-staff");
        draft.setLandlordUserId(5L);
        draft.setPublishedPropertyId(42L);
        draft.setStatus("REVIEW");
        when(drafts.findByDraftIdForUpdate("revision-staff")).thenReturn(Optional.of(draft));
        when(listings.lockOwnedId(42L, 5L)).thenReturn(Optional.of(42L));

        assertThrows(AccessDeniedException.class, () -> service.decideListing(84L,
                ListingWorkflowStatus.CHANGES_REQUIRED, "Please clarify the available date.", staff));
        assertThrows(AccessDeniedException.class, () -> service.approveRevision("revision-staff", staff));
        assertThrows(AccessDeniedException.class, () -> service.requestRevisionChanges("revision-staff",
                "Please clarify the available date.", staff));

        assertEquals(ListingWorkflowStatus.UNDER_REVIEW, listing.getWorkflowStatus());
        assertNull(listing.getReviewNote());
        assertEquals("REVIEW", draft.getStatus());
        assertNull(draft.getReviewNote());
        verify(listings, never()).saveAndFlush(any());
        verify(drafts, never()).saveAndFlush(any());
        verify(submissions, never()).applyRevision(any(), any(), anyList(), any());
        verifyNoInteractions(audit);
    }

    // ==================================== ordered revocation and durable audit (C and D at the seam)

    /**
     * The ordering guarantee at the seam: the actor's authority evidence is held before the Listing lock
     * that comes after it in the established guard order, and the audit row is written in the same
     * transaction, after the mutation it describes.
     */
    @Test
    void theExtendedDecisionHoldsTheAuthorityEvidenceBeforeTheListingLockAndAuditsTheOutcome() {
        Reviewer verdict = staffAdministrator();
        RentalDetails listing = reviewableListing(86L, "Indore");
        listing.setWorkflowStatus(ListingWorkflowStatus.SUBMITTED);
        when(listings.lockLandlordReviewId(86L)).thenReturn(Optional.of(86L));
        when(listings.findById(86L)).thenReturn(Optional.of(listing));
        when(listings.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));

        service.decideListing(86L, ListingWorkflowStatus.UNDER_REVIEW, null, verdict);

        InOrder guarded = inOrder(access, listings, reviewAuthority, audit);
        guarded.verify(access).holdGlobalAdminEvidence(STAFF_USER_ID);
        guarded.verify(listings).lockLandlordReviewId(86L);
        guarded.verify(reviewAuthority).revalidate(verdict);
        guarded.verify(listings).findById(86L);
        guarded.verify(listings).saveAndFlush(any());
        guarded.verify(audit).recordUserEvent(eq(attributableActor), eq("LISTING_REVIEW_STARTED"), eq("LISTING"),
                eq(86L), eq("ADMIN_REVIEW_DECISION"), isNull(), isNull(), anyMap());

        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(audit).recordUserEvent(any(), any(), any(), any(), any(), any(), any(), details.capture());
        assertEquals("SUBMITTED", details.getValue().get("fromStatus"));
        assertEquals("UNDER_REVIEW", details.getValue().get("toStatus"));
    }

    @Test
    void requestingListingChangesHoldsNoStaffEvidenceAndIsAuditedAsItsOwnAction() {
        RentalDetails listing = reviewableListing(87L, "Indore");
        when(listings.lockLandlordReviewId(87L)).thenReturn(Optional.of(87L));
        when(listings.findById(87L)).thenReturn(Optional.of(listing));
        when(listings.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        Reviewer admin = legacyAdministrator();

        service.decideListing(87L, ListingWorkflowStatus.CHANGES_REQUIRED,
                "Please clarify the available date.", admin);

        verify(access, never()).holdGlobalAdminEvidence(anyLong());
        verify(audit).recordUserEvent(eq(attributableActor), eq("LISTING_CHANGES_REQUESTED"), eq("LISTING"),
                eq(87L), eq("ADMIN_REVIEW_DECISION"), isNull(), isNull(), anyMap());
    }

    @Test
    void localityLinkingHoldsTheEvidenceFirstAndAuditsTheCanonicalLocalityItLinked() {
        Reviewer verdict = staffAdministrator();
        RentalDetails listing = reviewableListing(88L, "Indore");
        when(listings.lockLandlordReviewId(88L)).thenReturn(Optional.of(88L));
        when(listings.findById(88L)).thenReturn(Optional.of(listing));
        when(listings.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        Locality canonical = new Locality();
        canonical.setId(93L);
        canonical.setCity("Indore");
        canonical.setSectorName("Ranipura");
        when(locations.requireMatchingLocality("Indore", 93L)).thenReturn(canonical);

        service.linkLocality(88L, 93L, verdict);

        InOrder guarded = inOrder(access, listings, reviewAuthority);
        guarded.verify(access).holdGlobalAdminEvidence(STAFF_USER_ID);
        guarded.verify(listings).lockLandlordReviewId(88L);
        guarded.verify(reviewAuthority).revalidate(verdict);
        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(audit).recordUserEvent(eq(attributableActor), eq("LISTING_LOCALITY_LINKED"), eq("LISTING"),
                eq(88L), eq("ADMIN_REVIEW_DECISION"), isNull(), isNull(), details.capture());
        assertEquals(93L, details.getValue().get("canonicalLocalityId"));
        assertFalse(details.getValue().containsKey("previousCanonicalLocalityId"),
                "nothing was linked before, so there is no previous locality to claim");
    }

    @Test
    void theRevisionDecisionIsAuditedAgainstTheDraftRowWithItsStatesAndVersions() {
        Reviewer admin = legacyAdministrator();
        PropertyUploadDraft draft = new PropertyUploadDraft();
        draft.setDraftId("revision-audited");
        draft.setLandlordUserId(5L);
        draft.setPublishedPropertyId(42L);
        draft.setStatus("REVIEW");
        draft.setVersion(3);
        when(drafts.findByDraftIdForUpdate("revision-audited")).thenReturn(Optional.of(draft));
        when(drafts.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));

        service.requestRevisionChanges("revision-audited", "Please clarify the available date.", admin);

        verify(audit).recordUserEvent(eq(attributableActor), eq("REVISION_CHANGES_REQUESTED"),
                eq("PROPERTY_UPLOAD_DRAFT"), any(), eq("ADMIN_REVISION_DECISION"), isNull(), isNull(), anyMap());
        // The numeric draft row id the production evidence carries is asserted over real PostgreSQL; here
        // the row is a mock, so only the shape of the recorded evidence is checked.
        assertFalse(draft.getReviewNote() == null || draft.getReviewNote().isBlank());
    }

    /** The same three operations still do exactly what they always did for a genuine administrator. */
    @Test
    void theThreeLegacyOnlyOperationsStillDecideUnderTheirOwnRowLockForALegacyAdministrator() {
        Reviewer admin = legacyAdministrator();
        RentalDetails listing = reviewableListing(85L, "Indore");
        when(listings.lockLandlordReviewId(85L)).thenReturn(Optional.of(85L));
        when(listings.findById(85L)).thenReturn(Optional.of(listing));
        when(listings.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        PropertyUploadDraft draft = new PropertyUploadDraft();
        draft.setDraftId("revision-admin");
        draft.setLandlordUserId(5L);
        draft.setPublishedPropertyId(42L);
        draft.setStatus("REVIEW");
        draft.setTitleSummary("revision");
        when(drafts.findByDraftIdForUpdate("revision-admin")).thenReturn(Optional.of(draft));
        when(drafts.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));

        var changed = service.decideListing(85L, ListingWorkflowStatus.CHANGES_REQUIRED,
                "Please clarify the available date.", admin);
        var revision = service.requestRevisionChanges("revision-admin",
                "Please clarify the available date.", admin);

        assertEquals(ListingWorkflowStatus.CHANGES_REQUIRED, changed.status());
        assertEquals("Please clarify the available date.", listing.getReviewNote());
        assertEquals("CHANGES_REQUIRED", revision.status());
        assertEquals("Please clarify the available date.", draft.getReviewNote());
        InOrder guarded = inOrder(listings, reviewAuthority);
        guarded.verify(listings).lockLandlordReviewId(85L);
        guarded.verify(reviewAuthority).revalidateLegacyAdministrator(admin);
        guarded.verify(listings).findById(85L);
        verify(reviewAuthority, never()).revalidate(any());
    }

    // ==================================================== request-bound verdicts at the service

    @Test
    void aListingReviewReadRefusesAVerdictFromAnEarlierRequest() {
        Reviewer earlier = legacyAdministrator();
        authenticate(authentication("ROLE_ADMIN", null));

        assertThrows(AccessDeniedException.class, () -> service.listings(0, earlier));
        assertThrows(AccessDeniedException.class, () -> service.revisions(0, earlier));

        verifyNoInteractions(listings, drafts, media);
    }

    @Test
    void aStaffVerdictCannotBeReplayedOnTheNextCredentialOfTheSameStaffUser() {
        Reviewer earlier = staffAdministrator();
        authenticate(authentication("ROLE_TENANT", STAFF_USER_ID));

        assertThrows(AccessDeniedException.class, () -> service.listings(0, earlier));

        verifyNoInteractions(listings, drafts, media);
        verify(access, times(1)).requireGlobalAdmin(STAFF_USER_ID);
    }

    /**
     * The original bypass in its object-shaped form: a caller that supplies its own credential cannot even
     * take the entry decision with it, and a verdict minted for another authenticated request is refused
     * under the guard, with the listing, its locality and its publication state untouched.
     */
    @Test
    void aDirectServiceCallerCannotPublishWithACredentialItBuiltItself() {
        when(access.requireGlobalAdmin(STAFF_USER_ID)).thenReturn(new User());
        RentalDetails listing = reviewableListing(79L, "Indore");
        listing.setCanonicalLocalityId(91L);
        when(listings.lockLandlordReviewId(79L)).thenReturn(Optional.of(79L));
        when(listings.findById(79L)).thenReturn(Optional.of(listing));

        // A verdict legitimately minted in the administrator's own request...
        Reviewer borrowedAdministrator = reviewAuthority.require(
                authenticate("ROLE_ADMIN", LEGACY_ADMIN_USER_ID));
        assertTrue(borrowedAdministrator.legacyAdmin());
        // ... and an equivalent credential the caller wrote for itself, which this request must refuse.
        Authentication selfBuilt = authentication("ROLE_ADMIN", null);
        authenticate(authentication("ROLE_TENANT", 99L));

        assertThrows(AccessDeniedException.class, () -> reviewAuthority.require(selfBuilt));
        assertThrows(AccessDeniedException.class,
                () -> service.decideListing(79L, ListingWorkflowStatus.PUBLISHED, null, borrowedAdministrator));
        assertThrows(AccessDeniedException.class,
                () -> service.linkLocality(79L, 91L, borrowedAdministrator));

        verify(listings, never()).saveAndFlush(any());
        assertNull(listing.getPublishedAt());
        assertEquals(ListingStatus.PENDING, listing.getStatus());
        assertEquals(LocationResolution.MANUAL_PENDING, listing.getLocationResolution());
    }

    // ================================================================= authority harness

    /** The verdict a controller resolves for a caller holding a live global staff grant. */
    private Reviewer staffAdministrator() {
        when(access.requireGlobalAdmin(STAFF_USER_ID)).thenReturn(new User());
        Authentication principal = authentication("ROLE_TENANT", STAFF_USER_ID);
        authenticate(principal);
        Reviewer verdict = reviewAuthority.require(principal);
        when(access.requireAttributableUser(STAFF_USER_ID)).thenReturn(attributableActor);
        return verdict;
    }

    /** The verdict a controller resolves for a legacy administrator's own request. */
    private Reviewer legacyAdministrator() {
        Authentication principal = authentication("ROLE_ADMIN", LEGACY_ADMIN_USER_ID);
        authenticate(principal);
        Reviewer verdict = reviewAuthority.require(principal);
        when(access.requireAttributableUser(LEGACY_ADMIN_USER_ID)).thenReturn(attributableActor);
        return verdict;
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

    private static RentalDetails reviewableListing(long id, String city) {
        RentalDetails listing = new RentalDetails();
        listing.setId(id);
        listing.setOwnerUserId(5L);
        listing.setCity(city);
        listing.setSector("Rani Pura");
        listing.setTitle("2BHK flat in Rani Pura");
        listing.setWorkflowStatus(ListingWorkflowStatus.UNDER_REVIEW);
        listing.setStatus(ListingStatus.PENDING);
        listing.setLocationResolution(LocationResolution.MANUAL_PENDING);
        listing.setRentalMode(RentalMode.LONG_TERM_RENTAL);
        return listing;
    }
}
