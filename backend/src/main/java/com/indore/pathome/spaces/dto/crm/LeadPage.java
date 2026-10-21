package com.indore.pathome.spaces.dto.crm;

import java.util.List;

public record LeadPage(List<LeadSummaryView> items, long totalElements, int page, int size, long totalPages) {
}
