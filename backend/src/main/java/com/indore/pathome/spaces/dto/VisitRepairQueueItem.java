package com.indore.pathome.spaces.dto;

import java.time.Instant;
import java.util.UUID;

public record VisitRepairQueueItem(Long sessionId, Long version, String city, Instant previouslyScheduledAt, String zoneId,
        Long assignedGroundExecutiveUserId, String tenantConfirmationState, UUID repairOperationId) {}
