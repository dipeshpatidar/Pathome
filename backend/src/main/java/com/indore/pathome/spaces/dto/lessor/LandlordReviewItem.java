package com.indore.pathome.spaces.dto.lessor;

import java.time.LocalDateTime;

public record LandlordReviewItem(String draftId, Long listingId, String title, String status,
                                 LocalDateTime updatedAt) {}
