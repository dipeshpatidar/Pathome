package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.RoutingUnclaimedWorkPage;
import com.indore.pathome.spaces.repository.RoutingOversightRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoutingOversightService {
    private static final int MAX_PAGE_SIZE = 100;
    private final RoutingOversightRepository oversight;
    private final StaffAccessService access;

    public RoutingOversightService(RoutingOversightRepository oversight, StaffAccessService access) {
        this.oversight = oversight;
        this.access = access;
    }

    @Transactional(readOnly = true)
    public RoutingUnclaimedWorkPage unclaimed(Long actorId, int page, int size) {
        IntakeRoutingAuthority.resolve(access, actorId);
        if (page < 0) throw new IllegalArgumentException("page must not be negative");
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        var snapshot = oversight.page(actorId, (long) page * size, size);
        long totalPages = snapshot.totalCount() == 0 ? 0
                : (long) Math.ceil((double) snapshot.totalCount() / size);
        return new RoutingUnclaimedWorkPage(snapshot.items(), snapshot.totalCount(),
                page, size, totalPages);
    }
}
