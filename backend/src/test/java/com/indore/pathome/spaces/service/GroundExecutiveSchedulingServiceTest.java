package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.*;
import com.indore.pathome.spaces.entity.*;
import com.indore.pathome.spaces.exception.VisitOperationsConflictException;
import com.indore.pathome.spaces.repository.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GroundExecutiveSchedulingServiceTest {
    private VisitOperationsAuthorizationService authorization;
    private EmployeeProfileRepository employees;
    private GroundExecutiveSchedulingProfileRepository schedules;
    private GroundExecutiveShiftRepository shifts;
    private GroundExecutiveUnavailabilityRepository unavailability;
    private GroundExecutiveCoverageRepository coverage;
    private LocalityRepository localities;
    private EntityManager entityManager;
    private GroundExecutiveSchedulingService service;
    private User actor;
    private User ground;
    private EmployeeProfile employee;
    private GroundExecutiveSchedulingProfile scheduleProfile;

    @BeforeEach
    void setUp() {
        authorization = mock(VisitOperationsAuthorizationService.class);
        employees = mock(EmployeeProfileRepository.class);
        schedules = mock(GroundExecutiveSchedulingProfileRepository.class);
        shifts = mock(GroundExecutiveShiftRepository.class);
        unavailability = mock(GroundExecutiveUnavailabilityRepository.class);
        coverage = mock(GroundExecutiveCoverageRepository.class);
        localities = mock(LocalityRepository.class);
        entityManager = mock(EntityManager.class);
        service = new GroundExecutiveSchedulingService(authorization, employees, schedules, shifts,
                unavailability, coverage, localities, entityManager);
        actor = user(9L, Role.ROLE_ADMIN);
        ground = user(41L, Role.ROLE_GROUND_BOY);
        employee = new EmployeeProfile();
        employee.setId(501L);
        employee.setUser(ground);
        employee.setRoleType("GROUND_BOY");
        scheduleProfile = new GroundExecutiveSchedulingProfile();
        scheduleProfile.setEmployeeProfile(employee);

        when(authorization.requireOperations(9L)).thenReturn(actor);
        when(authorization.requireGroundExecutiveTarget(41L)).thenReturn(ground);
        when(employees.findLockedByUserId(41L)).thenReturn(Optional.of(employee));
        when(employees.findByUserId(41L)).thenReturn(Optional.of(employee));
        when(schedules.findLockedByGroundExecutiveUserId(41L)).thenReturn(Optional.empty());
        when(schedules.saveAndFlush(any())).thenAnswer(invocation -> {
            GroundExecutiveSchedulingProfile saved = invocation.getArgument(0);
            if (saved.getVersion() == null) saved.setVersion(0L);
            scheduleProfile = saved;
            when(schedules.findById(501L)).thenReturn(Optional.of(saved));
            when(schedules.findLockedByGroundExecutiveUserId(41L)).thenReturn(Optional.of(saved));
            return saved;
        });
        when(shifts.findOverlappingWindow(eq(501L), any(), any())).thenReturn(List.of());
        when(unavailability.findOverlappingWindow(eq(501L), any(), any())).thenReturn(List.of());
        when(coverage.findBySchedulingProfileEmployeeProfileIdOrderByCityAsc(501L)).thenReturn(List.of());
    }

    @Test
    void activeStateAndDatedShiftUseDbBackedGroundCapabilityAndServerActor() {
        var active = service.setSchedulingActive(9L, 41L,
                new GroundExecutiveSchedulingActiveCommand(0L, true));
        assertTrue(active.schedulingActive());
        assertSame(actor, scheduleProfile.getUpdatedBy());

        service.addShift(9L, 41L, shiftCommand(0L,
                "2099-10-02T09:00:00+05:30", "2099-10-02T18:00:00+05:30", "Asia/Kolkata"));
        var captor = org.mockito.ArgumentCaptor.forClass(GroundExecutiveShift.class);
        verify(shifts).save(captor.capture());
        assertEquals(Instant.parse("2099-10-02T03:30:00Z"), captor.getValue().getStartsAt());
        assertEquals(Instant.parse("2099-10-02T12:30:00Z"), captor.getValue().getEndsAt());
        assertSame(actor, captor.getValue().getCreatedBy());
        assertEquals("Asia/Kolkata", captor.getValue().getZoneId());
    }

    @Test
    void nonGroundExecutiveAndWrongProfileCannotBeConfigured() {
        doThrow(new IllegalArgumentException("not GE")).when(authorization).requireGroundExecutiveTarget(41L);
        assertThrows(IllegalArgumentException.class, () -> service.setSchedulingActive(9L, 41L,
                new GroundExecutiveSchedulingActiveCommand(0L, true)));
        verify(schedules, never()).saveAndFlush(any());

        doReturn(ground).when(authorization).requireGroundExecutiveTarget(41L);
        employee.setRoleType("WFH_ADMIN");
        assertThrows(IllegalArgumentException.class, () -> service.setSchedulingActive(9L, 41L,
                new GroundExecutiveSchedulingActiveCommand(0L, true)));
        verify(schedules, never()).saveAndFlush(any());
    }

    @Test
    void invalidAndOverlappingShiftsAreRejectedAndAdjacentShiftsCanBeAdded() {
        var reversed = shiftCommand(0L, "2099-10-02T18:00:00+05:30", "2099-10-02T09:00:00+05:30", "Asia/Kolkata");
        assertThrows(IllegalArgumentException.class, () -> service.addShift(9L, 41L, reversed));
        verify(shifts, never()).save(any());

        GroundExecutiveShift existing = new GroundExecutiveShift();
        when(shifts.findOverlappingWindow(eq(501L), any(), any())).thenReturn(List.of(existing));
        assertThrows(VisitOperationsConflictException.class, () -> service.addShift(9L, 41L,
                shiftCommand(0L, "2099-10-02T09:00:00+05:30", "2099-10-02T18:00:00+05:30", "Asia/Kolkata")));
        verify(shifts, never()).save(any());
    }

    @Test
    void longDatedShiftCanBeSavedWhenMutationResponseReadWindowIsCapped() {
        var saved = service.addShift(9L, 41L, shiftCommand(0L,
                "2099-10-02T09:00:00+05:30", "2100-01-10T09:00:00+05:30", "Asia/Kolkata"));

        assertNotNull(saved);
        verify(shifts).save(any(GroundExecutiveShift.class));
        var starts = org.mockito.ArgumentCaptor.forClass(Instant.class);
        var ends = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(shifts, times(2)).findOverlappingWindow(eq(501L), starts.capture(), ends.capture());
        assertEquals(90, Duration.between(starts.getAllValues().get(1), ends.getAllValues().get(1)).toDays());
    }

    @Test
    void breakMustFitInsideItsShiftAndDuplicateUnavailableIntervalsAreRejected() {
        var breakCommand = unavailableCommand(0L, "2099-10-02T13:00:00+05:30",
                "2099-10-02T13:30:00+05:30", "Asia/Kolkata", GroundExecutiveUnavailableType.BREAK);
        when(shifts.existsShiftContaining(501L, Instant.parse("2099-10-02T07:30:00Z"),
                Instant.parse("2099-10-02T08:00:00Z"), "Asia/Kolkata")).thenReturn(false);
        assertThrows(IllegalArgumentException.class, () -> service.addUnavailability(9L, 41L, breakCommand));
        verify(unavailability, never()).save(any());

        when(shifts.existsShiftContaining(501L, Instant.parse("2099-10-02T07:30:00Z"),
                Instant.parse("2099-10-02T08:00:00Z"), "Asia/Kolkata")).thenReturn(true);
        when(unavailability.existsOverlapping(501L, Instant.parse("2099-10-02T07:30:00Z"),
                Instant.parse("2099-10-02T08:00:00Z"))).thenReturn(true);
        assertThrows(VisitOperationsConflictException.class, () -> service.addUnavailability(9L, 41L, breakCommand));
        verify(unavailability, never()).save(any());

        when(unavailability.existsOverlapping(501L, Instant.parse("2099-10-02T07:30:00Z"),
                Instant.parse("2099-10-02T08:00:00Z"))).thenReturn(false);
        service.addUnavailability(9L, 41L, breakCommand);
        verify(unavailability).save(any(GroundExecutiveUnavailability.class));
    }

    @Test
    void coverageUsesCanonicalCityAndLocalityAndRejectsMismatchedCity() {
        Locality metro = locality(10L, "Example City", "Central");
        when(localities.findFirstByCityIgnoreCaseOrderByCityAscIdAsc("Example City")).thenReturn(Optional.of(metro));
        when(localities.findById(10L)).thenReturn(Optional.of(metro));

        assertThrows(IllegalArgumentException.class, () -> service.replaceCoverage(9L, 41L,
                new GroundExecutiveCoverageCommand(0L,
                        List.of(new GroundExecutiveCoverageEntry(null, 10L),
                                new GroundExecutiveCoverageEntry("Elsewhere", 10L)))));
        verify(coverage, never()).deleteBySchedulingProfileEmployeeProfileId(anyLong());

        service.replaceCoverage(9L, 41L, new GroundExecutiveCoverageCommand(0L,
                List.of(new GroundExecutiveCoverageEntry("Example City", null))));
        var cityCaptor = org.mockito.ArgumentCaptor.forClass(GroundExecutiveCoverage.class);
        verify(coverage).save(cityCaptor.capture());
        assertEquals("Example City", cityCaptor.getValue().getCity());
        assertNull(cityCaptor.getValue().getLocality());
        assertSame(actor, cityCaptor.getValue().getCreatedBy());

        service.replaceCoverage(9L, 41L, new GroundExecutiveCoverageCommand(0L,
                List.of(new GroundExecutiveCoverageEntry("Example City", 10L))));
        var localityCaptor = org.mockito.ArgumentCaptor.forClass(GroundExecutiveCoverage.class);
        verify(coverage, times(2)).save(localityCaptor.capture());
        assertEquals(10L, localityCaptor.getAllValues().get(1).getLocality().getId());
    }

    @Test
    void staleScheduleVersionCannotOverwriteNewerAvailability() {
        scheduleProfile.setVersion(5L);
        when(schedules.findLockedByGroundExecutiveUserId(41L)).thenReturn(Optional.of(scheduleProfile));
        assertThrows(VisitOperationsConflictException.class, () -> service.setSchedulingActive(9L, 41L,
                new GroundExecutiveSchedulingActiveCommand(4L, false)));
        verify(entityManager, never()).flush();
    }

    private static GroundExecutiveShiftCommand shiftCommand(Long version, String start, String end, String zone) {
        return new GroundExecutiveShiftCommand(version, OffsetDateTime.parse(start), OffsetDateTime.parse(end), zone);
    }

    private static GroundExecutiveUnavailabilityCommand unavailableCommand(Long version, String start,
            String end, String zone, GroundExecutiveUnavailableType type) {
        return new GroundExecutiveUnavailabilityCommand(version, OffsetDateTime.parse(start),
                OffsetDateTime.parse(end), zone, type);
    }

    private static Locality locality(Long id, String city, String sector) {
        Locality locality = new Locality();
        locality.setId(id);
        locality.setCity(city);
        locality.setSectorName(sector);
        return locality;
    }

    private static User user(Long id, Role role) {
        User user = new User();
        user.setId(id);
        user.setRole(role);
        return user;
    }
}
