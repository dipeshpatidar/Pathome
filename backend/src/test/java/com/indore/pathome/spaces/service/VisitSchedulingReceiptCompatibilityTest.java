package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.dto.VisitSchedulingApprovalView;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B09/V50 receipts written before R1-OE-1 must still decode. The new timing-fact list is additive and
 * absent in historical JSON, so it must arrive as null/absent rather than failing the receipt read.
 */
class VisitSchedulingReceiptCompatibilityTest {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @Test
    void historicalReceiptWithoutLinkedRequestTimingFactsStillDecodes() throws Exception {
        String historical = """
                {
                  "session": {
                    "sessionId": 500,
                    "status": "SCHEDULED",
                    "version": 7,
                    "city": "Example City",
                    "items": [],
                    "linkedRequestVersions": [{"requestId": 11, "version": 3}],
                    "coordinatorUserId": 9,
                    "tenantConfirmationState": "PENDING"
                  },
                  "decisionId": 71,
                  "override": false,
                  "topRecommendedGroundExecutiveUserId": 22,
                  "topRecommendedAt": "2026-10-18T04:30:00Z"
                }
                """;

        VisitSchedulingApprovalView receipt = MAPPER.readValue(historical, VisitSchedulingApprovalView.class);

        assertNotNull(receipt.session());
        assertEquals(500L, receipt.session().sessionId());
        assertNull(receipt.session().linkedRequestTimingFacts());
        assertEquals(1, receipt.session().linkedRequestVersions().size());
        assertEquals(7L, receipt.session().version());
    }
}
