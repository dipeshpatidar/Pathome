package com.indore.pathome.spaces.dto.crm;

import java.time.Instant;

/** Metadata-only high-volume review. No customer, phone, lessor contact or Lead history is included. */
public record LeadContactAnomalyView(
        Long actorUserId,
        Long cityId,
        Long teamId,
        int revealCount,
        Instant latestAt) {}
