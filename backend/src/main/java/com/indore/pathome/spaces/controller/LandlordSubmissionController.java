package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.lessor.LandlordPreview;
import com.indore.pathome.spaces.dto.lessor.LandlordSubmission;
import com.indore.pathome.spaces.service.LandlordSubmissionService;
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

    public LandlordSubmissionController(LandlordSubmissionService submissions) { this.submissions = submissions; }

    @GetMapping("/preview")
    public LandlordPreview preview(Authentication auth, @PathVariable String draftId) {
        return submissions.preview(auth.getName(), draftId);
    }

    @PostMapping("/submit")
    public LandlordSubmission submit(Authentication auth, @PathVariable String draftId) {
        return submissions.submit(auth.getName(), draftId);
    }
}
