package com.indore.pathome.spaces.service;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

/** One transaction-scoped PostgreSQL lock shared by bootstrap and all global-Admin mutations. */
@Component
public class StaffGovernanceLock {
    private final EntityManager entityManager;

    public StaffGovernanceLock(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public void acquire() {
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(1346454344, 1196387922)")
                .getSingleResult();
    }
}
