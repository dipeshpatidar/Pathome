package com.indore.pathome.spaces.dto.crm;

import com.indore.pathome.spaces.entity.LeadStatus;

public record LeadLifecycleCommand(Long expectedVersion, LeadStatus status, String reasonCode) {
}
