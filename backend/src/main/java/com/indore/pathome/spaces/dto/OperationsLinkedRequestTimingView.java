package com.indore.pathome.spaces.dto;

import java.time.Instant;

/**
 * One linked Request's timing facts, associated by its own Request ID and version.
 *
 * <p>{@code preferredVisitTiming} is the tenant's original verbatim free text and is never rewritten
 * from structured availability. The V37 fields are the tenant's current availability and may differ
 * from what was originally requested. Neither value is a scheduled appointment.</p>
 */
public record OperationsLinkedRequestTimingView(
        Long requestId,
        Long version,
        String preferredVisitTiming,
        Instant availabilityStartAt,
        Instant availabilityEndAt,
        String availabilityZoneId,
        Instant preferredAt) {}
