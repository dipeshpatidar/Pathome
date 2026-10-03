package com.indore.pathome.spaces.dto;

import java.util.UUID;

public record CompleteVisitSessionWithOutcomesCommand(
        Long expectedSessionVersion,
        Long expectedReportVersion,
        UUID operationId) {}
