package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.TenantVisitAvailabilityCommand;
import com.indore.pathome.spaces.entity.PropertyVisitRequest;
import com.indore.pathome.spaces.exception.VisitOperationsConflictException;
import com.indore.pathome.spaces.repository.PropertyVisitRequestRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TenantVisitAvailabilityServiceTest {
    private final PropertyVisitRequestRepository requests = mock(PropertyVisitRequestRepository.class);
    private final TenantVisitAvailabilityService service = new TenantVisitAvailabilityService(requests);

    @Test
    void updatesOnlyTheAuthenticatedTenantsRequestAndKeepsPreferenceSeparateFromSession() {
        PropertyVisitRequest request = new PropertyVisitRequest();
        request.setId(51L);
        request.setVersion(3L);
        when(requests.findLockedByIdAndTenantId(51L, 7L)).thenReturn(Optional.of(request));

        var result = service.update(7L, 51L, command(3L));

        assertEquals(51L, result.requestId());
        assertEquals(InstantValue.START, result.availabilityStartAt());
        assertEquals(InstantValue.PREFERRED, result.preferredAt());
        assertNull(request.getSession());
        verify(requests).flush();
        verify(requests, never()).findLockedById(51L);
    }

    @Test
    void ownershipLookupHidesOtherTenantsAndStaleVersionsConflict() {
        when(requests.findLockedByIdAndTenantId(51L, 7L)).thenReturn(Optional.empty());
        assertThrows(EntityNotFoundException.class, () -> service.update(7L, 51L, command(3L)));

        PropertyVisitRequest request = new PropertyVisitRequest();
        request.setId(51L);
        request.setVersion(4L);
        when(requests.findLockedByIdAndTenantId(51L, 7L)).thenReturn(Optional.of(request));
        assertThrows(VisitOperationsConflictException.class, () -> service.update(7L, 51L, command(3L)));
        verify(requests, never()).flush();
    }

    @Test
    void supportsClearingStructuredPreferenceAndLegacyRowsRemainNull() {
        PropertyVisitRequest request = new PropertyVisitRequest();
        request.setId(51L);
        request.setVersion(0L);
        when(requests.findLockedByIdAndTenantId(51L, 7L)).thenReturn(Optional.of(request));

        var result = service.update(7L, 51L, new TenantVisitAvailabilityCommand(0L, null, null, null, null));

        assertNull(result.availabilityStartAt());
        assertNull(result.availabilityEndAt());
        assertNull(result.availabilityZoneId());
        assertNull(result.preferredAt());
        assertNull(TenantVisitAvailabilityService.view(new PropertyVisitRequest()).availabilityStartAt());
    }

    private TenantVisitAvailabilityCommand command(Long version) {
        return new TenantVisitAvailabilityCommand(version,
                OffsetDateTime.parse("2026-10-03T13:00:00+05:30"),
                OffsetDateTime.parse("2026-10-03T17:00:00+05:30"), "Asia/Kolkata",
                OffsetDateTime.parse("2026-10-03T14:00:00+05:30"));
    }

    private static final class InstantValue {
        private static final java.time.Instant START = java.time.Instant.parse("2026-10-03T07:30:00Z");
        private static final java.time.Instant PREFERRED = java.time.Instant.parse("2026-10-03T08:30:00Z");
    }
}
