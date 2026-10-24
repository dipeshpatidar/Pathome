package com.indore.pathome.spaces.dto.crm;

import java.util.List;

public record LeadShareHistoryPage(
        List<LeadShareHistoryView> items,
        long totalElements,
        int page,
        int size,
        int totalPages) {
}
