package com.indore.pathome.spaces.dto.crm;

public record LeadAssignmentCommand(Long expectedVersion, Long targetCoordinatorUserId, String reasonCode) {
}
