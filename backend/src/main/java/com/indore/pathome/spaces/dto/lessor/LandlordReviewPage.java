package com.indore.pathome.spaces.dto.lessor;

import java.util.List;

public record LandlordReviewPage(List<LandlordReviewItem> items, int page, boolean hasMore) {}
