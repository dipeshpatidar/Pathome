package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.OperationsTenantContactStatus;
import com.indore.pathome.spaces.entity.User;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OperationsTenantContactResolverTest {

    @Test
    void trimsTheAccountNameAndUsesTheCanonicalMobileNormalizer() {
        User tenant = new User();
        tenant.setFullName("  Asha Verma  ");
        tenant.setPhoneNumber("98765-43210");

        var view = OperationsTenantContactResolver.resolveRequest(51L, tenant);

        assertEquals(51L, view.requestId());
        assertEquals("Asha Verma", view.name());
        assertEquals("+91 9876543210", view.mobile());
        assertEquals(OperationsTenantContactStatus.CONTACT_AVAILABLE, view.status());
    }

    @Test
    void sessionRootKeepsOnlyTheSessionIdentifier() {
        User tenant = new User();
        tenant.setFullName("Asha Verma");
        tenant.setPhoneNumber("+91 98765 43210");

        var view = OperationsTenantContactResolver.resolveSession(500L, tenant);

        assertEquals(500L, view.sessionId());
        assertEquals("CONTACT_AVAILABLE", view.status().name());
    }

    @Test
    void invalidOrPartialContactIsUnavailableWithBothValuesNull() {
        User noPhone = new User();
        noPhone.setFullName("Asha Verma");
        noPhone.setPhoneNumber(null);
        noPhone.setEmail("asha@example.test");
        var noPhoneView = OperationsTenantContactResolver.resolveSession(500L, noPhone);
        assertUnavailable(noPhoneView.name(), noPhoneView.mobile());
        assertEquals(OperationsTenantContactStatus.CONTACT_UNAVAILABLE, noPhoneView.status());

        User invalidPhone = new User();
        invalidPhone.setFullName("Asha Verma");
        invalidPhone.setPhoneNumber("12345");
        var invalid = OperationsTenantContactResolver.resolveRequest(51L, invalidPhone);
        assertUnavailable(invalid.name(), invalid.mobile());

        User blankName = new User();
        blankName.setFullName("   ");
        blankName.setPhoneNumber("9876543210");
        var blank = OperationsTenantContactResolver.resolveRequest(51L, blankName);
        assertUnavailable(blank.name(), blank.mobile());

        var missing = OperationsTenantContactResolver.resolveSession(500L, null);
        assertUnavailable(missing.name(), missing.mobile());
    }

    private static void assertUnavailable(String name, String mobile) {
        assertNull(name);
        assertNull(mobile);
    }
}
