package com.indore.pathome.spaces.dto.crm;

import java.util.List;

public record LeadShortlistCommand(
        Long expectedLeadVersion,
        Long expectedShortlistVersion,
        List<Object> listingIds) {
}
