package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.*;
import com.indore.pathome.spaces.exception.DraftConflictException;
import com.indore.pathome.spaces.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BackofficeMediaServiceTest {
    private static final Long USER_ID = 9L;
    private static final Long LISTING_ID = 42L;
    private static final Long CITY_ID = 7L;
    private static final String DRAFT_ID = "staff-draft";

    private final PropertyUploadDraftRepository drafts = mock(PropertyUploadDraftRepository.class);
    private final PropertyDraftMediaRepository media = mock(PropertyDraftMediaRepository.class);
    private final ListingRepository listings = mock(ListingRepository.class);
    private final LocalityRepository localities = mock(LocalityRepository.class);
    private final BackofficePropertyAccessService access = mock(BackofficePropertyAccessService.class);
    private final LandlordMediaService fileValidation = mock(LandlordMediaService.class);
    private final CloudinaryService cloudinary = mock(CloudinaryService.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final BackofficeMediaService service = new BackofficeMediaService(
            drafts, media, listings, localities, access, fileValidation, cloudinary, transactions);

    private RentalDetails listing;
    private PropertyUploadDraft draft;
    private final AtomicReference<PropertyDraftMedia> saved = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        when(transactions.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());

        SupportedCity city = new SupportedCity("indore", "Indore", true);
        ReflectionTestUtils.setField(city, "id", CITY_ID);
        Locality locality = new Locality("Indore", "Vijay Nagar", null, 0, 0.0);
        locality.setId(11L);
        locality.setSupportedCity(city);
        listing = new RentalDetails();
        listing.setId(LISTING_ID);
        listing.setCanonicalLocalityId(11L);
        when(listings.findById(LISTING_ID)).thenReturn(Optional.of(listing));
        when(listings.lockForSchedulingById(LISTING_ID)).thenReturn(Optional.of(LISTING_ID));
        when(localities.findById(11L)).thenReturn(Optional.of(locality));

        draft = new PropertyUploadDraft();
        draft.setDraftId(DRAFT_ID);
        draft.setPublishedPropertyId(LISTING_ID);
        draft.setStaffMakerUserId(USER_ID);
        draft.setLessorProfileId(15L);
        draft.setRevisionMode("STAFF_DIRECT");
        draft.setStatus("DRAFT");
        draft.setVersion(3);
        when(drafts.findStaffDraftForUpdate(DRAFT_ID, USER_ID)).thenReturn(Optional.of(draft));
        when(fileValidation.validateFile(any())).thenReturn("image/jpeg");
        when(media.findByDraftIdOrderBySortOrderAscIdAsc(DRAFT_ID)).thenReturn(java.util.List.of());
        when(media.saveAndFlush(any(PropertyDraftMedia.class))).thenAnswer(call -> {
            PropertyDraftMedia row = call.getArgument(0);
            saved.set(row);
            return row;
        });
    }

    @Test
    void newUploadTakesStaffOwnershipOnlyFromTheAuthorizedParentDraft() {
        String mediaId = UUID.randomUUID().toString();
        when(media.findByMediaId(mediaId)).thenAnswer(call -> Optional.ofNullable(saved.get()));
        when(cloudinary.findExistingResourceByUploadRequestId(any(), eq(false)))
                .thenReturn(Optional.of(new CloudinaryService.CloudinaryUploadResult(
                        "https://cdn.example.test/property.webp", "staff-media", "image")));

        var result = service.upload(USER_ID, LISTING_ID, DRAFT_ID, mediaId, 3,
                new MockMultipartFile("file", "property.jpg", "image/jpeg", new byte[]{1}));

        assertEquals(mediaId, result.mediaId());
        assertEquals(USER_ID, saved.get().getStaffMakerUserId());
        assertEquals(null, saved.get().getAdminId());
        assertEquals(null, saved.get().getLandlordUserId());
        assertEquals(false, saved.get().getGuestOwned());
        verify(cloudinary, times(1))
                .findExistingResourceByUploadRequestId(any(), eq(false));
    }

    @Test
    void anotherStaffEditorsMediaIdCannotBeReusedConcealedOrCompleted() {
        String mediaId = UUID.randomUUID().toString();
        PropertyDraftMedia foreign = new PropertyDraftMedia();
        foreign.setMediaId(mediaId);
        foreign.setDraftId(DRAFT_ID);
        foreign.setStaffMakerUserId(88L);
        foreign.setUploadStatus("UPLOADED");
        when(media.findByMediaId(mediaId)).thenReturn(Optional.of(foreign));

        assertThrows(DraftConflictException.class, () -> service.upload(
                USER_ID, LISTING_ID, DRAFT_ID, mediaId, 3,
                new MockMultipartFile(
                        "file", "property.jpg", "image/jpeg", new byte[]{1})));
        verifyNoInteractions(cloudinary);
    }
}
