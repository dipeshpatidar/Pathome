package com.indore.pathome.spaces.dto;

import com.indore.pathome.spaces.entity.VisitSessionItemOutcomeState;
import com.indore.pathome.spaces.entity.VisitSessionItemSkipReason;
import java.util.UUID;

public record CorrectVisitOutcomeCommand(
        Long itemId,
        VisitSessionItemOutcomeState outcome,
        VisitSessionItemSkipReason skipReason,
        String privateNote,
        String correctionReason,
        Long expectedReportVersion,
        Long expectedItemVersion,
        UUID operationId) {}
