package com.indore.pathome.spaces.dto.crm;

import java.util.List;

public record LeadActivityPage(List<LeadActivityView> items, long totalElements, int page, int size, int totalPages) {
}
