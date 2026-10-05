package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.lessor.LandlordPreview;
import com.indore.pathome.spaces.dto.lessor.LandlordSubmission;
import com.indore.pathome.spaces.dto.lessor.LandlordSubmissionProgress;
import com.indore.pathome.spaces.service.LandlordSubmissionService;
import com.indore.pathome.spaces.service.LandlordSubmissionProgressService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/lessor/properties/drafts/{draftId}")
@PreAuthorize("isAuthenticated()")
public class LandlordSubmissionController {
    private final LandlordSubmissionService submissions;
    private final LandlordSubmissionProgressService progress;

    public LandlordSubmissionController(LandlordSubmissionService submissions, LandlordSubmissionProgressService progress) {
        this.submissions = submissions;
        this.progress = progress;
    }

    @GetMapping("/preview")
    public LandlordPreview preview(Authentication auth, @PathVariable String draftId) {
        return submissions.preview(auth.getName(), draftId);
    }

    @PostMapping("/submission-progress")
    public LandlordSubmissionProgress startProgress(Authentication auth, @PathVariable String draftId) {
        return progress.start(auth.getName(), draftId);
    }

    @GetMapping("/submission-progress")
    public LandlordSubmissionProgress getProgress(Authentication auth, @PathVariable String draftId) {
        return progress.get(auth.getName(), draftId);
    }

    @PostMapping("/submit")
    public LandlordSubmission submit(Authentication auth, @PathVariable String draftId) {
        Long ownerId = progress.beginSaving(auth.getName(), draftId);
        try {
            LandlordSubmission result = submissions.submit(auth.getName(), draftId);
            progress.complete(ownerId, draftId);
            return result;
        } catch (RuntimeException failure) {
            try {
                progress.fail(ownerId, draftId);
            } catch (RuntimeException ignored) {
                // Preserve the original submission failure if progress observation is unavailable.
            }
            throw failure;
        }
    }
}
