package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.Listing;
import com.indore.pathome.spaces.entity.ListingStatus;
import com.indore.pathome.spaces.entity.ListingWorkflowStatus;
import com.indore.pathome.spaces.entity.RentalMode;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class ListingWorkflowService {
    public enum Actor { LANDLORD, ADMIN }

    public void prepareSubmitted(Listing listing, Long ownerUserId, Long canonicalLocalityId) {
        if (ownerUserId == null || ownerUserId <= 0 || canonicalLocalityId == null || canonicalLocalityId <= 0) {
            throw new IllegalArgumentException("Landlord and canonical locality are required");
        }
        if (listing.getWorkflowStatus() != null || listing.getOwnerUserId() != null) {
            throw new IllegalStateException("Listing has already entered a workflow");
        }
        listing.setOwnerUserId(ownerUserId);
        listing.setCanonicalLocalityId(canonicalLocalityId);
        listing.setRentalMode(RentalMode.LONG_TERM_RENTAL);
        listing.setWorkflowStatus(ListingWorkflowStatus.SUBMITTED);
        listing.setStatus(ListingStatus.PENDING);
        listing.setSubmittedAt(LocalDateTime.now());
    }

    public void transition(Listing listing, ListingWorkflowStatus target, Actor actor) {
        ListingWorkflowStatus current = listing.getWorkflowStatus();
        if (current == null || target == null || actor == null || !allowed(current, target, actor)) {
            throw new IllegalStateException("Listing workflow transition is not permitted");
        }
        if (target == ListingWorkflowStatus.PUBLISHED && listing.getOwnerUserId() != null
                && listing.getRentalMode() != RentalMode.LONG_TERM_RENTAL) {
            throw new IllegalStateException("Only long-term rentals may be published in this workflow");
        }
        listing.setWorkflowStatus(target);
        listing.setStatus(target == ListingWorkflowStatus.PUBLISHED ? ListingStatus.ACTIVE
                : target == ListingWorkflowStatus.PAUSED || target == ListingWorkflowStatus.ARCHIVED
                    ? ListingStatus.CLOSED : ListingStatus.PENDING);
        if (target == ListingWorkflowStatus.PUBLISHED && listing.getPublishedAt() == null) {
            listing.setPublishedAt(LocalDateTime.now());
        }
        if (target == ListingWorkflowStatus.SUBMITTED) {
            listing.setSubmittedAt(LocalDateTime.now());
        }
    }

    private boolean allowed(ListingWorkflowStatus current, ListingWorkflowStatus target, Actor actor) {
        if (actor == Actor.ADMIN) {
            return current == ListingWorkflowStatus.SUBMITTED && target == ListingWorkflowStatus.UNDER_REVIEW
                    || current == ListingWorkflowStatus.UNDER_REVIEW
                        && (target == ListingWorkflowStatus.CHANGES_REQUIRED || target == ListingWorkflowStatus.PUBLISHED);
        }
        return current == ListingWorkflowStatus.CHANGES_REQUIRED && target == ListingWorkflowStatus.SUBMITTED
                || current == ListingWorkflowStatus.PUBLISHED
                    && (target == ListingWorkflowStatus.PAUSED || target == ListingWorkflowStatus.ARCHIVED)
                || current == ListingWorkflowStatus.PAUSED
                    && (target == ListingWorkflowStatus.SUBMITTED || target == ListingWorkflowStatus.ARCHIVED);
    }
}
