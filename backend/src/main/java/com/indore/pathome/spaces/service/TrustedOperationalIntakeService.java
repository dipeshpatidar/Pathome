package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.exception.DraftConflictException;
import com.indore.pathome.spaces.entity.Locality;
import com.indore.pathome.spaces.entity.Listing;
import com.indore.pathome.spaces.entity.ListingStatus;
import com.indore.pathome.spaces.entity.ListingWorkflowStatus;
import com.indore.pathome.spaces.entity.PropertyVisitRequest;
import com.indore.pathome.spaces.entity.SupportedCity;
import com.indore.pathome.spaces.entity.VisitRequestRoutingTask;
import com.indore.pathome.spaces.repository.LocalityRepository;
import com.indore.pathome.spaces.repository.ListingRepository;
import com.indore.pathome.spaces.repository.PropertyVisitRequestRepository;
import com.indore.pathome.spaces.repository.SupportedCityRepository;
import com.indore.pathome.spaces.repository.VisitRequestRoutingTaskRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Admits customer intake only from a persisted Listing locality and the canonical City's live state. */
@Service
public class TrustedOperationalIntakeService {
    private final LocalityRepository localities;
    private final PropertyVisitRequestRepository requests;
    private final SupportedCityRepository cities;
    private final OperationalSecurityGuards guards;
    private final VisitRequestRoutingTaskRepository routingTasks;
    private final DatabaseClock databaseClock;
    private final EntityManager entityManager;

    @Autowired(required = false)
    private ListingRepository listings;

    public TrustedOperationalIntakeService(LocalityRepository localities, PropertyVisitRequestRepository requests,
            SupportedCityRepository cities, OperationalSecurityGuards guards,
            VisitRequestRoutingTaskRepository routingTasks, DatabaseClock databaseClock,
            EntityManager entityManager) {
        this.localities = localities;
        this.requests = requests;
        this.cities = cities;
        this.guards = guards;
        this.routingTasks = routingTasks;
        this.databaseClock = databaseClock;
        this.entityManager = entityManager;
    }

    @Transactional
    public PropertyVisitRequest admitAndSave(PropertyVisitRequest request) {
        return admitAndSave(request, false);
    }

    /**
     * Trusted Tenant creation path. Enrollment is committed in the same transaction as the Request and
     * never applies to duplicate readback, startup, timestamp sweeps, or historical fixture writers.
     */
    @Transactional
    public PropertyVisitRequest admitNewTenantIntakeAndSave(PropertyVisitRequest request) {
        return admitAndSave(request, true);
    }

    private PropertyVisitRequest admitAndSave(PropertyVisitRequest request, boolean enrollRouting) {
        if (request == null || request.getListing() == null)
            throw new IllegalArgumentException("A persisted Listing is required for trusted intake");
        Long localityId = request.getListing().getCanonicalLocalityId();
        if (localityId != null) {
            Locality locality = localities.findById(localityId).orElse(null);
            SupportedCity city = locality == null ? null : locality.getSupportedCity();
            if (city != null) {
                Long cityId = city.getId();
                entityManager.detach(city);
                guards.acquire(List.of(cityId), List.of(), List.of());
                SupportedCity currentCity = cities.findLockedById(cityId).orElse(null);
                request.setSupportedCity(currentCity);
                request.setOperationalScopeReady(currentCity != null && guards.cityIsActive(cityId));
            }
        }
        if (listings != null) {
            Long listingId = request.getListing().getId();
            if (listingId == null) throw new DraftConflictException("listing", 0,
                    "This property is no longer accepting visit requests.");
            listings.lockForSchedulingById(listingId)
                    .orElseThrow(() -> new DraftConflictException("listing", 0,
                            "This property is no longer accepting visit requests."));
            Listing current = listings.findById(listingId)
                    .orElseThrow(() -> new DraftConflictException("listing", 0,
                            "This property is no longer accepting visit requests."));
            entityManager.refresh(current, LockModeType.PESSIMISTIC_WRITE);
            if (current.getStatus() != ListingStatus.ACTIVE
                    || current.getWorkflowStatus() != ListingWorkflowStatus.PUBLISHED) {
                throw new DraftConflictException("listing", 0,
                        "This property is no longer accepting visit requests.");
            }
            request.setListing(current);
        }
        PropertyVisitRequest persisted = requests.saveAndFlush(request);
        if (enrollRouting) {
            routingTasks.saveAndFlush(new VisitRequestRoutingTask(persisted, databaseClock.now()));
        }
        return persisted;
    }
}
