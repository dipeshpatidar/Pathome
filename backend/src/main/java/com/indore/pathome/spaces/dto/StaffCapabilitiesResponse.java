package com.indore.pathome.spaces.dto;

import java.time.Instant;
import java.util.List;

public record StaffCapabilitiesResponse(boolean staffActive,
                                        List<StaffCapabilitySummary> capabilities,
                                        Instant serverTime) {
}
