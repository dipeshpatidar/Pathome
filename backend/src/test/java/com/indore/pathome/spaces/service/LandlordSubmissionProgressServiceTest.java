package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.PropertyDraftMedia;
import com.indore.pathome.spaces.entity.PropertyUploadDraft;
import com.indore.pathome.spaces.repository.PropertyDraftMediaRepository;
import com.indore.pathome.spaces.repository.PropertyUploadDraftRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LandlordSubmissionProgressServiceTest {
    private LandlordCapabilityService capabilities;
    private PropertyUploadDraftRepository drafts;
    private PropertyDraftMediaRepository media;
    private LandlordSubmissionProgressService progress;
    private PropertyUploadDraft draft;
    private List<PropertyDraftMedia> rows;

    @BeforeEach
    void setUp() {
        capabilities = mock(LandlordCapabilityService.class);
        drafts = mock(PropertyUploadDraftRepository.class);
        media = mock(PropertyDraftMediaRepository.class);
        progress = new LandlordSubmissionProgressService(capabilities, drafts, media);
        draft = new PropertyUploadDraft();
        draft.setDraftId("draft-a");
        draft.setStatus("DRAFT");
        draft.setLandlordUserId(7L);
        rows = List.of(media("photo-1", "STAGED"), media("photo-2", "STAGED"),
                media("photo-3", "STAGED"), media("photo-4", "STAGED"));
        when(capabilities.requireOnboardingUserId("a@example.com")).thenReturn(7L);
        when(capabilities.requireOnboardingUserId("b@example.com")).thenReturn(8L);
        when(drafts.findByDraftIdAndLandlordUserId("draft-a", 7L)).thenReturn(Optional.of(draft));
        when(media.findByDraftIdAndLandlordUserIdOrderBySortOrderAscIdAsc("draft-a", 7L)).thenReturn(rows);
    }

    @Test
    void startsAtTruthfulPreparingStateAndAdvancesOnlyAfterEachCompletedMedia() {
        var initial = progress.start("a@example.com", "draft-a");
        assertEquals("draft-a", initial.submissionId());
        assertEquals("PREPARING", initial.status());
        assertEquals(0, initial.percent());
        assertEquals(0, initial.processedMedia());
        assertEquals(4, initial.totalMedia());

        progress.mediaStarted(7L, "draft-a", rows);
        progress.mediaProcessed(7L, "draft-a", "photo-1");
        assertProgress("PROCESSING_MEDIA", 22, 1, 4);
        progress.mediaProcessed(7L, "draft-a", "photo-2");
        assertProgress("PROCESSING_MEDIA", 45, 2, 4);
        progress.mediaProcessed(7L, "draft-a", "photo-3");
        assertProgress("PROCESSING_MEDIA", 67, 3, 4);
        progress.mediaProcessed(7L, "draft-a", "photo-4");
        assertProgress("PROCESSING_MEDIA", 90, 4, 4);
    }

    @Test
    void finalisationCannotReachOneHundredUntilSubmissionReturnsSuccessfully() {
        progress.start("a@example.com", "draft-a");
        progress.mediaStarted(7L, "draft-a", rows);
        for (PropertyDraftMedia row : rows) progress.mediaProcessed(7L, "draft-a", row.getMediaId());

        progress.beginSaving("a@example.com", "draft-a");
        assertProgress("SAVING_PROPERTY", 90, 4, 4);
        progress.complete(7L, "draft-a");
        var completed = progress.get("a@example.com", "draft-a");
        assertEquals("COMPLETED", completed.status());
        assertEquals(100, completed.percent());
        assertEquals(4, completed.processedMedia());
        assertEquals(4, completed.totalMedia());
        assertTrue(completed.completed());
        assertFalse(completed.failed());
    }

    @Test
    void repeatedStartAfterFrontendRemountReturnsTheExistingActiveSubmission() {
        progress.start("a@example.com", "draft-a");
        progress.mediaStarted(7L, "draft-a", rows);
        progress.mediaProcessed(7L, "draft-a", "photo-1");

        var remounted = progress.start("a@example.com", "draft-a");

        assertEquals("PROCESSING_MEDIA", remounted.status());
        assertEquals(22, remounted.percent());
        assertEquals(1, remounted.processedMedia());
        assertEquals(4, remounted.totalMedia());
    }

    @Test
    void aRealFailureIsTerminalAndNeverReportsSuccess() {
        progress.start("a@example.com", "draft-a");
        progress.mediaStarted(7L, "draft-a", rows);
        progress.mediaProcessed(7L, "draft-a", "photo-1");
        progress.fail("a@example.com", "draft-a");

        var failed = progress.get("a@example.com", "draft-a");
        assertEquals("FAILED", failed.status());
        assertEquals(22, failed.percent());
        assertTrue(failed.failed());
        assertFalse(failed.completed());
    }

    @Test
    void anotherAuthenticatedOwnerCannotReadOrStartTheDraftProgress() {
        progress.start("a@example.com", "draft-a");

        assertThrows(EntityNotFoundException.class, () -> progress.get("b@example.com", "draft-a"));
        assertThrows(EntityNotFoundException.class, () -> progress.start("b@example.com", "draft-a"));
        verify(drafts, times(2)).findByDraftIdAndLandlordUserId("draft-a", 8L);
    }

    @Test
    void submittedDraftRemainsAuthoritativelyCompleteAfterEphemeralRecordExpiresOrProcessRestarts() {
        draft.setStatus("SUBMITTED");
        var result = progress.get("a@example.com", "draft-a");

        assertEquals("COMPLETED", result.status());
        assertEquals(100, result.percent());
        assertTrue(result.completed());
        assertFalse(result.failed());
    }

    private void assertProgress(String status, int percent, int processed, int total) {
        var current = progress.get("a@example.com", "draft-a");
        assertEquals(status, current.status());
        assertEquals(percent, current.percent());
        assertEquals(processed, current.processedMedia());
        assertEquals(total, current.totalMedia());
        assertFalse(current.completed());
        assertFalse(current.failed());
    }

    private static PropertyDraftMedia media(String id, String status) {
        PropertyDraftMedia row = new PropertyDraftMedia();
        row.setMediaId(id);
        row.setUploadStatus(status);
        return row;
    }
}
