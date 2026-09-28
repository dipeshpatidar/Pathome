package com.indore.pathome.spaces.dto.lessor;

import java.util.List;

public record LandlordDraftPage(List<LandlordDraftSummary> items, int page, boolean hasMore) {}
