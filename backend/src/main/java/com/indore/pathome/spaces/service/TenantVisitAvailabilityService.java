package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.TenantVisitAvailabilityCommand;
import com.indore.pathome.spaces.dto.TenantVisitAvailabilityView;
import com.indore.pathome.spaces.entity.PropertyVisitRequest;
import com.indore.pathome.spaces.exception.VisitOperationsConflictException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant-self availability update seam.
 *
 * <p>Availability is a pre-booking fact. It may change only while the Request is standalone and
 * {@code RECEIVED}, or while it is {@code COORDINATING} inside a still-{@code DRAFT} Session. The
 * actual read, lock order, revalidation and write live in {@link TenantVisitAvailabilityTransaction}
 * so a topology change discovered after locking can roll back and safely re-resolve in a fresh
 * transaction instead of acquiring a Session lock while a Request lock is already held.</p>
 */
@Service
public class TenantVisitAvailabilityService {
    private static final int MAX_TOPOLOGY_ATTEMPTS = 3;

    private final TenantVisitAvailabilityTransaction transaction;

    public TenantVisitAvailabilityService(TenantVisitAvailabilityTransaction transaction) {
        this.transaction = transaction;
    }

    /**
     * The public retry boundary must never join an ambient transaction. Each attempt is its own
     * proxied transaction, so a topology change genuinely rolls back and releases its locks before
     * the next Session→Request lock order is discovered.
     */
    @Transactional(propagation = Propagation.NEVER)
    public TenantVisitAvailabilityView update(Long authenticatedTenantId, Long requestId,
                                              TenantVisitAvailabilityCommand command) {
        if (authenticatedTenantId == null || authenticatedTenantId <= 0)
            throw new IllegalArgumentException("Authenticated tenant identity is required");
        if (requestId == null || requestId <= 0) throw new IllegalArgumentException("Visit Request ID must be positive");
        if (command == null) throw new IllegalArgumentException("Availability details are required");
        for (int attempt = 1; attempt <= MAX_TOPOLOGY_ATTEMPTS; attempt++) {
            try {
                return transaction.attempt(authenticatedTenantId, requestId, command);
            } catch (TenantVisitAvailabilityTransaction.TopologyChangedException topologyChanged) {
                // The transaction rolled back. A concurrent link/schedule may have changed the root,
                // so re-discover from a fresh snapshot rather than holding a Request lock and then
                // asking for the Session lock.
            }
        }
        throw new VisitOperationsConflictException("Visit Request changed; reload and retry");
    }

    public static TenantVisitAvailabilityView view(PropertyVisitRequest request) {
        return new TenantVisitAvailabilityView(request.getId(), request.getVersion(),
                request.getAvailabilityStartAt(), request.getAvailabilityEndAt(),
                request.getAvailabilityZoneId(), request.getPreferredAt());
    }
}
