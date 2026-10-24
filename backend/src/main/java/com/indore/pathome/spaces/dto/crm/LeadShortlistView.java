package com.indore.pathome.spaces.dto.crm;

import java.time.Instant;
import java.util.List;

public record LeadShortlistView(
        Long id,
        Long version,
        Long leadVersion,
        List<Long> listingIds,
        Instant createdAt,
        Instant updatedAt) {
}
