package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.Listing;
import com.indore.pathome.spaces.entity.RentalDetails;
import com.indore.pathome.spaces.repository.ListingRepository;
import com.indore.pathome.spaces.repository.PropertyMediaAssetRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BatchPropertyPublishingServiceTest {

    @Mock
    private ListingRepository listingRepository;

    @Mock
    private PropertyMediaAssetRepository mediaAssetRepository;

    @Mock
    private PropertyParserService propertyParserService;

    @Test
    void eachBatchPropertyUsesAnIndependentTransaction() throws Exception {
        Transactional transactional = BatchPropertyPublishingService.class
                .getMethod("publish", RentalDetails.class, List.class)
                .getAnnotation(Transactional.class);

        assertEquals(Propagation.REQUIRES_NEW, transactional.propagation());
    }

    @Test
    void mediaPersistenceFailureEscapesSoThePropertyTransactionCanRollBack() {
        RentalDetails listing = listing();
        listing.setId(42L);
        when(listingRepository.saveAndFlush(listing)).thenReturn(listing);
        doThrow(new IllegalStateException("media write failed"))
                .when(mediaAssetRepository).saveAllAndFlush(anyList());
        BatchPropertyPublishingService service = new BatchPropertyPublishingService(
                listingRepository, mediaAssetRepository, propertyParserService);

        assertThrows(IllegalStateException.class,
                () -> service.publish(listing, List.of("https://cdn.example/property.jpg")));
        verify(listingRepository).saveAndFlush(listing);
    }

    private RentalDetails listing() {
        RentalDetails listing = new RentalDetails();
        listing.setCity("Indore");
        listing.setSector("Vijay Nagar");
        listing.setMonthlyRent(BigDecimal.valueOf(18000));
        return listing;
    }
}
