package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.Listing;
import com.indore.pathome.spaces.entity.PropertyVisitRequest;
import com.indore.pathome.spaces.repository.LocalityRepository;
import com.indore.pathome.spaces.repository.PropertyVisitRequestRepository;
import com.indore.pathome.spaces.repository.SupportedCityRepository;
import com.indore.pathome.spaces.repository.VisitRequestRoutingTaskRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrustedOperationalIntakeRoutingEnrollmentTest {

    @Test
    void onlyTheTenantCreationEntryPointEnrollsRouting() {
        LocalityRepository localities = mock(LocalityRepository.class);
        PropertyVisitRequestRepository requests = mock(PropertyVisitRequestRepository.class);
        SupportedCityRepository cities = mock(SupportedCityRepository.class);
        OperationalSecurityGuards guards = mock(OperationalSecurityGuards.class);
        VisitRequestRoutingTaskRepository tasks = mock(VisitRequestRoutingTaskRepository.class);
        DatabaseClock clock = mock(DatabaseClock.class);
        EntityManager entityManager = mock(EntityManager.class);
        TrustedOperationalIntakeService service = new TrustedOperationalIntakeService(
                localities, requests, cities, guards, tasks, clock, entityManager);

        Listing listing = mock(Listing.class);
        PropertyVisitRequest request = new PropertyVisitRequest();
        request.setListing(listing);
        when(requests.saveAndFlush(request)).thenReturn(request);
        when(clock.now()).thenReturn(Instant.parse("2026-10-23T12:00:00Z"));

        service.admitAndSave(request);
        verify(tasks, never()).saveAndFlush(any());

        service.admitNewTenantIntakeAndSave(request);
        verify(tasks).saveAndFlush(any());
    }
}
