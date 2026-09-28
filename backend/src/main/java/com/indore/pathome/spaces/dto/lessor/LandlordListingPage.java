package com.indore.pathome.spaces.dto.lessor;

import java.util.List;

public record LandlordListingPage(List<LandlordListingSummary> items, int page, boolean hasMore) {}
