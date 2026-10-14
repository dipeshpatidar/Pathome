package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.TenantVisitAvailabilityCommand;
import com.indore.pathome.spaces.entity.PropertyVisitRequest;
import com.indore.pathome.spaces.exception.VisitOperationsConflictException;
import com.indore.pathome.spaces.repository.PropertyVisitRequestRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TenantVisitAvailabilityServiceTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    private final PropertyVisitRequestRepository requests = mock(PropertyVisitRequestRepository.class);
    private final TenantVisitAvailabilityService service = new TenantVisitAvailabilityService(requests);

    /** Derived from the current date so the fixture always sits in the future. */
    private final OffsetDateTime availabilityStart =
            LocalDate.now(ZONE)
                    .plusDays(2)
                    .atTime(13, 0)
                    .atZone(ZONE)
                    .toOffsetDateTime();

    @Test
    void updatesOnlyTheAuthenticatedTenantsRequestAndKeepsPreferenceSeparateFromSession() {
        PropertyVisitRequest request = new PropertyVisitRequest();
        request.setId(51L);
        request.setVersion(3L);
        when(requests.findLockedByIdAndTenantId(51L, 7L)).thenReturn(Optional.of(request));

        var result = service.update(7L, 51L, command(3L));

        assertEquals(51L, result.requestId());
        assertEquals(availabilityStart.toInstant(), result.availabilityStartAt());
        assertEquals(availabilityStart.plusHours(4).toInstant(), result.availabilityEndAt());
        assertEquals(availabilityStart.plusHours(1).toInstant(), result.preferredAt());
        assertEquals(ZONE.getId(), result.availabilityZoneId());
        assertNull(request.getSession());
        verify(requests).flush();
        verify(requests, never()).findLockedById(51L);
    }

    @Test
    void rejectsAvailabilityWindowStartingInThePastWithoutMutatingRequestOrFlushing() {
        PropertyVisitRequest request = new PropertyVisitRequest();
        request.setId(51L);
        request.setVersion(3L);
        when(requests.findLockedByIdAndTenantId(51L, 7L)).thenReturn(Optional.of(request));

        OffsetDateTime expiredStart = availabilityStart.minusDays(4);
        var failure = assertThrows(IllegalArgumentException.class,
                () -> service.update(7L, 51L, command(3L, expiredStart)));

        assertTrue(failure.getMessage().contains("cannot start in the past"), failure.getMessage());
        assertNull(request.getAvailabilityStartAt());
        assertNull(request.getAvailabilityEndAt());
        assertNull(request.getAvailabilityZoneId());
        assertNull(request.getPreferredAt());
        assertNull(request.getSession());
        assertEquals(3L, request.getVersion());
        verify(requests, never()).flush();
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
        return command(version, availabilityStart);
    }

    private TenantVisitAvailabilityCommand command(Long version, OffsetDateTime start) {
        return new TenantVisitAvailabilityCommand(version,
                start,
                start.plusHours(4), ZONE.getId(),
                start.plusHours(1));
    }
}
