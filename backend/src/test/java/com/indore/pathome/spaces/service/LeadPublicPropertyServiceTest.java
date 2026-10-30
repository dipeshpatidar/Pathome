package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.LeadIntent;
import com.indore.pathome.spaces.entity.LeadRoutingQueue;
import com.indore.pathome.spaces.entity.ListingStatus;
import com.indore.pathome.spaces.entity.ListingType;
import com.indore.pathome.spaces.entity.ListingWorkflowStatus;
import com.indore.pathome.spaces.entity.Locality;
import com.indore.pathome.spaces.entity.RentalDetails;
import com.indore.pathome.spaces.entity.SupportedCity;
import com.indore.pathome.spaces.repository.ListingRepository;
import com.indore.pathome.spaces.repository.LocalityRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LeadPublicPropertyServiceTest {
    private final ListingRepository listings = mock(ListingRepository.class);
    private final LocalityRepository localities = mock(LocalityRepository.class);
    private final LeadPublicPropertyService service =
            new LeadPublicPropertyService(listings, localities);

    @Test
    void twentyByTwentyHistoryMembershipUsesOneListingAndOneLocalityBatch() {
        SupportedCity city = mock(SupportedCity.class);
        when(city.getId()).thenReturn(5L);
        Locality locality = mock(Locality.class);
        when(locality.getId()).thenReturn(70L);
        when(locality.getSupportedCity()).thenReturn(city);
        List<RentalDetails> rows = new ArrayList<>();
        for (long id = 101; id <= 120; id++) {
            RentalDetails listing = mock(RentalDetails.class);
            when(listing.getId()).thenReturn(id);
            when(listing.getStatus()).thenReturn(ListingStatus.ACTIVE);
            when(listing.getWorkflowStatus()).thenReturn(ListingWorkflowStatus.PUBLISHED);
            when(listing.getListingType()).thenReturn(ListingType.RENT);
            when(listing.getTitle()).thenReturn("Synthetic property " + id);
            when(listing.getCity()).thenReturn("Synthetic City");
            when(listing.getSector()).thenReturn("Synthetic Sector");
            when(listing.getBhkCount()).thenReturn("2 BHK");
            when(listing.getCanonicalLocalityId()).thenReturn(70L);
            when(listing.getMonthlyRent()).thenReturn(new BigDecimal("20000"));
            rows.add(listing);
        }
        when(listings.findAllByIdInAndStatus(any(), any())).thenReturn(List.copyOf(rows));
        when(localities.findAllById(any())).thenReturn(List.of(locality));
        LeadRoutingQueue lead = new LeadRoutingQueue();
        lead.setSupportedCity(city);
        lead.setIntent(LeadIntent.RENT);

        Map<Long, LeadPublicPropertyService.EligibleProperty> result =
                service.currentPublicPropertiesById(lead, rows.stream()
                        .map(RentalDetails::getId).toList(), false);

        assertEquals(20, result.size());
        verify(listings, times(1)).findAllByIdInAndStatus(any(), any());
        verify(localities, times(1)).findAllById(any());
    }

    @Test
    void activeButNonPublishedPropertyCannotEnterLeadContactFlow() {
        SupportedCity city = mock(SupportedCity.class);
        when(city.getId()).thenReturn(5L);
        when(city.getDisplayName()).thenReturn("Bhopal");
        Locality locality = mock(Locality.class);
        when(locality.getId()).thenReturn(70L);
        when(locality.getSectorName()).thenReturn("Arera Colony");
        when(locality.getSupportedCity()).thenReturn(city);
        RentalDetails listing = mock(RentalDetails.class);
        when(listing.getId()).thenReturn(101L);
        when(listing.getStatus()).thenReturn(ListingStatus.ACTIVE);
        when(listing.getWorkflowStatus()).thenReturn(com.indore.pathome.spaces.entity.ListingWorkflowStatus.DRAFT);
        when(listing.getListingType()).thenReturn(ListingType.RENT);
        when(listing.getCanonicalLocalityId()).thenReturn(70L);
        when(listings.findAllByIdInAndStatus(any(), any())).thenReturn(List.of(listing));
        when(localities.findAllById(any())).thenReturn(List.of(locality));
        LeadRoutingQueue lead = new LeadRoutingQueue();
        lead.setSupportedCity(city);
        lead.setIntent(LeadIntent.RENT);

        assertThrows(com.indore.pathome.spaces.exception.VisitOperationsConflictException.class,
                () -> service.requireCurrentPublicProperties(lead, List.of(101L)));
    }

    @Test
    void eligiblePublicFactsCarryOnlyCanonicalLocationAndDisplayablePropertyDetails() {
        SupportedCity city = mock(SupportedCity.class);
        when(city.getId()).thenReturn(5L);
        when(city.getDisplayName()).thenReturn("Bhopal");
        Locality locality = mock(Locality.class);
        when(locality.getId()).thenReturn(70L);
        when(locality.getSectorName()).thenReturn("Arera Colony");
        when(locality.getSupportedCity()).thenReturn(city);
        RentalDetails listing = mock(RentalDetails.class);
        when(listing.getId()).thenReturn(101L);
        when(listing.getStatus()).thenReturn(ListingStatus.ACTIVE);
        when(listing.getWorkflowStatus()).thenReturn(ListingWorkflowStatus.PUBLISHED);
        when(listing.getListingType()).thenReturn(ListingType.RENT);
        when(listing.getBhkCount()).thenReturn("2 BHK");
        when(listing.getTotalAreaSqFt()).thenReturn(915.5);
        when(listing.getCanonicalLocalityId()).thenReturn(70L);
        when(listing.getMonthlyRent()).thenReturn(new BigDecimal("21999"));
        when(listings.findAllByIdInAndStatus(any(), any())).thenReturn(List.of(listing));
        when(localities.findAllById(any())).thenReturn(List.of(locality));
        LeadRoutingQueue lead = new LeadRoutingQueue();
        lead.setSupportedCity(city);
        lead.setIntent(LeadIntent.RENT);

        LeadPublicPropertyService.EligibleProperty property =
                service.requireCurrentPublicProperties(lead, List.of(101L)).get(0);

        assertEquals("Arera Colony", property.locality());
        assertEquals("Bhopal", property.city());
        assertEquals("Arera Colony, Bhopal", property.location());
        assertEquals("2 BHK", property.bhk());
        assertEquals(new BigDecimal("21999"), property.monthlyRent());
        assertEquals(915.5, property.totalAreaSqFt());
    }

    @Test
    void identicalLocalityTextInAnotherCanonicalCityCannotEnterTheShortlist() {
        SupportedCity leadCity = mock(SupportedCity.class);
        when(leadCity.getId()).thenReturn(5L);
        SupportedCity foreignCity = mock(SupportedCity.class);
        when(foreignCity.getId()).thenReturn(6L);
        when(foreignCity.getDisplayName()).thenReturn("Another City");
        Locality locality = mock(Locality.class);
        when(locality.getId()).thenReturn(70L);
        when(locality.getSectorName()).thenReturn("Same Locality Name");
        when(locality.getSupportedCity()).thenReturn(foreignCity);
        RentalDetails listing = mock(RentalDetails.class);
        when(listing.getId()).thenReturn(101L);
        when(listing.getStatus()).thenReturn(ListingStatus.ACTIVE);
        when(listing.getWorkflowStatus()).thenReturn(ListingWorkflowStatus.PUBLISHED);
        when(listing.getListingType()).thenReturn(ListingType.RENT);
        when(listing.getCanonicalLocalityId()).thenReturn(70L);
        when(listings.findAllByIdInAndStatus(any(), any())).thenReturn(List.of(listing));
        when(localities.findAllById(any())).thenReturn(List.of(locality));
        LeadRoutingQueue lead = new LeadRoutingQueue();
        lead.setSupportedCity(leadCity);
        lead.setIntent(LeadIntent.RENT);

        assertThrows(com.indore.pathome.spaces.exception.VisitOperationsConflictException.class,
                () -> service.requireCurrentPublicProperties(lead, List.of(101L)));
    }

    @Test
    void buyIntentAndDuplicateSelectionsCannotUseRentalShortlist() {
        LeadRoutingQueue lead = new LeadRoutingQueue();
        lead.setIntent(LeadIntent.BUY);
        SupportedCity city = mock(SupportedCity.class);
        when(city.getId()).thenReturn(5L);
        lead.setSupportedCity(city);
        assertThrows(com.indore.pathome.spaces.exception.VisitOperationsConflictException.class,
                () -> service.requireCurrentPublicProperties(lead, List.of(101L)));

        lead.setIntent(LeadIntent.RENT);
        assertThrows(IllegalArgumentException.class,
                () -> service.requireCurrentPublicProperties(lead, List.of(101L, 101L)));
    }
}
