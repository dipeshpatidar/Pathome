package com.indore.pathome.spaces.dto;

/**
 * Minimal, least-privilege tenant contact contract for one authorized operational root.
 *
 * <p>The root is identified only by the identifier the caller already used: {@code requestId} for the
 * Request route and {@code sessionId} for the Session route. No email, address, User identifier or
 * unrelated account field is exposed. When the contact cannot be trusted or validated, {@code name}
 * and {@code mobile} are both {@code null}.</p>
 */
public record OperationsTenantContactView(
        Long requestId,
        String name,
        String mobile,
        OperationsTenantContactStatus status) {

    public static OperationsTenantContactView availableForRequest(Long requestId, String name, String mobile) {
        return new OperationsTenantContactView(requestId, name, mobile,
                OperationsTenantContactStatus.CONTACT_AVAILABLE);
    }

    public static OperationsTenantContactView unavailableForRequest(Long requestId) {
        return new OperationsTenantContactView(requestId, null, null,
                OperationsTenantContactStatus.CONTACT_UNAVAILABLE);
    }
}
