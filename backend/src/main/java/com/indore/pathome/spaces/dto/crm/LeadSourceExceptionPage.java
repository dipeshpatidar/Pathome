package com.indore.pathome.spaces.dto.crm;

import java.util.List;

public record LeadSourceExceptionPage(
        List<LeadSourceExceptionView> items,
        long totalElements,
        int page,
        int size,
        int totalPages) {
}
