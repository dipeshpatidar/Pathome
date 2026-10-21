package com.indore.pathome.spaces.dto.crm;

import java.util.Map;

public record LeadPatchCommand(Long expectedVersion, Map<String, Object> changes) {
}
