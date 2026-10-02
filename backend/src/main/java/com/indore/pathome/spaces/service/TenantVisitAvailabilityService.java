package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.TenantVisitAvailabilityCommand;
import com.indore.pathome.spaces.dto.TenantVisitAvailabilityView;
import com.indore.pathome.spaces.entity.PropertyVisitRequest;
import com.indore.pathome.spaces.exception.VisitOperationsConflictException;
import com.indore.pathome.spaces.repository.PropertyVisitRequestRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;

@Service
public class TenantVisitAvailabilityService {
    private final PropertyVisitRequestRepository requests;

    public TenantVisitAvailabilityService(PropertyVisitRequestRepository requests) {
        this.requests = requests;
    }

    @Transactional
    public TenantVisitAvailabilityView update(Long authenticatedTenantId, Long requestId,
                                              TenantVisitAvailabilityCommand command) {
        if (authenticatedTenantId == null || authenticatedTenantId <= 0)
            throw new IllegalArgumentException("Authenticated tenant identity is required");
        if (requestId == null || requestId <= 0) throw new IllegalArgumentException("Visit Request ID must be positive");
        if (command == null) throw new IllegalArgumentException("Availability details are required");
        PropertyVisitRequest request = requests.findLockedByIdAndTenantId(requestId, authenticatedTenantId)
                .orElseThrow(() -> new EntityNotFoundException("Visit Request not found"));
        if (command.expectedRequestVersion() == null
                || !Objects.equals(request.getVersion(), command.expectedRequestVersion()))
            throw new VisitOperationsConflictException("Visit Request changed; reload and retry");

        var window = SchedulingWindowValidator.optional(command.availabilityStartAt(), command.availabilityEndAt(),
                command.availabilityZoneId(), command.preferredAt(), Instant.now(), true);
        if (window.isPresent()) {
            var value = window.get();
            request.setAvailabilityStartAt(value.startsAt());
            request.setAvailabilityEndAt(value.endsAt());
            request.setAvailabilityZoneId(value.zoneId());
            request.setPreferredAt(value.preferredAt());
        } else {
            request.setAvailabilityStartAt(null);
            request.setAvailabilityEndAt(null);
            request.setAvailabilityZoneId(null);
            request.setPreferredAt(null);
        }
        requests.flush();
        return view(request);
    }

    public static TenantVisitAvailabilityView view(PropertyVisitRequest request) {
        return new TenantVisitAvailabilityView(request.getId(), request.getVersion(),
                request.getAvailabilityStartAt(), request.getAvailabilityEndAt(),
                request.getAvailabilityZoneId(), request.getPreferredAt());
    }
}
