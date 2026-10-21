package com.indore.pathome.spaces.dto;

/**
 * Session-root tenant contact contract. The identifier is the Session the caller already used; no
 * Request, user or account identifier is added.
 */
public record OperationsSessionTenantContactView(
        Long sessionId,
        String name,
        String mobile,
        OperationsTenantContactStatus status) {

    public static OperationsSessionTenantContactView availableForSession(Long sessionId, String name, String mobile) {
        return new OperationsSessionTenantContactView(sessionId, name, mobile,
                OperationsTenantContactStatus.CONTACT_AVAILABLE);
    }

    public static OperationsSessionTenantContactView unavailableForSession(Long sessionId) {
        return new OperationsSessionTenantContactView(sessionId, null, null,
                OperationsTenantContactStatus.CONTACT_UNAVAILABLE);
    }
}
