package com.indore.pathome.spaces.support;

import org.junit.jupiter.api.Test;

import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class A1A7IsolatedTargetTest {
    @Test
    void bindsOnlyTheOwnerApprovedN2V63Target() {
        assertEquals(java.nio.file.Path.of(
                        "/private/tmp/pathome-n2-v63-db-20261028-3cbccf2d"),
                A1A7IsolatedTarget.CLUSTER);
        assertEquals(55436, A1A7IsolatedTarget.PORT);
        assertEquals("pathome_n2_v63_it_20261028", A1A7IsolatedTarget.DATABASE);
        assertEquals("pathome_n2_v63_migrator_20261028",
                A1A7IsolatedTarget.MIGRATOR_ROLE);
        assertEquals("pathome_n2_v63_runtime_20261028",
                A1A7IsolatedTarget.RUNTIME_ROLE);
        assertEquals("pathome_n2_v63_runner_20261028",
                A1A7IsolatedTarget.RUNNER_ROLE);
        assertEquals("PATHOME_N2_V63_SOURCE_ONLY_DISPOSABLE_20261028",
                A1A7IsolatedTarget.MARKER);
    }

    @Test
    void jdbcBooleanValuesFailClosedOnNull() throws Exception {
        ResultSet trueRows = mock(ResultSet.class);
        when(trueRows.getBoolean(1)).thenReturn(true);
        when(trueRows.wasNull()).thenReturn(false);
        ResultSet falseRows = mock(ResultSet.class);
        when(falseRows.getBoolean(1)).thenReturn(false);
        when(falseRows.wasNull()).thenReturn(false);
        ResultSet nullRows = mock(ResultSet.class);
        when(nullRows.getBoolean(1)).thenReturn(false);
        when(nullRows.wasNull()).thenReturn(true);

        assertTrue(A1A7IsolatedTarget.readBoolean(trueRows));
        assertFalse(A1A7IsolatedTarget.readBoolean(falseRows));
        assertThrows(IllegalStateException.class,
                () -> A1A7IsolatedTarget.readBoolean(nullRows));
    }

    @Test
    void ownershipRulesDistinguishIntentionalMigratorOwnership() {
        assertEquals(null, invoke(A1A7IsolatedTarget.MIGRATOR_ROLE, false, true));
        assertEquals(null, invoke(A1A7IsolatedTarget.RUNTIME_ROLE, false, false));
        assertEquals(null, invoke(A1A7IsolatedTarget.RUNNER_ROLE, false, false));
        assertThrows(IllegalStateException.class,
                () -> invoke(A1A7IsolatedTarget.MIGRATOR_ROLE, false, false));
        assertThrows(IllegalStateException.class,
                () -> invoke(A1A7IsolatedTarget.RUNTIME_ROLE, false, true));
        assertThrows(IllegalStateException.class,
                () -> invoke(A1A7IsolatedTarget.RUNNER_ROLE, true, false));
    }

    private static Object invoke(String role, boolean superuser, boolean ownsDatabase) {
        A1A7IsolatedTarget.requireSafeDatabasePrivileges(role, superuser, ownsDatabase);
        return null;
    }
}
