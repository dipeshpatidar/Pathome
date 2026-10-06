package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.Locality;
import com.indore.pathome.spaces.entity.PropertyVisitRequest;
import com.indore.pathome.spaces.entity.SupportedCity;
import com.indore.pathome.spaces.repository.LocalityRepository;
import com.indore.pathome.spaces.repository.PropertyVisitRequestRepository;
import com.indore.pathome.spaces.repository.SupportedCityRepository;
import jakarta.persistence.EntityManager;
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
    private final EntityManager entityManager;

    public TrustedOperationalIntakeService(LocalityRepository localities, PropertyVisitRequestRepository requests,
            SupportedCityRepository cities, OperationalSecurityGuards guards, EntityManager entityManager) {
        this.localities = localities;
        this.requests = requests;
        this.cities = cities;
        this.guards = guards;
        this.entityManager = entityManager;
    }

    @Transactional
    public PropertyVisitRequest admitAndSave(PropertyVisitRequest request) {
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
        return requests.saveAndFlush(request);
    }
}
