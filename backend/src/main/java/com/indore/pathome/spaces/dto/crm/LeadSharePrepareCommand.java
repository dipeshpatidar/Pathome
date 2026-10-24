package com.indore.pathome.spaces.dto.crm;

import com.indore.pathome.spaces.entity.LeadShareMode;

import java.util.UUID;

public record LeadSharePrepareCommand(
        UUID operationId,
        Long expectedLeadVersion,
        Long shortlistId,
        Long expectedShortlistVersion,
        Long contactPreferenceRevision,
        LeadShareMode mode) {
}
