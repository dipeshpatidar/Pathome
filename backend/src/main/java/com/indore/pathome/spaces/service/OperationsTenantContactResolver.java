package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.OperationsTenantContactView;
import com.indore.pathome.spaces.entity.User;

/**
 * Pure, fail-closed tenant-contact resolver for the R1-OE-1 contact seam.
 *
 * <p>The only source is the authorized Request/Session tenant relationship. A blank name, an
 * unusable account mobile, or a tenant that cannot be resolved produces
 * {@code CONTACT_UNAVAILABLE} with both values null. There is no email, lead-phone, lessor or
 * verified-login fallback.</p>
 */
final class OperationsTenantContactResolver {

    private OperationsTenantContactResolver() {}

    static OperationsTenantContactView resolveRequest(Long requestId, User tenant) {
        Contact contact = resolve(tenant);
        return contact.available()
                ? OperationsTenantContactView.availableForRequest(requestId, contact.name(), contact.mobile())
                : OperationsTenantContactView.unavailableForRequest(requestId);
    }

    static com.indore.pathome.spaces.dto.OperationsSessionTenantContactView resolveSession(Long sessionId, User tenant) {
        Contact contact = resolve(tenant);
        return contact.available()
                ? com.indore.pathome.spaces.dto.OperationsSessionTenantContactView.availableForSession(
                        sessionId, contact.name(), contact.mobile())
                : com.indore.pathome.spaces.dto.OperationsSessionTenantContactView.unavailableForSession(sessionId);
    }

    private static Contact resolve(User tenant) {
        String name = tenant == null || tenant.getFullName() == null ? null : tenant.getFullName().trim();
        String mobile = tenant == null ? null : tenant.getPhoneNumber();
        if (name == null || name.isBlank() || !LandlordContactService.isUsablePhone(mobile))
            return new Contact(null, null, false);
        return new Contact(name, LandlordContactService.normalizePhone(mobile), true);
    }

    private record Contact(String name, String mobile, boolean available) {}
}
