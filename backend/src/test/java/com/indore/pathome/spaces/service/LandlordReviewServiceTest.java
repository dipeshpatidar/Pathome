package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.lessor.LandlordDraftData;
import com.indore.pathome.spaces.entity.*;
import com.indore.pathome.spaces.exception.DraftConflictException;
import com.indore.pathome.spaces.repository.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LandlordReviewServiceTest {
    private final ListingRepository listings = mock(ListingRepository.class);
    private final PropertyUploadDraftRepository drafts = mock(PropertyUploadDraftRepository.class);
    private final PropertyDraftMediaRepository media = mock(PropertyDraftMediaRepository.class);
    private final LandlordDraftService draftService = mock(LandlordDraftService.class);
    private final LandlordSubmissionService submissions = mock(LandlordSubmissionService.class);
    private final LandlordLocationService locations = mock(LandlordLocationService.class);
    private final LandlordReviewService service = new LandlordReviewService(listings, drafts, media,
            draftService, submissions, locations, new ListingWorkflowService(), mock(PropertyMediaAssetRepository.class));

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
        assertThrows(DraftConflictException.class, () -> service.approveRevision("revision-1"));
        verify(submissions, never()).applyRevision(any(), any(), anyList(), any());
        live.setVersion(3L);
        var approved = service.approveRevision("revision-1");
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
                () -> service.requestRevisionChanges("revision-2", "short"));
        var changed = service.requestRevisionChanges("revision-2", "Please clarify the available date.");
        assertEquals("CHANGES_REQUIRED", changed.status());
        assertEquals("Please clarify the available date.", draft.getReviewNote());
    }
}
