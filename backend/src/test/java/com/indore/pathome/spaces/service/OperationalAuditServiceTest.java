package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.OperationalAuditEvent;
import com.indore.pathome.spaces.entity.Role;
import com.indore.pathome.spaces.entity.SupportedCity;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.repository.OperationalAuditEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OperationalAuditServiceTest {
    private final OperationalAuditEventRepository events = mock(OperationalAuditEventRepository.class);
    private final DatabaseClock clock = mock(DatabaseClock.class);
    private final OperationalAuditService service = new OperationalAuditService(events, clock);

    @Test
    void publicationVersionAcceptsOnlyItsApprovedBoundedAuditContract() {
        User actor = new User(17L, "publisher@example.test", null, "Publisher", null,
                Role.ROLE_TENANT, null, 5);
        SupportedCity city = new SupportedCity("indore", "Indore", true);
        ReflectionTestUtils.setField(city, "id", 7L);
        when(clock.now()).thenReturn(Instant.parse("2026-10-26T00:00:00Z"));

        var details = new LinkedHashMap<String, Object>();
        details.put("publicationVersion", 12L);
        details.put("previousVersion", 8L);
        details.put("resultingVersion", 9L);
        details.put("capability", "PROPERTY_EDIT_PUBLISH");
        details.put("scopeType", "CITY");
        details.put("cityId", 7L);

        service.recordUserEvent(actor, "PROPERTY_CONTENT_PUBLISHED", "LISTING", 42L,
                "STAFF_PROPERTY_PUBLICATION", city, null, details);

        var captured = org.mockito.ArgumentCaptor.forClass(OperationalAuditEvent.class);
        verify(events).save(captured.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> persisted = (Map<String, Object>) ReflectionTestUtils.getField(
                captured.getValue(), "details");
        assertEquals(12L, persisted.get("publicationVersion"));
        assertEquals(details, persisted);
    }

    @Test
    void publicationVersionDoesNotWidenTheAuditAllowlist() {
        User actor = new User(17L, "publisher@example.test", null, "Publisher", null,
                Role.ROLE_TENANT, null, 5);
        when(clock.now()).thenReturn(Instant.parse("2026-10-26T00:00:00Z"));
        List<Map<String, Object>> rejectedDetails = List.of(
                Map.of("publicationVersions", 12L),
                Map.of("PublicationVersion", 12L),
                Map.of("publication_version", 12L),
                Map.of("nestedPublicationVersion", Map.of("value", 12L)),
                Map.of("adminAuthority", "STAFF_ADMIN"),
                Map.of("ownerEmail", "owner@example.test"),
                Map.of("publicationVersion", Map.of("value", 12L)),
                Map.of("publicationVersion", "12"));

        for (Map<String, Object> details : rejectedDetails) {
            reset(events);
            assertThrows(IllegalArgumentException.class,
                    () -> service.recordUserEvent(actor, "PROPERTY_CONTENT_PUBLISHED", "LISTING", 42L,
                            "STAFF_PROPERTY_PUBLICATION", null, null, details));
            verifyNoInteractions(events);
        }
    }
}
