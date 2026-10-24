package com.indore.pathome.spaces.dto.crm;

import java.util.List;

public record LeadDuplicateCandidateResponse(
        List<LeadDuplicateCandidateView> candidates,
        boolean existingAccountMatchAvailable) {
}
