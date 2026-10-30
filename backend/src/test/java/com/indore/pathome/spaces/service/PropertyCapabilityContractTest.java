package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PropertyCapabilityContractTest {
    private static final Instant NOW = Instant.parse("2026-10-24T08:00:00Z");

    @Test
    void eachPropertyDutyIsValidOnlyAtCityScope() {
        User user = new User(7L, "staff@pathome.in", "hash", "Staff", "9000000000",
                Role.ROLE_TENANT, null, 0);
        User owner = new User(1L, "owner@pathome.in", "hash", "Owner", "9000000001",
                Role.ROLE_ADMIN, null, 0);
        SupportedCity city = new SupportedCity("indore", "Indore", true);
        OperatingTeam team = new OperatingTeam(city, "core", "Core Team", true);

        for (StaffCapability capability : new StaffCapability[]{
                StaffCapability.PROPERTY_EDIT_PUBLISH,
                StaffCapability.PROPERTY_AUDIT_READ,
                StaffCapability.PROPERTY_LIFECYCLE}) {
            assertDoesNotThrow(() -> new StaffAccessGrant(user, capability, StaffScopeType.CITY,
                    city, null, NOW, null, owner, NOW, "TEST_ASSIGNMENT",
                    StaffGrantProvisioningSource.ADMIN_API));
            assertThrows(IllegalArgumentException.class, () -> new StaffAccessGrant(user,
                    capability, StaffScopeType.GLOBAL, null, null, NOW, null, owner, NOW,
                    "TEST_ASSIGNMENT", StaffGrantProvisioningSource.ADMIN_API));
            assertThrows(IllegalArgumentException.class, () -> new StaffAccessGrant(user,
                    capability, StaffScopeType.TEAM, null, team, NOW, null, owner, NOW,
                    "TEST_ASSIGNMENT", StaffGrantProvisioningSource.ADMIN_API));
        }
    }
}

