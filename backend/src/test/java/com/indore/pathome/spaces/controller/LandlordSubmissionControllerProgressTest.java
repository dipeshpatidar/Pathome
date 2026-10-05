package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.service.LandlordSubmissionProgressService;
import com.indore.pathome.spaces.service.LandlordSubmissionService;
import com.indore.pathome.spaces.dto.lessor.LandlordSubmission;
import com.indore.pathome.spaces.entity.ListingWorkflowStatus;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class LandlordSubmissionControllerProgressTest {
    @Test
    void progressReadOnlyDelegatesToOwnerScopedObserverAndDoesNotSubmit() {
        LandlordSubmissionService submissions = mock(LandlordSubmissionService.class);
        LandlordSubmissionProgressService progress = mock(LandlordSubmissionProgressService.class);
        Authentication authentication = mock(Authentication.class);
        when(authentication.getName()).thenReturn("owner@example.com");
        LandlordSubmissionController controller = new LandlordSubmissionController(submissions, progress);

        controller.getProgress(authentication, "draft-1");

        verify(progress).get("owner@example.com", "draft-1");
        verifyNoInteractions(submissions);
    }

    @Test
    void progressReachesCompletedOnlyAfterTheTransactionalSubmitReturns() {
        LandlordSubmissionService submissions = mock(LandlordSubmissionService.class);
        LandlordSubmissionProgressService progress = mock(LandlordSubmissionProgressService.class);
        Authentication authentication = mock(Authentication.class);
        when(authentication.getName()).thenReturn("owner@example.com");
        when(progress.beginSaving("owner@example.com", "draft-1")).thenReturn(7L);
        LandlordSubmission expected = new LandlordSubmission(42L, "draft-1", "Home",
                ListingWorkflowStatus.SUBMITTED, LocalDateTime.now());
        when(submissions.submit("owner@example.com", "draft-1")).thenReturn(expected);
        LandlordSubmissionController controller = new LandlordSubmissionController(submissions, progress);

        assertSame(expected, controller.submit(authentication, "draft-1"));

        var ordered = inOrder(progress, submissions);
        ordered.verify(progress).beginSaving("owner@example.com", "draft-1");
        ordered.verify(submissions).submit("owner@example.com", "draft-1");
        ordered.verify(progress).complete(7L, "draft-1");
        verify(progress, never()).fail(anyLong(), anyString());
    }

    @Test
    void failedTransactionalSubmitMarksFailureAndPreservesTheOriginalError() {
        LandlordSubmissionService submissions = mock(LandlordSubmissionService.class);
        LandlordSubmissionProgressService progress = mock(LandlordSubmissionProgressService.class);
        Authentication authentication = mock(Authentication.class);
        when(authentication.getName()).thenReturn("owner@example.com");
        when(progress.beginSaving("owner@example.com", "draft-1")).thenReturn(7L);
        IllegalStateException expected = new IllegalStateException("submission failed");
        when(submissions.submit("owner@example.com", "draft-1")).thenThrow(expected);
        LandlordSubmissionController controller = new LandlordSubmissionController(submissions, progress);

        assertSame(expected, assertThrows(IllegalStateException.class,
                () -> controller.submit(authentication, "draft-1")));
        verify(progress).fail(7L, "draft-1");
    }
}
