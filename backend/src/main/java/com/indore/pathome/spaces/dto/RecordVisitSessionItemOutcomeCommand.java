package com.indore.pathome.spaces.dto;

import com.indore.pathome.spaces.entity.VisitSessionItemOutcomeState;
import com.indore.pathome.spaces.entity.VisitSessionItemSkipReason;

import java.util.UUID;

public record RecordVisitSessionItemOutcomeCommand(
        VisitSessionItemOutcomeState outcome,
        VisitSessionItemSkipReason skipReason,
        String privateNote,
        Long expectedSessionVersion,
        Long expectedReportVersion,
        UUID operationId) {}
