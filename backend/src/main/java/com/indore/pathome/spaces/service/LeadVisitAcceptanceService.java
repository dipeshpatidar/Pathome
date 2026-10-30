package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.CreateOwnVisitRequestCommand;
import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.OwnVisitRequestView;
import com.indore.pathome.spaces.entity.LeadVisitAssociationStatus;
import org.springframework.stereotype.Service;

@Service
public class LeadVisitAcceptanceService {
    private final PropertyVisitRequestApplicationService requests;
    private final LeadVisitAssociationService associations;

    public LeadVisitAcceptanceService(PropertyVisitRequestApplicationService requests,
                                      LeadVisitAssociationService associations) {
        this.requests = requests;
        this.associations = associations;
    }

    /**
     * The Request transaction commits first. Any optional invitation response or association failure
     * is reported as partial success and can never erase or relabel the valid Visit Request.
     */
    public OwnVisitRequestView requestVisit(Long tenantId, String continuationToken,
                                            CreateOwnVisitRequestCommand command) {
        OwnVisitRequestView request = requests.createOwn(tenantId, command);
        if (request.requiresAcknowledgement() || request.requestId() == null) return request;
        if (command.handoffContextId() == null || continuationToken == null
                || continuationToken.isBlank()) {
            return withAssociation(request, LeadVisitAssociationStatus.UNAVAILABLE,
                    request.message() + " No active invitation context was changed.");
        }
        try {
            var response = associations.recordOwnRequest(tenantId, command.handoffContextId(),
                    continuationToken, request.requestId(), command.associateEnquiry(),
                    command.consentWordingVersion(), null);
            return withAssociation(request, response.associationStatus(),
                    request.message() + " " + response.message());
        } catch (RuntimeException exception) {
            return withAssociation(request, LeadVisitAssociationStatus.UNAVAILABLE,
                    request.message()
                            + " Enquiry association is pending/unavailable; no second Visit Request was created.");
        }
    }

    private static OwnVisitRequestView withAssociation(OwnVisitRequestView request,
                                                       LeadVisitAssociationStatus status,
                                                       String message) {
        return new OwnVisitRequestView(request.requestId(), request.propertyId(),
                request.requestVersion(), request.status(), request.created(),
                request.requiresAcknowledgement(),
                status, message, request.receivedAt());
    }
}
