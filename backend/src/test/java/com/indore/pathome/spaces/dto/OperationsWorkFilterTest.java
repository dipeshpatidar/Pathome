package com.indore.pathome.spaces.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OperationsWorkFilterTest {
    @Test
    void absentValuesAreAnUnfilteredQueue() {
        OperationsWorkFilter filter = OperationsWorkFilter.parse(null, null, null, null, null);
        assertTrue(filter.isEmpty());
        assertEquals(OperationsWorkFilter.NONE, filter);
    }

    @Test
    void validTypeSpecificFiltersAreAccepted() {
        OperationsWorkFilter request = OperationsWorkFilter.parse("REQUEST", "RECEIVED", "3", "9", "501");
        assertEquals(OperationsRootType.REQUEST, request.targetType());
        assertEquals("RECEIVED", request.status());
        assertEquals(3L, request.cityId());
        assertEquals(9L, request.teamId());
        assertEquals(501L, request.targetId());

        OperationsWorkFilter session = OperationsWorkFilter.parse("SESSION", "DRAFT", null, null, null);
        assertEquals(OperationsRootType.SESSION, session.targetType());
        assertEquals("DRAFT", session.status());
    }

    @Test
    void statusAndExactReferenceRequireAnExplicitType() {
        assertThrows(IllegalArgumentException.class,
                () -> OperationsWorkFilter.parse(null, "SCHEDULED", null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> OperationsWorkFilter.parse(null, null, null, null, "7"));
    }

    @Test
    void statusMustBelongToTheSelectedType() {
        assertThrows(IllegalArgumentException.class,
                () -> OperationsWorkFilter.parse("REQUEST", "DRAFT", null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> OperationsWorkFilter.parse("SESSION", "RECEIVED", null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> OperationsWorkFilter.parse("SESSION", "NOT_A_STATUS", null, null, null));
    }

    @Test
    void malformedIdentifiersAreRejectedBeforeSql() {
        assertThrows(IllegalArgumentException.class,
                () -> OperationsWorkFilter.parse("REQUEST", null, "abc", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> OperationsWorkFilter.parse("REQUEST", null, null, "0", null));
        assertThrows(IllegalArgumentException.class,
                () -> OperationsWorkFilter.parse("REQUEST", null, null, null, "-4"));
        assertThrows(IllegalArgumentException.class,
                () -> OperationsWorkFilter.parse("TEAM", null, null, null, null));
    }

    @Test
    void surroundingWhitespaceIsCanonicalised() {
        OperationsWorkFilter filter = OperationsWorkFilter.parse(" REQUEST ", " RECEIVED ", " 3 ", " 9 ", " 5 ");
        assertEquals(OperationsRootType.REQUEST, filter.targetType());
        assertEquals("RECEIVED", filter.status());
        assertEquals(3L, filter.cityId());
    }
}
