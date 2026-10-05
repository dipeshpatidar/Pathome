package com.indore.pathome.spaces.dto;

/** A null total means the historical grant still needs reconciliation. */
public record TenantVisitEntitlementView(
        Integer totalGrantedSessions,
        Integer remainingSessions,
        Integer reservedSessions,
        Integer availableSessions,
        boolean totalReconciliationRequired) {}
