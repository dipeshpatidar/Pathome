package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.*;
import com.indore.pathome.spaces.repository.LessorProfileRepository;
import com.indore.pathome.spaces.repository.SystemNotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class LessorWorkflowNotificationServiceTest {

    private SystemNotificationRepository notificationRepository;
    private LessorProfileRepository lessorProfileRepository;
    private LessorWorkflowNotificationService notificationService;

    private static final Long LINKED_USER_ID = 42L;
    private static final Long UNRELATED_USER_ID = 99L;
    private static final Long PROFILE_ID = 7L;
    private static final Long LISTING_ID = 101L;

    @BeforeEach
    public void setUp() {
        notificationRepository = mock(SystemNotificationRepository.class);
        lessorProfileRepository = mock(LessorProfileRepository.class);
        notificationService = new LessorWorkflowNotificationService(notificationRepository, lessorProfileRepository);

        LessorProfile profile = new LessorProfile();
        profile.setId(PROFILE_ID);
        profile.setLinkedUserId(LINKED_USER_ID);
        when(lessorProfileRepository.findById(PROFILE_ID)).thenReturn(Optional.of(profile));
        when(lessorProfileRepository.findByLinkedUserId(LINKED_USER_ID)).thenReturn(Optional.of(profile));

        when(notificationRepository.save(any(SystemNotification.class))).thenAnswer(inv -> {
            SystemNotification n = inv.getArgument(0);
            n.setId(1L);
            return n;
        });
    }

    private RentalDetails createListing(ListingWorkflowStatus status) {
        RentalDetails listing = new RentalDetails();
        listing.setId(LISTING_ID);
        listing.setTitle("3BHK flat in Bapat");
        listing.setLessorProfileId(PROFILE_ID);
        listing.setOwnerUserId(LINKED_USER_ID);
        listing.setWorkflowStatus(status);
        listing.setVersion(1L);
        return listing;
    }

    @Test
    @DisplayName("1. Correct lessor receives workflow notification with canonical ownership")
    public void testCorrectLessorReceivesNotification() {
        RentalDetails listing = createListing(ListingWorkflowStatus.SUBMITTED);

        Optional<SystemNotification> result = notificationService.notifyPropertySubmitted(listing);

        assertTrue(result.isPresent());
        ArgumentCaptor<SystemNotification> captor = ArgumentCaptor.forClass(SystemNotification.class);
        verify(notificationRepository).save(captor.capture());

        SystemNotification saved = captor.getValue();
        assertEquals(String.valueOf(LINKED_USER_ID), saved.getRecipientUserId());
        assertNotEquals(String.valueOf(UNRELATED_USER_ID), saved.getRecipientUserId());
        assertEquals("Property submitted", saved.getTitle());
        assertEquals("3BHK flat in Bapat was submitted for review.", saved.getMessage());
        assertEquals("/lessor/listings/101", saved.getActionTarget());
        assertEquals("VIEW_PROPERTY", saved.getActionType());
        assertEquals(LISTING_ID, saved.getListingId());
    }

    @Test
    @DisplayName("2. No linked user on LessorProfile is handled safely without failure")
    public void testNoLinkedUserHandledSafely() {
        LessorProfile unlinkedProfile = new LessorProfile();
        unlinkedProfile.setId(88L);
        unlinkedProfile.setLinkedUserId(null); // Field team / unlinked profile
        when(lessorProfileRepository.findById(88L)).thenReturn(Optional.of(unlinkedProfile));

        RentalDetails listing = new RentalDetails();
        listing.setId(202L);
        listing.setTitle("Penthouse in Vijay Nagar");
        listing.setLessorProfileId(88L);
        listing.setWorkflowStatus(ListingWorkflowStatus.SUBMITTED);

        Optional<SystemNotification> result = notificationService.notifyPropertySubmitted(listing);

        // Safe skip: returns empty, does not throw, does not save notification
        assertTrue(result.isEmpty());
        verify(notificationRepository, never()).save(any());
    }

    @Test
    @DisplayName("3. Duplicate or retried event does not duplicate notification")
    public void testDuplicateEventDoesNotDuplicate() {
        RentalDetails listing = createListing(ListingWorkflowStatus.UNDER_REVIEW);
        String expectedKey = "REVIEW_STARTED:101:1";

        SystemNotification existing = new SystemNotification();
        existing.setId(55L);
        existing.setEventKey(expectedKey);

        when(notificationRepository.existsByEventKey(expectedKey)).thenReturn(true);
        when(notificationRepository.findByEventKey(expectedKey)).thenReturn(Optional.of(existing));

        Optional<SystemNotification> result = notificationService.notifyReviewStarted(listing);

        assertTrue(result.isPresent());
        assertEquals(55L, result.get().getId());
        // Verify save was NOT called again because dedupe key existed
        verify(notificationRepository, never()).save(any());
    }

    @Test
    @DisplayName("4. Changes required notification contains 'Updates needed' and 'Review changes' action")
    public void testChangesRequiredNotification() {
        RentalDetails listing = createListing(ListingWorkflowStatus.CHANGES_REQUIRED);

        Optional<SystemNotification> result = notificationService.notifyChangesRequired(listing);

        assertTrue(result.isPresent());
        SystemNotification notif = result.get();
        assertEquals("Updates needed", notif.getTitle());
        assertEquals("Updates are needed before 3BHK flat in Bapat can be published.", notif.getMessage());
        assertEquals("REVIEW_CHANGES", notif.getActionType());
        assertEquals("/lessor/listings/101", notif.getActionTarget());
        assertEquals("warning", notif.getType());
    }

    @Test
    @DisplayName("5. Property published notification contains 'Your property is live'")
    public void testPropertyPublishedNotification() {
        RentalDetails listing = createListing(ListingWorkflowStatus.PUBLISHED);

        Optional<SystemNotification> result = notificationService.notifyPropertyPublished(listing);

        assertTrue(result.isPresent());
        SystemNotification notif = result.get();
        assertEquals("Your property is live", notif.getTitle());
        assertEquals("3BHK flat in Bapat has been approved and is now visible to renters.", notif.getMessage());
        assertEquals("VIEW_PROPERTY", notif.getActionType());
    }

    @Test
    @DisplayName("6. Published listing with pending revision truthfully states live listing remains visible")
    public void testPublishedListingWithPendingRevisionTruthfulness() {
        RentalDetails listing = createListing(ListingWorkflowStatus.PUBLISHED);

        Optional<SystemNotification> result = notificationService.notifyRevisionSubmitted(listing, "draft-rev-1");

        assertTrue(result.isPresent());
        SystemNotification notif = result.get();
        assertEquals("Changes submitted", notif.getTitle());
        assertEquals("Your recent changes to 3BHK flat in Bapat were submitted for review. Your live listing remains visible.",
                notif.getMessage());
        assertEquals("draft-rev-1", notif.getRevisionId());
    }

    @Test
    @DisplayName("7. Paused listing with pending revision does NOT receive false live-listing copy")
    public void testPausedListingWithPendingRevisionTruthfulness() {
        RentalDetails listing = createListing(ListingWorkflowStatus.PAUSED);

        Optional<SystemNotification> result = notificationService.notifyRevisionSubmitted(listing, "draft-rev-2");

        assertTrue(result.isPresent());
        SystemNotification notif = result.get();
        assertEquals("Changes submitted", notif.getTitle());
        // Must NOT claim that live listing remains visible
        assertEquals("Your recent changes to 3BHK flat in Bapat were submitted for review.", notif.getMessage());
        assertFalse(notif.getMessage().contains("live listing remains visible"));
    }

    @Test
    @DisplayName("8. Revision changes required contains 'Changes need attention' and 'Review changes' action")
    public void testRevisionChangesRequiredNotification() {
        RentalDetails listing = createListing(ListingWorkflowStatus.PUBLISHED);
        PropertyUploadDraft draft = new PropertyUploadDraft();
        draft.setDraftId("draft-rev-9");
        draft.setVersion(2);

        Optional<SystemNotification> result = notificationService.notifyRevisionChangesRequired(listing, draft);

        assertTrue(result.isPresent());
        SystemNotification notif = result.get();
        assertEquals("Changes need attention", notif.getTitle());
        assertEquals("Updates are needed before your recent changes to 3BHK flat in Bapat can be published.", notif.getMessage());
        assertEquals("REVIEW_CHANGES", notif.getActionType());
        assertEquals("draft-rev-9", notif.getRevisionId());
    }

    @Test
    @DisplayName("9. Revision published contains 'Changes published' and live message")
    public void testRevisionPublishedNotification() {
        RentalDetails listing = createListing(ListingWorkflowStatus.PUBLISHED);

        Optional<SystemNotification> result = notificationService.notifyRevisionPublished(listing, "draft-rev-approved");

        assertTrue(result.isPresent());
        SystemNotification notif = result.get();
        assertEquals("Changes published", notif.getTitle());
        assertEquals("Your approved changes to 3BHK flat in Bapat are now live.", notif.getMessage());
    }
}
