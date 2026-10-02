package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.GroundExecutiveCoverageCommand;
import com.indore.pathome.spaces.dto.GroundExecutiveCoverageEntry;
import com.indore.pathome.spaces.dto.GroundExecutiveSchedulingActiveCommand;
import com.indore.pathome.spaces.dto.GroundExecutiveSchedulingView;
import com.indore.pathome.spaces.dto.GroundExecutiveShiftCommand;
import com.indore.pathome.spaces.dto.GroundExecutiveUnavailabilityCommand;
import com.indore.pathome.spaces.entity.EmployeeProfile;
import com.indore.pathome.spaces.entity.GroundExecutiveCoverage;
import com.indore.pathome.spaces.entity.GroundExecutiveSchedulingProfile;
import com.indore.pathome.spaces.entity.GroundExecutiveShift;
import com.indore.pathome.spaces.entity.GroundExecutiveUnavailableType;
import com.indore.pathome.spaces.entity.GroundExecutiveUnavailability;
import com.indore.pathome.spaces.entity.Locality;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.exception.VisitOperationsConflictException;
import com.indore.pathome.spaces.repository.EmployeeProfileRepository;
import com.indore.pathome.spaces.repository.GroundExecutiveCoverageRepository;
import com.indore.pathome.spaces.repository.GroundExecutiveSchedulingProfileRepository;
import com.indore.pathome.spaces.repository.GroundExecutiveShiftRepository;
import com.indore.pathome.spaces.repository.GroundExecutiveUnavailabilityRepository;
import com.indore.pathome.spaces.repository.LocalityRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

@Service
public class GroundExecutiveSchedulingService {
    private static final String GROUND_PROFILE = "GROUND_BOY";
    private static final int MAX_COVERAGE_ENTRIES = 500;
    private static final Duration MAX_READ_WINDOW = Duration.ofDays(90);

    private final VisitOperationsAuthorizationService authorization;
    private final EmployeeProfileRepository employees;
    private final GroundExecutiveSchedulingProfileRepository schedules;
    private final GroundExecutiveShiftRepository shifts;
    private final GroundExecutiveUnavailabilityRepository unavailability;
    private final GroundExecutiveCoverageRepository coverage;
    private final LocalityRepository localities;
    private final EntityManager entityManager;

    public GroundExecutiveSchedulingService(VisitOperationsAuthorizationService authorization,
                                            EmployeeProfileRepository employees,
                                            GroundExecutiveSchedulingProfileRepository schedules,
                                            GroundExecutiveShiftRepository shifts,
                                            GroundExecutiveUnavailabilityRepository unavailability,
                                            GroundExecutiveCoverageRepository coverage,
                                            LocalityRepository localities,
                                            EntityManager entityManager) {
        this.authorization = authorization;
        this.employees = employees;
        this.schedules = schedules;
        this.shifts = shifts;
        this.unavailability = unavailability;
        this.coverage = coverage;
        this.localities = localities;
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public GroundExecutiveSchedulingView get(Long actorId, Long groundExecutiveUserId,
                                             Instant windowStart, Instant windowEnd) {
        authorization.requireOperations(actorId);
        requireGroundExecutive(groundExecutiveUserId);
        validateReadWindow(windowStart, windowEnd);
        EmployeeProfile employee = employees.findByUserId(groundExecutiveUserId)
                .orElseThrow(() -> new EntityNotFoundException("Ground Executive profile not found"));
        GroundExecutiveSchedulingProfile profile = schedules.findById(employee.getId()).orElse(null);
        List<GroundExecutiveSchedulingView.Shift> shiftViews = profile == null ? List.of()
                : shifts.findOverlappingWindow(employee.getId(), windowStart, windowEnd).stream()
                    .map(this::shiftView).toList();
        List<GroundExecutiveSchedulingView.Unavailability> unavailableViews = profile == null ? List.of()
                : unavailability.findOverlappingWindow(employee.getId(), windowStart, windowEnd).stream()
                    .map(this::unavailabilityView).toList();
        List<GroundExecutiveSchedulingView.Coverage> coverageViews = profile == null ? List.of()
                : coverage.findBySchedulingProfileEmployeeProfileIdOrderByCityAsc(employee.getId()).stream()
                    .map(this::coverageView).toList();
        return new GroundExecutiveSchedulingView(groundExecutiveUserId,
                profile != null && profile.isSchedulingActive(), profile == null ? 0L : profile.getVersion(),
                profile == null ? null : profile.getUpdatedAt(), shiftViews, unavailableViews, coverageViews);
    }

    @Transactional
    public GroundExecutiveSchedulingView setSchedulingActive(Long actorId, Long groundExecutiveUserId,
                                                              GroundExecutiveSchedulingActiveCommand command) {
        User actor = authorization.requireOperations(actorId);
        if (command == null || command.schedulingActive() == null)
            throw new IllegalArgumentException("Scheduling active state is required");
        GroundExecutiveSchedulingProfile profile = lockProfile(actor, groundExecutiveUserId,
                command.expectedVersion());
        profile.setSchedulingActive(command.schedulingActive());
        touch(profile, actor);
        entityManager.flush();
        return currentView(actorId, groundExecutiveUserId);
    }

    @Transactional
    public GroundExecutiveSchedulingView addShift(Long actorId, Long groundExecutiveUserId,
                                                   GroundExecutiveShiftCommand command) {
        User actor = authorization.requireOperations(actorId);
        requireCommand(command);
        GroundExecutiveSchedulingProfile profile = lockProfile(actor, groundExecutiveUserId,
                command.expectedVersion());
        var window = SchedulingWindowValidator.required(command.startsAt(), command.endsAt(), command.zoneId(),
                Instant.now(), false);
        if (!shifts.findOverlappingWindow(profile.getEmployeeProfileId(), window.startsAt(), window.endsAt()).isEmpty())
            throw new VisitOperationsConflictException("Ground Executive shift overlaps an existing shift");
        GroundExecutiveShift shift = new GroundExecutiveShift();
        shift.setSchedulingProfile(profile);
        shift.setStartsAt(window.startsAt());
        shift.setEndsAt(window.endsAt());
        shift.setZoneId(window.zoneId());
        shift.setCreatedBy(actor);
        shift.setUpdatedBy(actor);
        touch(profile, actor);
        persistAndFlushAsConflict("Ground Executive shift overlaps an existing shift", () -> shifts.save(shift));
        return viewForMutation(actorId, groundExecutiveUserId, window.startsAt());
    }

    @Transactional
    public GroundExecutiveSchedulingView updateShift(Long actorId, Long groundExecutiveUserId, Long shiftId,
                                                      GroundExecutiveShiftCommand command) {
        User actor = authorization.requireOperations(actorId);
        requireCommand(command);
        GroundExecutiveSchedulingProfile profile = lockProfile(actor, groundExecutiveUserId,
                command.expectedVersion());
        GroundExecutiveShift shift = shifts.findByIdAndSchedulingProfileEmployeeProfileId(
                requirePositive(shiftId, "Shift ID"), profile.getEmployeeProfileId())
                .orElseThrow(() -> new EntityNotFoundException("Ground Executive shift not found"));
        var window = SchedulingWindowValidator.required(command.startsAt(), command.endsAt(), command.zoneId(),
                Instant.now(), false);
        if (shifts.existsOverlappingExcept(profile.getEmployeeProfileId(), shiftId,
                window.startsAt(), window.endsAt()))
            throw new VisitOperationsConflictException("Ground Executive shift overlaps an existing shift");
        if (unavailability.existsBreakMadeInvalidByShiftUpdate(profile.getEmployeeProfileId(),
                shift.getStartsAt(), shift.getEndsAt(), window.startsAt(), window.endsAt(), window.zoneId()))
            throw new VisitOperationsConflictException("Shift update would leave a break outside its working shift");
        shift.setStartsAt(window.startsAt());
        shift.setEndsAt(window.endsAt());
        shift.setZoneId(window.zoneId());
        shift.setUpdatedBy(actor);
        shift.setUpdatedAt(Instant.now());
        touch(profile, actor);
        flushAsConflict("Ground Executive shift conflicts with existing scheduling data");
        return viewForMutation(actorId, groundExecutiveUserId, window.startsAt());
    }

    @Transactional
    public GroundExecutiveSchedulingView removeShift(Long actorId, Long groundExecutiveUserId, Long shiftId,
                                                      Long expectedVersion) {
        User actor = authorization.requireOperations(actorId);
        GroundExecutiveSchedulingProfile profile = lockProfile(actor, groundExecutiveUserId, expectedVersion);
        GroundExecutiveShift shift = shifts.findByIdAndSchedulingProfileEmployeeProfileId(
                requirePositive(shiftId, "Shift ID"), profile.getEmployeeProfileId())
                .orElseThrow(() -> new EntityNotFoundException("Ground Executive shift not found"));
        if (unavailability.existsBreakOverlapping(profile.getEmployeeProfileId(), shift.getStartsAt(), shift.getEndsAt()))
            throw new VisitOperationsConflictException("Remove or move the break before removing its shift");
        shifts.delete(shift);
        touch(profile, actor);
        entityManager.flush();
        return viewForMutation(actorId, groundExecutiveUserId, shift.getStartsAt());
    }

    @Transactional
    public GroundExecutiveSchedulingView addUnavailability(Long actorId, Long groundExecutiveUserId,
                                                            GroundExecutiveUnavailabilityCommand command) {
        User actor = authorization.requireOperations(actorId);
        requireUnavailabilityCommand(command);
        GroundExecutiveSchedulingProfile profile = lockProfile(actor, groundExecutiveUserId,
                command.expectedVersion());
        var window = SchedulingWindowValidator.required(command.startsAt(), command.endsAt(), command.zoneId(),
                Instant.now(), false);
        validateIntervalType(command.intervalType());
        if (command.intervalType() == GroundExecutiveUnavailableType.BREAK
                && !shifts.existsShiftContaining(profile.getEmployeeProfileId(), window.startsAt(), window.endsAt(), window.zoneId()))
            throw new IllegalArgumentException("A break must fit inside a shift in the same timezone");
        if (unavailability.existsOverlapping(profile.getEmployeeProfileId(), window.startsAt(), window.endsAt()))
            throw new VisitOperationsConflictException("Ground Executive unavailable intervals cannot overlap");
        GroundExecutiveUnavailability interval = new GroundExecutiveUnavailability();
        interval.setSchedulingProfile(profile);
        interval.setStartsAt(window.startsAt());
        interval.setEndsAt(window.endsAt());
        interval.setZoneId(window.zoneId());
        interval.setIntervalType(command.intervalType());
        interval.setCreatedBy(actor);
        interval.setUpdatedBy(actor);
        touch(profile, actor);
        persistAndFlushAsConflict("Ground Executive unavailable intervals cannot overlap",
                () -> unavailability.save(interval));
        return viewForMutation(actorId, groundExecutiveUserId, window.startsAt());
    }

    @Transactional
    public GroundExecutiveSchedulingView updateUnavailability(Long actorId, Long groundExecutiveUserId,
                                                               Long intervalId,
                                                               GroundExecutiveUnavailabilityCommand command) {
        User actor = authorization.requireOperations(actorId);
        requireUnavailabilityCommand(command);
        GroundExecutiveSchedulingProfile profile = lockProfile(actor, groundExecutiveUserId,
                command.expectedVersion());
        GroundExecutiveUnavailability interval = unavailability.findByIdAndSchedulingProfileEmployeeProfileId(
                requirePositive(intervalId, "Unavailable interval ID"), profile.getEmployeeProfileId())
                .orElseThrow(() -> new EntityNotFoundException("Ground Executive unavailable interval not found"));
        var window = SchedulingWindowValidator.required(command.startsAt(), command.endsAt(), command.zoneId(),
                Instant.now(), false);
        validateIntervalType(command.intervalType());
        if (command.intervalType() == GroundExecutiveUnavailableType.BREAK
                && !shifts.existsShiftContaining(profile.getEmployeeProfileId(), window.startsAt(), window.endsAt(), window.zoneId()))
            throw new IllegalArgumentException("A break must fit inside a shift in the same timezone");
        if (unavailability.existsOverlappingExcept(profile.getEmployeeProfileId(), intervalId,
                window.startsAt(), window.endsAt()))
            throw new VisitOperationsConflictException("Ground Executive unavailable intervals cannot overlap");
        interval.setStartsAt(window.startsAt());
        interval.setEndsAt(window.endsAt());
        interval.setZoneId(window.zoneId());
        interval.setIntervalType(command.intervalType());
        interval.setUpdatedBy(actor);
        interval.setUpdatedAt(Instant.now());
        touch(profile, actor);
        flushAsConflict("Ground Executive unavailable intervals cannot overlap");
        return viewForMutation(actorId, groundExecutiveUserId, window.startsAt());
    }

    @Transactional
    public GroundExecutiveSchedulingView removeUnavailability(Long actorId, Long groundExecutiveUserId,
                                                               Long intervalId, Long expectedVersion) {
        User actor = authorization.requireOperations(actorId);
        GroundExecutiveSchedulingProfile profile = lockProfile(actor, groundExecutiveUserId, expectedVersion);
        GroundExecutiveUnavailability interval = unavailability.findByIdAndSchedulingProfileEmployeeProfileId(
                requirePositive(intervalId, "Unavailable interval ID"), profile.getEmployeeProfileId())
                .orElseThrow(() -> new EntityNotFoundException("Ground Executive unavailable interval not found"));
        unavailability.delete(interval);
        touch(profile, actor);
        entityManager.flush();
        return viewForMutation(actorId, groundExecutiveUserId, interval.getStartsAt());
    }

    @Transactional
    public GroundExecutiveSchedulingView replaceCoverage(Long actorId, Long groundExecutiveUserId,
                                                          GroundExecutiveCoverageCommand command) {
        User actor = authorization.requireOperations(actorId);
        if (command == null || command.coverage() == null)
            throw new IllegalArgumentException("Coverage list is required");
        if (command.coverage().size() > MAX_COVERAGE_ENTRIES)
            throw new IllegalArgumentException("Coverage list is too large");
        GroundExecutiveSchedulingProfile profile = lockProfile(actor, groundExecutiveUserId,
                command.expectedVersion());
        List<CoverageSpec> canonicalCoverage = canonicalizeCoverage(command.coverage());
        coverage.deleteBySchedulingProfileEmployeeProfileId(profile.getEmployeeProfileId());
        coverage.flush();
        touch(profile, actor);
        persistAndFlushAsConflict("Duplicate or conflicting Ground Executive coverage", () -> {
            for (CoverageSpec spec : canonicalCoverage) {
                GroundExecutiveCoverage entry = new GroundExecutiveCoverage();
                entry.setSchedulingProfile(profile);
                entry.setCity(spec.city());
                entry.setLocality(spec.locality());
                entry.setCreatedBy(actor);
                coverage.save(entry);
            }
        });
        return currentView(actorId, groundExecutiveUserId);
    }

    private GroundExecutiveSchedulingProfile lockProfile(User actor, Long groundExecutiveUserId,
                                                          Long expectedVersion) {
        requirePositive(groundExecutiveUserId, "Ground Executive user ID");
        authorization.requireGroundExecutiveTarget(groundExecutiveUserId);
        EmployeeProfile employee = employees.findLockedByUserId(groundExecutiveUserId)
                .orElseThrow(() -> new EntityNotFoundException("Ground Executive profile not found"));
        if (!GROUND_PROFILE.equalsIgnoreCase(employee.getRoleType()))
            throw new IllegalArgumentException("Only Ground Executive profiles can be scheduled");
        GroundExecutiveSchedulingProfile profile = schedules.findLockedByGroundExecutiveUserId(groundExecutiveUserId)
                .orElse(null);
        if (profile == null) {
            if (expectedVersion == null || expectedVersion != 0L)
                throw new VisitOperationsConflictException("Scheduling profile changed; reload and retry");
            profile = new GroundExecutiveSchedulingProfile();
            profile.setEmployeeProfile(employee);
            profile.setUpdatedBy(actor);
            profile.setUpdatedAt(Instant.now());
            profile = schedules.saveAndFlush(profile);
        }
        if (expectedVersion == null || !Objects.equals(profile.getVersion(), expectedVersion))
            throw new VisitOperationsConflictException("Ground Executive scheduling data changed; reload and retry");
        return profile;
    }

    private List<CoverageSpec> canonicalizeCoverage(List<GroundExecutiveCoverageEntry> entries) {
        List<CoverageSpec> result = new ArrayList<>(entries.size());
        Set<String> cities = new HashSet<>();
        Set<Long> localityIds = new HashSet<>();
        Set<String> citywide = new HashSet<>();
        for (GroundExecutiveCoverageEntry entry : entries) {
            if (entry == null) throw new IllegalArgumentException("Coverage entry is required");
            if (entry.localityId() == null) {
                if (entry.city() == null || entry.city().isBlank())
                    throw new IllegalArgumentException("City-wide coverage requires a canonical city");
                String canonicalCity = localities.findFirstByCityIgnoreCaseOrderByCityAscIdAsc(entry.city().trim())
                        .map(Locality::getCity)
                        .orElseThrow(() -> new IllegalArgumentException("City is not in the canonical locality registry"));
                String normalized = normalizeCity(canonicalCity);
                if (!cities.add(normalized)) throw new IllegalArgumentException("Coverage contains duplicate cities");
                citywide.add(normalized);
                result.add(new CoverageSpec(canonicalCity, null));
            } else {
                Locality locality = localities.findById(entry.localityId())
                        .orElseThrow(() -> new EntityNotFoundException("Canonical locality not found"));
                String city = locality.getCity();
                if (entry.city() != null && !entry.city().isBlank()
                        && !normalizeCity(entry.city()).equals(normalizeCity(city)))
                    throw new IllegalArgumentException("Coverage locality does not belong to the supplied city");
                String normalizedCity = normalizeCity(city);
                if (citywide.contains(normalizedCity))
                    throw new IllegalArgumentException("City-wide coverage cannot be combined with locality coverage in that city");
                if (!localityIds.add(locality.getId()))
                    throw new IllegalArgumentException("Coverage contains a duplicate locality");
                result.add(new CoverageSpec(city, locality));
            }
        }
        Set<String> coveredCities = new HashSet<>();
        result.stream().filter(spec -> spec.locality() != null)
                .forEach(spec -> coveredCities.add(normalizeCity(spec.city())));
        if (citywide.stream().anyMatch(coveredCities::contains))
            throw new IllegalArgumentException("City-wide coverage cannot be combined with locality coverage in that city");
        return result;
    }

    private void validateReadWindow(Instant start, Instant end) {
        if (start == null || end == null || !start.isBefore(end))
            throw new IllegalArgumentException("A valid availability query window is required");
        if (Duration.between(start, end).compareTo(MAX_READ_WINDOW) > 0)
            throw new IllegalArgumentException("Availability query window cannot exceed 90 days");
    }

    private GroundExecutiveSchedulingView currentView(Long actorId, Long groundExecutiveUserId) {
        Instant start = Instant.now();
        return get(actorId, groundExecutiveUserId, start, start.plus(MAX_READ_WINDOW));
    }

    private GroundExecutiveSchedulingView viewForMutation(Long actorId, Long groundExecutiveUserId,
                                                           Instant anchor) {
        return get(actorId, groundExecutiveUserId, anchor, anchor.plus(MAX_READ_WINDOW));
    }

    private void validateIntervalType(GroundExecutiveUnavailableType type) {
        if (type == null) throw new IllegalArgumentException("Unavailable interval type is required");
    }

    private void requireUnavailabilityCommand(GroundExecutiveUnavailabilityCommand command) {
        requireCommand(command);
        validateIntervalType(command.intervalType());
    }

    private void requireCommand(Object command) {
        if (command == null) throw new IllegalArgumentException("Scheduling details are required");
    }

    private void touch(GroundExecutiveSchedulingProfile profile, User actor) {
        Instant now = Instant.now();
        if (profile.getUpdatedAt() != null && !now.isAfter(profile.getUpdatedAt()))
            now = profile.getUpdatedAt().plusNanos(1_000_000);
        profile.setUpdatedAt(now);
        profile.setUpdatedBy(actor);
    }

    private void flushAsConflict(String message) {
        try {
            entityManager.flush();
        } catch (DataIntegrityViolationException conflict) {
            throw new VisitOperationsConflictException(message);
        }
    }

    private void persistAndFlushAsConflict(String message, Runnable persist) {
        try {
            persist.run();
            entityManager.flush();
        } catch (DataIntegrityViolationException conflict) {
            throw new VisitOperationsConflictException(message);
        }
    }

    private long requirePositive(Long id, String label) {
        if (id == null || id <= 0) throw new IllegalArgumentException(label + " must be positive");
        return id;
    }

    private void requireGroundExecutive(Long userId) {
        requirePositive(userId, "Ground Executive user ID");
        authorization.requireGroundExecutiveTarget(userId);
    }

    private String normalizeCity(String city) {
        return city == null ? "" : city.trim().toLowerCase(Locale.ROOT);
    }

    private GroundExecutiveSchedulingView.Shift shiftView(GroundExecutiveShift shift) {
        return new GroundExecutiveSchedulingView.Shift(shift.getId(), shift.getStartsAt(), shift.getEndsAt(),
                shift.getZoneId(), shift.getVersion());
    }

    private GroundExecutiveSchedulingView.Unavailability unavailabilityView(GroundExecutiveUnavailability item) {
        return new GroundExecutiveSchedulingView.Unavailability(item.getId(), item.getStartsAt(), item.getEndsAt(),
                item.getZoneId(), item.getIntervalType().name(), item.getVersion());
    }

    private GroundExecutiveSchedulingView.Coverage coverageView(GroundExecutiveCoverage item) {
        return new GroundExecutiveSchedulingView.Coverage(item.getId(), item.getCity(),
                item.getLocality() == null ? null : item.getLocality().getId(),
                item.getLocality() == null ? null : item.getLocality().getSectorName());
    }

    private record CoverageSpec(String city, Locality locality) {}
}
