package com.indore.pathome.spaces.dto;

import java.time.Instant;
import java.util.List;

public record StaffUserAccessState(Long userId,
                                   boolean profileExists,
                                   boolean staffActive,
                                   Long version,
                                   Instant staffActivatedAt,
                                   Instant staffDeactivatedAt,
                                   List<StaffGrantView> grants) {
}
