package com.indore.pathome.spaces.dto;

public record CoordinateVisitRequestCommand(
        Long expectedRequestVersion,
        Long sessionId,
        Long expectedSessionVersion) {}
