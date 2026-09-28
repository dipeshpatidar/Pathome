package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.dto.lessor.LandlordDraftData;
import com.indore.pathome.spaces.entity.PropertyType;
import com.indore.pathome.spaces.entity.PropertyUploadDraft;
import com.indore.pathome.spaces.entity.RentalMode;
import com.indore.pathome.spaces.exception.DraftConflictException;
import com.indore.pathome.spaces.repository.PropertyUploadDraftRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LandlordDraftServiceTest {
    private PropertyUploadDraftRepository drafts;
    private LandlordCapabilityService capabilities;
    private LandlordDraftService service;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setUp() {
        drafts = mock(PropertyUploadDraftRepository.class);
        capabilities = mock(LandlordCapabilityService.class);
        service = new LandlordDraftService(drafts, capabilities, mapper);
        when(capabilities.requireLandlordUserId("owner@example.com")).thenReturn(5L);
    }

    @Test
    void createsIndependentOwnedDraftsWithoutAdminIdentity() {
        List<PropertyUploadDraft> saved = new ArrayList<>();
        when(drafts.saveAndFlush(any())).thenAnswer(invocation -> {
            PropertyUploadDraft draft = invocation.getArgument(0);
            saved.add(draft);
            return draft;
        });
        var basics = new LandlordDraftData.Basics(PropertyType.FLAT, RentalMode.LONG_TERM_RENTAL, null);

        var first = service.create("owner@example.com", basics);
        var second = service.create("owner@example.com", basics);

        assertNotEquals(first.draftId(), second.draftId());
        assertEquals(2, saved.size());
        assertTrue(saved.stream().allMatch(d -> d.getLandlordUserId().equals(5L)
                && d.getAdminId() == null && "DRAFT".equals(d.getStatus())));
        assertTrue(first.completionPercent() < 100);
    }

    @Test
    void updatesOnlyOwnedSectionAndRejectsStaleVersion() throws Exception {
        PropertyUploadDraft draft = storedDraft(5L);
        when(drafts.findByDraftIdAndLandlordUserId("draft-one", 5L)).thenReturn(Optional.of(draft));
        when(drafts.saveAndFlush(any())).thenAnswer(invocation -> {
            PropertyUploadDraft saved = invocation.getArgument(0);
            saved.setVersion(2);
            return saved;
        });

        var result = service.updatePricing("owner@example.com", "draft-one", 1,
                new LandlordDraftData.Pricing(new BigDecimal("22000"), BigDecimal.ZERO));

        assertEquals(2, result.version());
        assertEquals(BigDecimal.ZERO, result.data().pricing().securityDeposit());
        assertEquals(PropertyType.FLAT, result.data().basics().propertyType());
        assertThrows(DraftConflictException.class, () -> service.updatePricing("owner@example.com", "draft-one", 1,
                new LandlordDraftData.Pricing(new BigDecimal("25000"), BigDecimal.ZERO)));
    }

    @Test
    void crossOwnerDraftIdIsNotFound() {
        when(drafts.findByDraftIdAndLandlordUserId("other-draft", 5L)).thenReturn(Optional.empty());
        assertThrows(EntityNotFoundException.class, () -> service.get("owner@example.com", "other-draft"));
        verify(drafts, never()).findByDraftId("other-draft");
    }

    @Test
    void rejectsLandAndShortStayInPhaseOneB() {
        assertThrows(IllegalArgumentException.class, () -> service.create("owner@example.com",
                new LandlordDraftData.Basics(PropertyType.LAND, RentalMode.LONG_TERM_RENTAL, "2BHK")));
        assertThrows(IllegalArgumentException.class, () -> service.create("owner@example.com",
                new LandlordDraftData.Basics(PropertyType.FLAT, RentalMode.SHORT_STAY, "2BHK")));
        verify(drafts, never()).saveAndFlush(any());
    }

    private PropertyUploadDraft storedDraft(Long ownerId) throws Exception {
        PropertyUploadDraft draft = new PropertyUploadDraft();
        draft.setDraftId("draft-one");
        draft.setLandlordUserId(ownerId);
        draft.setVersion(1);
        draft.setStatus("DRAFT");
        draft.setPayload(mapper.writeValueAsString(new LandlordDraftData(
                new LandlordDraftData.Basics(PropertyType.FLAT, RentalMode.LONG_TERM_RENTAL, "2BHK"),
                null, null, null)));
        return draft;
    }
}
