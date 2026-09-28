package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.ListingStatus;
import com.indore.pathome.spaces.entity.ListingWorkflowStatus;
import com.indore.pathome.spaces.entity.RentalDetails;
import com.indore.pathome.spaces.entity.RentalMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ListingWorkflowServiceTest {
    private final ListingWorkflowService workflows = new ListingWorkflowService();

    @Test
    void newLandlordListingIsPrivateUntilAdminPublishes() {
        RentalDetails listing = new RentalDetails();
        workflows.prepareSubmitted(listing, 4L, 8L);

        assertEquals(4L, listing.getOwnerUserId());
        assertEquals(8L, listing.getCanonicalLocalityId());
        assertEquals(RentalMode.LONG_TERM_RENTAL, listing.getRentalMode());
        assertEquals(ListingWorkflowStatus.SUBMITTED, listing.getWorkflowStatus());
        assertEquals(ListingStatus.PENDING, listing.getStatus());
        assertNotNull(listing.getSubmittedAt());

        workflows.transition(listing, ListingWorkflowStatus.UNDER_REVIEW, ListingWorkflowService.Actor.ADMIN);
        assertEquals(ListingStatus.PENDING, listing.getStatus());
        workflows.transition(listing, ListingWorkflowStatus.PUBLISHED, ListingWorkflowService.Actor.ADMIN);
        assertEquals(ListingStatus.ACTIVE, listing.getStatus());
        assertNotNull(listing.getPublishedAt());
    }

    @Test
    void landlordCannotSelfPublishOrEditReviewState() {
        RentalDetails listing = new RentalDetails();
        workflows.prepareSubmitted(listing, 4L, 8L);
        assertThrows(IllegalStateException.class, () -> workflows.transition(
                listing, ListingWorkflowStatus.PUBLISHED, ListingWorkflowService.Actor.LANDLORD));
        assertEquals(ListingWorkflowStatus.SUBMITTED, listing.getWorkflowStatus());
        assertEquals(ListingStatus.PENDING, listing.getStatus());
    }

    @Test
    void pauseAndArchiveRemovePublicVisibility() {
        RentalDetails listing = new RentalDetails();
        workflows.prepareSubmitted(listing, 4L, 8L);
        workflows.transition(listing, ListingWorkflowStatus.UNDER_REVIEW, ListingWorkflowService.Actor.ADMIN);
        workflows.transition(listing, ListingWorkflowStatus.PUBLISHED, ListingWorkflowService.Actor.ADMIN);
        workflows.transition(listing, ListingWorkflowStatus.PAUSED, ListingWorkflowService.Actor.LANDLORD);
        assertEquals(ListingStatus.CLOSED, listing.getStatus());
        workflows.transition(listing, ListingWorkflowStatus.ARCHIVED, ListingWorkflowService.Actor.LANDLORD);
        assertEquals(ListingStatus.CLOSED, listing.getStatus());
    }

    @Test
    void shortStayCannotBePublishedByThisWorkflow() {
        RentalDetails listing = new RentalDetails();
        workflows.prepareSubmitted(listing, 4L, 8L);
        workflows.transition(listing, ListingWorkflowStatus.UNDER_REVIEW, ListingWorkflowService.Actor.ADMIN);
        listing.setRentalMode(RentalMode.SHORT_STAY);
        assertThrows(IllegalStateException.class, () -> workflows.transition(
                listing, ListingWorkflowStatus.PUBLISHED, ListingWorkflowService.Actor.ADMIN));
        assertEquals(ListingStatus.PENDING, listing.getStatus());
    }

    @Test
    void legacyActiveListingIsNotChangedByNewSubmissionPath() {
        RentalDetails legacy = new RentalDetails();
        assertEquals(ListingStatus.ACTIVE, legacy.getStatus());
        assertThrows(IllegalStateException.class, () -> workflows.transition(
                legacy, ListingWorkflowStatus.PAUSED, ListingWorkflowService.Actor.LANDLORD));
        assertEquals(ListingStatus.ACTIVE, legacy.getStatus());
    }

    @Test
    void unresolvedSubmissionStaysPrivateUntilCanonicalLocalityIsLinked() {
        RentalDetails listing = new RentalDetails();
        workflows.prepareSubmitted(listing, 4L, null);
        workflows.transition(listing, ListingWorkflowStatus.UNDER_REVIEW, ListingWorkflowService.Actor.ADMIN);
        assertThrows(IllegalStateException.class, () -> workflows.transition(
                listing, ListingWorkflowStatus.PUBLISHED, ListingWorkflowService.Actor.ADMIN));
        assertEquals(ListingStatus.PENDING, listing.getStatus());
        listing.setCanonicalLocalityId(8L);
        workflows.transition(listing, ListingWorkflowStatus.PUBLISHED, ListingWorkflowService.Actor.ADMIN);
        assertEquals(ListingStatus.ACTIVE, listing.getStatus());
    }
}
