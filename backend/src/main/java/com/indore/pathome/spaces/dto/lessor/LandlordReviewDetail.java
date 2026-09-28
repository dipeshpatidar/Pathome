package com.indore.pathome.spaces.dto.lessor;

import java.util.List;

/** Admin-only review shape. Data includes the private address needed for moderation. */
public record LandlordReviewDetail(String draftId, Long listingId, String status, String reviewNote,
                                   LandlordDraftData data, List<LandlordMediaItem> media) {}
