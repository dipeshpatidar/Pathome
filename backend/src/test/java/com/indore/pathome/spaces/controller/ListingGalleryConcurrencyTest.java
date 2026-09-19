package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.entity.Listing;
import com.indore.pathome.spaces.entity.RentalDetails;
import com.indore.pathome.spaces.repository.ListingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class ListingGalleryConcurrencyTest {

    @Mock
    private ListingRepository listingRepository;

    private PropertyController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        controller = new PropertyController(
                listingRepository,
                mock(com.indore.pathome.spaces.repository.PropertyMediaAssetRepository.class),
                mock(com.indore.pathome.spaces.service.CloudinaryService.class),
                mock(com.indore.pathome.spaces.service.FailedUploadService.class),
                mock(com.indore.pathome.spaces.service.PropertyParserService.class),
                mock(com.indore.pathome.spaces.service.ParserLearningService.class),
                mock(com.indore.pathome.spaces.service.ParserLearningCaptureService.class),
                mock(com.indore.pathome.spaces.service.BatchPropertyPublishingService.class),
                null
        );
    }

    @Test
    @DisplayName("16. Empty/null gallery + first URL: appends without malformed leading comma")
    void emptyGallery_appendsFirstUrlCleanly() {
        RentalDetails listing = new RentalDetails();
        listing.setId(100L);
        listing.setMediaGalleryUrls(null);

        // Simulate database atomic append behavior
        AtomicReference<String> dbGallery = new AtomicReference<>(null);
        when(listingRepository.appendMediaGalleryUrlAtomic(eq(100L), anyString()))
                .thenAnswer(invocation -> {
                    String url = invocation.getArgument(1);
                    dbGallery.updateAndGet(existing -> (existing == null || existing.isBlank()) ? url : existing + "," + url);
                    return 1;
                });

        // Trigger gallery update via controller's internal update logic
        when(listingRepository.findById(100L)).thenReturn(java.util.Optional.of(listing));

        // Use reflection to verify updateListingGallery directly
        try {
            java.lang.reflect.Method method = PropertyController.class.getDeclaredMethod("updateListingGallery", Listing.class, String.class);
            method.setAccessible(true);
            method.invoke(controller, listing, "https://cdn.example/first.webp");
        } catch (Exception e) {
            fail("Reflection invocation failed: " + e.getMessage());
        }

        verify(listingRepository).appendMediaGalleryUrlAtomic(100L, "https://cdn.example/first.webp");
        verify(listingRepository, never()).save(any(Listing.class));
        assertEquals("https://cdn.example/first.webp", dbGallery.get());
        assertEquals("https://cdn.example/first.webp", listing.getMediaGalleryUrls());
    }

    @Test
    @DisplayName("17 & 20. Existing gallery + new URL: appends with single comma separator and preserves existing URLs")
    void existingGallery_appendsNewPreservingExisting() {
        RentalDetails listing = new RentalDetails();
        listing.setId(101L);
        listing.setMediaGalleryUrls("https://cdn.example/old1.webp,https://cdn.example/old2.webp");

        AtomicReference<String> dbGallery = new AtomicReference<>("https://cdn.example/old1.webp,https://cdn.example/old2.webp");
        when(listingRepository.appendMediaGalleryUrlAtomic(eq(101L), anyString()))
                .thenAnswer(invocation -> {
                    String url = invocation.getArgument(1);
                    dbGallery.updateAndGet(existing -> existing + "," + url);
                    return 1;
                });

        try {
            java.lang.reflect.Method method = PropertyController.class.getDeclaredMethod("updateListingGallery", Listing.class, String.class);
            method.setAccessible(true);
            method.invoke(controller, listing, "https://cdn.example/new3.webp");
        } catch (Exception e) {
            fail("Reflection failed: " + e.getMessage());
        }

        verify(listingRepository).appendMediaGalleryUrlAtomic(101L, "https://cdn.example/new3.webp");
        verify(listingRepository, never()).save(any(Listing.class));
        assertEquals("https://cdn.example/old1.webp,https://cdn.example/old2.webp,https://cdn.example/new3.webp", dbGallery.get());
    }

    @Test
    @DisplayName("18 & 19. Multiple concurrent gallery appends (concurrency = 3): all URLs survive without lost updates")
    void concurrentUploads_allThreeUrlsSurvive() throws InterruptedException {
        Long listingId = 102L;
        RentalDetails listing = new RentalDetails();
        listing.setId(listingId);
        listing.setMediaGalleryUrls("");

        // Thread-safe atomic simulation replicating the PostgreSQL atomic CASE logic
        AtomicReference<String> atomicPostgresRow = new AtomicReference<>("");
        when(listingRepository.appendMediaGalleryUrlAtomic(eq(listingId), anyString()))
                .thenAnswer(invocation -> {
                    String cdnUrl = invocation.getArgument(1);
                    atomicPostgresRow.updateAndGet(current -> {
                        if (current == null || current.isBlank()) return cdnUrl;
                        if (("," + current + ",").contains("," + cdnUrl + ",")) return current;
                        return current + "," + cdnUrl;
                    });
                    return 1;
                });

        int numWorkers = 3;
        ExecutorService executor = Executors.newFixedThreadPool(numWorkers);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numWorkers);

        List<String> workerUrls = List.of(
                "https://cdn.example/workerA.webp",
                "https://cdn.example/workerB.webp",
                "https://cdn.example/workerC.webp"
        );

        for (int i = 0; i < numWorkers; i++) {
            final String url = workerUrls.get(i);
            executor.submit(() -> {
                try {
                    startLatch.await();
                    java.lang.reflect.Method method = PropertyController.class.getDeclaredMethod("updateListingGallery", Listing.class, String.class);
                    method.setAccessible(true);
                    method.invoke(controller, listing, url);
                } catch (Exception e) {
                    fail("Worker failed: " + e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // Release all 3 workers simultaneously
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS), "Workers timed out");
        executor.shutdown();

        // Verify that atomic query was called 3 times
        verify(listingRepository, times(3)).appendMediaGalleryUrlAtomic(eq(listingId), anyString());
        // Verify listingRepository.save was NEVER called (preventing JPA dirty write overwrite)
        verify(listingRepository, never()).save(any(Listing.class));

        // Verify all 3 URLs are present in the atomic database string
        String result = atomicPostgresRow.get();
        assertNotNull(result);
        for (String expectedUrl : workerUrls) {
            assertTrue(result.contains(expectedUrl), "Result must contain " + expectedUrl + " but was: " + result);
        }
        assertEquals(3, result.split(",").length, "Must contain exactly 3 URLs");
    }

    @Test
    @DisplayName("21. Duplicate idempotent request does not append duplicate gallery URL")
    void duplicateIdempotentRequest_doesNotAppendDuplicate() {
        RentalDetails listing = new RentalDetails();
        listing.setId(103L);
        listing.setMediaGalleryUrls("https://cdn.example/photo.webp");

        AtomicReference<String> atomicPostgresRow = new AtomicReference<>("https://cdn.example/photo.webp");
        when(listingRepository.appendMediaGalleryUrlAtomic(eq(103L), anyString()))
                .thenAnswer(invocation -> {
                    String cdnUrl = invocation.getArgument(1);
                    atomicPostgresRow.updateAndGet(current -> {
                        if (("," + current + ",").contains("," + cdnUrl + ",")) return current;
                        return current + "," + cdnUrl;
                    });
                    return 1;
                });

        try {
            java.lang.reflect.Method method = PropertyController.class.getDeclaredMethod("updateListingGallery", Listing.class, String.class);
            method.setAccessible(true);
            method.invoke(controller, listing, "https://cdn.example/photo.webp");
        } catch (Exception e) {
            fail("Reflection failed: " + e.getMessage());
        }

        assertEquals("https://cdn.example/photo.webp", atomicPostgresRow.get());
        assertEquals("https://cdn.example/photo.webp", listing.getMediaGalleryUrls());
    }

    @Test
    @DisplayName("22 & 23. Null/blank input handling: ignores blank URLs, does not produce malformed commas")
    void blankUrl_ignoredSafely() {
        RentalDetails listing = new RentalDetails();
        listing.setId(104L);
        listing.setMediaGalleryUrls("https://cdn.example/photo.webp");

        try {
            java.lang.reflect.Method method = PropertyController.class.getDeclaredMethod("updateListingGallery", Listing.class, String.class);
            method.setAccessible(true);
            method.invoke(controller, listing, "");
            method.invoke(controller, listing, "   ");
            method.invoke(controller, listing, (String) null);
        } catch (Exception e) {
            fail("Reflection failed: " + e.getMessage());
        }

        verify(listingRepository, never()).appendMediaGalleryUrlAtomic(anyLong(), anyString());
        assertEquals("https://cdn.example/photo.webp", listing.getMediaGalleryUrls());
    }
}
