package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.dto.CreatePropertyVisitRequest;
import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.CreateOwnVisitRequestCommand;
import com.indore.pathome.spaces.entity.ListingStatus;
import com.indore.pathome.spaces.entity.ListingWorkflowStatus;
import com.indore.pathome.spaces.entity.LeadAssistanceCommandReceipt;
import com.indore.pathome.spaces.entity.PropertyVisitRequest;
import com.indore.pathome.spaces.entity.RentalDetails;
import com.indore.pathome.spaces.entity.Role;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.entity.VisitRequestStatus;
import com.indore.pathome.spaces.exception.VisitOperationsConflictException;
import com.indore.pathome.spaces.repository.LeadAssistanceCommandReceiptRepository;
import com.indore.pathome.spaces.repository.ListingRepository;
import com.indore.pathome.spaces.repository.PropertyVisitRequestRepository;
import com.indore.pathome.spaces.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PropertyVisitRequestApplicationServiceTest {
    private final UserRepository users = mock(UserRepository.class);
    private final ListingRepository listings = mock(ListingRepository.class);
    private final PropertyVisitRequestRepository requests = mock(PropertyVisitRequestRepository.class);
    private final PropertyVisitRequestAttemptService attempts = mock(PropertyVisitRequestAttemptService.class);
    private final LeadAssistanceCommandReceiptRepository receipts =
            mock(LeadAssistanceCommandReceiptRepository.class);
    private final LeadDataProtection protection = mock(LeadDataProtection.class);
    private final PropertyVisitRequestApplicationService service =
            new PropertyVisitRequestApplicationService(users, listings, requests, attempts, receipts,
                    protection, new ObjectMapper().findAndRegisterModules());

    @Test
    void newOwnRequestUsesServerTenantAndCurrentExactPropertyWithoutLeadOrEnquiryProof() {
        User tenant = tenant(7L);
        RentalDetails listing = listing(31L, 4L);
        PropertyVisitRequest created = request(tenant, listing, VisitRequestStatus.RECEIVED);
        when(users.findById(7L)).thenReturn(Optional.of(tenant));
        when(listings.findById(31L)).thenReturn(Optional.of(listing));
        when(requests.findByTenantIdAndListingId(7L, 31L)).thenReturn(Optional.empty());
        when(protection.keyedDigest(anyString(), anyString())).thenReturn("a".repeat(64));
        when(attempts.create(eq(tenant), eq(31L), any(), any(), anyString(), any(), eq(4L)))
                .thenReturn(new PropertyVisitRequestAttemptService.CreateResult(created, true));

        var result = service.createOwn(7L, command(31L, 4L, false));

        assertTrue(result.created());
        assertFalse(result.requiresAcknowledgement());
        assertEquals(VisitRequestStatus.RECEIVED.name(), result.status());
        verify(users).findById(7L);
        verify(attempts).create(eq(tenant), eq(31L), any(), any(), anyString(), any(), eq(4L));
    }

    @Test
    void existingOwnRequestRequiresDeliberateAcknowledgementAndCreatesNothing() {
        User tenant = tenant(7L);
        RentalDetails listing = listing(31L, 4L);
        PropertyVisitRequest existing = request(tenant, listing, VisitRequestStatus.RECEIVED);
        when(users.findById(7L)).thenReturn(Optional.of(tenant));
        when(listings.findById(31L)).thenReturn(Optional.of(listing));
        when(requests.findByTenantIdAndListingId(7L, 31L)).thenReturn(Optional.of(existing));

        var result = service.createOwn(7L, command(31L, 4L, false));

        assertTrue(result.requiresAcknowledgement());
        assertFalse(result.created());
        verify(attempts, never()).create(any(), anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void terminalHistoryCannotBeResurrectedOrDuplicated() {
        User tenant = tenant(7L);
        RentalDetails listing = listing(31L, 4L);
        PropertyVisitRequest cancelled = request(tenant, listing, VisitRequestStatus.CANCELLED);
        when(users.findById(7L)).thenReturn(Optional.of(tenant));
        when(listings.findById(31L)).thenReturn(Optional.of(listing));
        when(requests.findByTenantIdAndListingId(7L, 31L)).thenReturn(Optional.of(cancelled));

        assertThrows(VisitOperationsConflictException.class,
                () -> service.createOwn(7L, command(31L, 4L, false)));
        verify(attempts, never()).create(any(), anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void deliberateAcknowledgementRequiresCurrentVersionAndIsRecordedAsTypedOutcome() {
        User tenant = tenant(7L);
        RentalDetails listing = listing(31L, 4L);
        PropertyVisitRequest existing = request(tenant, listing, VisitRequestStatus.RECEIVED);
        CreateOwnVisitRequestCommand command = acknowledgedCommand(3L);
        when(users.findById(7L)).thenReturn(Optional.of(tenant));
        when(listings.findById(31L)).thenReturn(Optional.of(listing));
        when(requests.findByTenantIdAndListingId(7L, 31L)).thenReturn(Optional.of(existing));
        when(protection.keyedDigest(anyString(), anyString())).thenReturn("a".repeat(64));
        when(attempts.acknowledge(eq(tenant), eq(55L), eq(31L), eq(command.operationId()),
                eq("a".repeat(64)), eq(3L))).thenReturn(existing);

        var result = service.createOwn(7L, command);

        assertFalse(result.created());
        assertFalse(result.requiresAcknowledgement());
        assertEquals(3L, result.requestVersion());
        assertEquals(55L, result.requestId());
        verify(attempts).acknowledge(eq(tenant), eq(55L), eq(31L),
                eq(command.operationId()), eq("a".repeat(64)), eq(3L));
        verify(attempts, never()).create(any(), anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void acknowledgementWithoutTheReturnedRequestVersionIsRejectedBeforeMutation() {
        User tenant = tenant(7L);
        RentalDetails listing = listing(31L, 4L);
        when(users.findById(7L)).thenReturn(Optional.of(tenant));
        when(listings.findById(31L)).thenReturn(Optional.of(listing));

        assertThrows(IllegalArgumentException.class,
                () -> service.createOwn(7L, command(31L, 4L, true)));
        verify(attempts, never()).create(any(), anyLong(), any(), any(), any(), any(), any());
        verify(attempts, never()).acknowledge(any(), anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void legacyMissingAndInactivePropertyRemainNotFoundInsteadOfConflict() {
        when(users.findById(7L)).thenReturn(Optional.of(tenant(7L)));
        when(listings.findById(31L)).thenReturn(Optional.empty());
        assertThrows(jakarta.persistence.EntityNotFoundException.class,
                () -> service.requestLegacy(7L, 31L, requestCommand()));

        RentalDetails inactive = listing(32L, 1L);
        inactive.setStatus(ListingStatus.CLOSED);
        when(listings.findById(32L)).thenReturn(Optional.of(inactive));
        assertThrows(jakarta.persistence.EntityNotFoundException.class,
                () -> service.requestLegacy(7L, 32L, requestCommand()));
    }

    @Test
    void legacyRequestRejectsAnActiveButCurrentlyUnpublishedPropertyBeforeMutation() {
        User tenant = tenant(7L);
        RentalDetails paused = listing(31L, 4L);
        paused.setWorkflowStatus(ListingWorkflowStatus.PAUSED);
        PropertyVisitRequest wouldBeCreated = request(tenant, paused, VisitRequestStatus.RECEIVED);
        when(users.findById(7L)).thenReturn(Optional.of(tenant));
        when(listings.findById(31L)).thenReturn(Optional.of(paused));
        when(attempts.create(eq(tenant), eq(31L), any(), any(), any(), any(), any()))
                .thenReturn(new PropertyVisitRequestAttemptService.CreateResult(
                        wouldBeCreated, true));

        assertThrows(jakarta.persistence.EntityNotFoundException.class,
                () -> service.requestLegacy(7L, 31L, requestCommand()));
        verify(attempts, never()).create(any(), anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void uniquenessLoserReturnsCreatedFalseWithoutDuplicateRouting() {
        User tenant = tenant(7L);
        RentalDetails listing = listing(31L, 4L);
        PropertyVisitRequest winner = request(tenant, listing, VisitRequestStatus.RECEIVED);
        when(users.findById(7L)).thenReturn(Optional.of(tenant));
        when(listings.findById(31L)).thenReturn(Optional.of(listing));
        when(requests.findByTenantIdAndListingId(7L, 31L))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(attempts.create(eq(tenant), eq(31L), any(), any(), any(), any(), any()))
                .thenThrow(new DataIntegrityViolationException(
                        "uk_property_visit_request_tenant_listing"));

        var result = service.requestLegacy(7L, 31L, requestCommand());

        assertFalse(result.created());
        assertEquals(winner.getId(), result.acknowledgement().requestId());
    }

    @Test
    void sameOperationReplayReturnsOnlyTheCallersStoredRequest() {
        User tenant = tenant(7L);
        RentalDetails listing = listing(31L, 4L);
        PropertyVisitRequest stored = request(tenant, listing, VisitRequestStatus.RECEIVED);
        UUID operationId = UUID.randomUUID();
        LeadAssistanceCommandReceipt receipt = new LeadAssistanceCommandReceipt(tenant, operationId,
                PropertyVisitRequestAttemptService.COMMAND_ACTION, "a".repeat(64), "55",
                Instant.parse("2026-10-27T10:00:00Z"));
        when(users.findById(7L)).thenReturn(Optional.of(tenant));
        when(listings.findById(31L)).thenReturn(Optional.of(listing));
        when(protection.keyedDigest(anyString(), anyString())).thenReturn("a".repeat(64));
        when(receipts.findAllByActor_IdAndOperationId(7L, operationId))
                .thenReturn(java.util.List.of(receipt));
        when(requests.findById(55L)).thenReturn(Optional.of(stored));

        var result = service.createOwn(7L, command(operationId, 31L, 4L));

        assertEquals(55L, result.requestId());
        assertFalse(result.created());
        verify(attempts, never()).create(any(), anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void sameOperationWithChangedPayloadIsAnAuthorizedConflict() {
        User tenant = tenant(7L);
        RentalDetails listing = listing(31L, 4L);
        UUID operationId = UUID.randomUUID();
        LeadAssistanceCommandReceipt receipt = new LeadAssistanceCommandReceipt(tenant, operationId,
                PropertyVisitRequestAttemptService.COMMAND_ACTION, "b".repeat(64), "55",
                Instant.parse("2026-10-27T10:00:00Z"));
        when(users.findById(7L)).thenReturn(Optional.of(tenant));
        when(listings.findById(31L)).thenReturn(Optional.of(listing));
        when(protection.keyedDigest(anyString(), anyString())).thenReturn("a".repeat(64));
        when(receipts.findAllByActor_IdAndOperationId(7L, operationId))
                .thenReturn(java.util.List.of(receipt));
        when(requests.findById(55L)).thenReturn(Optional.of(request(tenant, listing,
                VisitRequestStatus.RECEIVED)));

        assertThrows(VisitOperationsConflictException.class,
                () -> service.createOwn(7L, command(operationId, 31L, 4L)));
        verify(attempts, never()).create(any(), anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void anotherTenantsRequestIdIsConcealedAsAbsent() {
        User owner = tenant(7L);
        PropertyVisitRequest foreign = request(owner, listing(31L, 4L),
                VisitRequestStatus.RECEIVED);
        when(requests.findById(55L)).thenReturn(Optional.of(foreign));

        assertThrows(jakarta.persistence.EntityNotFoundException.class,
                () -> service.requireOwnRequest(8L, 55L));
    }

    private static CreateOwnVisitRequestCommand command(long listingId, long version,
                                                        boolean acknowledgeExisting) {
        return new CreateOwnVisitRequestCommand(UUID.randomUUID(), listingId, version, null, true,
                acknowledgeExisting, null, false, null,
                new CreatePropertyVisitRequest(null, null, null, null,
                        "Saturday afternoon", null));
    }

    private static CreateOwnVisitRequestCommand command(UUID operationId, long listingId,
                                                        long version) {
        return new CreateOwnVisitRequestCommand(operationId, listingId, version, null, true,
                false, null, false, null,
                new CreatePropertyVisitRequest(null, null, null, null,
                        "Saturday afternoon", null));
    }

    private static CreateOwnVisitRequestCommand acknowledgedCommand(long expectedRequestVersion) {
        return new CreateOwnVisitRequestCommand(UUID.randomUUID(), 31L, 4L,
                expectedRequestVersion, true, true, null, false, null,
                new CreatePropertyVisitRequest(null, null, null, null,
                        "Saturday afternoon", null));
    }

    private static CreatePropertyVisitRequest requestCommand() {
        return new CreatePropertyVisitRequest(null, null, null, null,
                "Saturday afternoon", null);
    }

    private static User tenant(Long id) {
        User user = new User();
        user.setId(id);
        user.setRole(Role.ROLE_TENANT);
        return user;
    }

    private static RentalDetails listing(Long id, Long version) {
        RentalDetails listing = new RentalDetails();
        listing.setId(id);
        listing.setVersion(version);
        listing.setStatus(ListingStatus.ACTIVE);
        listing.setWorkflowStatus(ListingWorkflowStatus.PUBLISHED);
        return listing;
    }

    private static PropertyVisitRequest request(User tenant, RentalDetails listing,
                                                VisitRequestStatus status) {
        PropertyVisitRequest request = new PropertyVisitRequest();
        request.setId(55L);
        request.setTenant(tenant);
        request.setListing(listing);
        request.setStatus(status);
        request.setVersion(3L);
        return request;
    }
}
