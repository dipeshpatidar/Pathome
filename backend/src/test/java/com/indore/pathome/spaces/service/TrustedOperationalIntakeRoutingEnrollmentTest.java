package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.Listing;
import com.indore.pathome.spaces.entity.ListingStatus;
import com.indore.pathome.spaces.entity.ListingWorkflowStatus;
import com.indore.pathome.spaces.entity.PropertyVisitRequest;
import com.indore.pathome.spaces.exception.DraftConflictException;
import com.indore.pathome.spaces.repository.LocalityRepository;
import com.indore.pathome.spaces.repository.ListingRepository;
import com.indore.pathome.spaces.repository.PropertyVisitRequestRepository;
import com.indore.pathome.spaces.repository.SupportedCityRepository;
import com.indore.pathome.spaces.repository.VisitRequestRoutingTaskRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void intakeRefreshesTheListingAfterItsSchedulingLockBeforeEligibility() {
        LocalityRepository localities = mock(LocalityRepository.class);
        PropertyVisitRequestRepository requests = mock(PropertyVisitRequestRepository.class);
        SupportedCityRepository cities = mock(SupportedCityRepository.class);
        OperationalSecurityGuards guards = mock(OperationalSecurityGuards.class);
        VisitRequestRoutingTaskRepository tasks = mock(VisitRequestRoutingTaskRepository.class);
        DatabaseClock clock = mock(DatabaseClock.class);
        EntityManager entityManager = mock(EntityManager.class);
        ListingRepository listings = mock(ListingRepository.class);
        TrustedOperationalIntakeService service = new TrustedOperationalIntakeService(
                localities, requests, cities, guards, tasks, clock, entityManager);
        ReflectionTestUtils.setField(service, "listings", listings);

        Listing source = mock(Listing.class);
        Listing fresh = mock(Listing.class);
        when(source.getId()).thenReturn(42L);
        when(source.getCanonicalLocalityId()).thenReturn(null);
        when(fresh.getStatus()).thenReturn(ListingStatus.ACTIVE);
        when(fresh.getWorkflowStatus()).thenReturn(ListingWorkflowStatus.PUBLISHED);
        when(listings.lockForSchedulingById(42L)).thenReturn(Optional.of(42L));
        when(listings.findById(42L)).thenReturn(Optional.of(fresh));
        PropertyVisitRequest request = new PropertyVisitRequest();
        request.setListing(source);
        when(requests.saveAndFlush(request)).thenReturn(request);

        service.admitAndSave(request);

        verify(entityManager).refresh(fresh, LockModeType.PESSIMISTIC_WRITE);
        verify(requests).saveAndFlush(request);
    }

    @Test
    void intakeRejectsFreshPausedListingBeforeRequestPersistenceOrRoutingEnrollment() {
        LocalityRepository localities = mock(LocalityRepository.class);
        PropertyVisitRequestRepository requests = mock(PropertyVisitRequestRepository.class);
        SupportedCityRepository cities = mock(SupportedCityRepository.class);
        OperationalSecurityGuards guards = mock(OperationalSecurityGuards.class);
        VisitRequestRoutingTaskRepository tasks = mock(VisitRequestRoutingTaskRepository.class);
        DatabaseClock clock = mock(DatabaseClock.class);
        EntityManager entityManager = mock(EntityManager.class);
        ListingRepository listings = mock(ListingRepository.class);
        TrustedOperationalIntakeService service = new TrustedOperationalIntakeService(
                localities, requests, cities, guards, tasks, clock, entityManager);
        ReflectionTestUtils.setField(service, "listings", listings);

        Listing source = mock(Listing.class);
        Listing fresh = mock(Listing.class);
        when(source.getId()).thenReturn(42L);
        when(source.getCanonicalLocalityId()).thenReturn(null);
        when(fresh.getStatus()).thenReturn(ListingStatus.ACTIVE);
        when(fresh.getWorkflowStatus()).thenReturn(ListingWorkflowStatus.PAUSED);
        when(listings.lockForSchedulingById(42L)).thenReturn(Optional.of(42L));
        when(listings.findById(42L)).thenReturn(Optional.of(fresh));
        PropertyVisitRequest request = new PropertyVisitRequest();
        request.setListing(source);

        assertThrows(DraftConflictException.class,
                () -> service.admitNewTenantIntakeAndSave(request));
        verify(requests, never()).saveAndFlush(any());
        verify(tasks, never()).saveAndFlush(any());
    }
}
