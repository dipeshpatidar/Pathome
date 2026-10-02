package com.indore.pathome.spaces.dto;

import java.util.List;

public record RecommendationRejection(
        Long groundExecutiveUserId,
        String feasibilityStatus,
        List<RecommendationRejectionReason> reasons) {}
