package com.indore.pathome.spaces.repository;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class VisitRequestRoutingTaskRepositorySqlTest {

    @Test
    void scopedTaskPagesFilterReadinessAndCanonicalLocalityCityBeforePagination() {
        String visibility = VisitRequestRoutingTaskRepository.NON_GLOBAL_VISIBILITY;

        assertTrue(visibility.contains("request.supported_city_id IN (:cityIds)"));
        assertTrue(visibility.contains("request.operational_scope_ready = TRUE"));
        assertTrue(visibility.contains(
                "locality.supported_city_id = request.supported_city_id"));
    }
}
