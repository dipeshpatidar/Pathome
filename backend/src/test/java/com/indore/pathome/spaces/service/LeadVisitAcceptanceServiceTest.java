package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.CreatePropertyVisitRequest;
import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.CreateOwnVisitRequestCommand;
import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.OwnVisitRequestView;
import com.indore.pathome.spaces.entity.LeadVisitAssociationStatus;
import com.indore.pathome.spaces.exception.VisitOperationsConflictException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LeadVisitAcceptanceServiceTest {
    private final PropertyVisitRequestApplicationService requests =
            mock(PropertyVisitRequestApplicationService.class);
    private final LeadVisitAssociationService associations =
            mock(LeadVisitAssociationService.class);
    private final LeadVisitAcceptanceService service =
            new LeadVisitAcceptanceService(requests, associations);

    @Test
    void associationFailureCannotEraseCommittedOwnRequest() {
        UUID context = UUID.randomUUID();
        CreateOwnVisitRequestCommand command = command(context, true);
        OwnVisitRequestView request = new OwnVisitRequestView(55L, 31L, 3L, "RECEIVED",
                true, false, LeadVisitAssociationStatus.UNAVAILABLE,
                "Visit Request recorded.", LocalDateTime.of(2026, 10, 27, 10, 0));
        when(requests.createOwn(eq(7L), any())).thenReturn(request);
        when(associations.recordOwnRequest(eq(7L), eq(context), eq("opaque"),
                eq(55L), eq(true), eq("V1"), eq(null)))
                .thenThrow(new VisitOperationsConflictException("association unavailable"));

        OwnVisitRequestView result = service.requestVisit(7L, "opaque", command);

        assertEquals(55L, result.requestId());
        assertTrue(result.created());
        assertEquals(LeadVisitAssociationStatus.UNAVAILABLE, result.associationStatus());
        assertTrue(result.message().contains("no second Visit Request"));
    }

    @Test
    void existingRequestAcknowledgementDoesNotAttemptAssociation() {
        CreateOwnVisitRequestCommand command = command(UUID.randomUUID(), true);
        OwnVisitRequestView requiresAck = new OwnVisitRequestView(55L, 31L, 3L, "RECEIVED",
                false, true, LeadVisitAssociationStatus.UNAVAILABLE,
                "Review existing request.", LocalDateTime.of(2026, 10, 27, 10, 0));
        when(requests.createOwn(eq(7L), any())).thenReturn(requiresAck);

        OwnVisitRequestView result = service.requestVisit(7L, "opaque", command);

        assertEquals(requiresAck, result);
    }

    private static CreateOwnVisitRequestCommand command(UUID context, boolean associate) {
        return new CreateOwnVisitRequestCommand(UUID.randomUUID(), 31L, 4L, null, true,
                false, context, associate, associate ? "V1" : null,
                new CreatePropertyVisitRequest(null, null, null, null,
                        "Saturday afternoon", null));
    }
}
