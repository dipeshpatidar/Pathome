package com.indore.pathome.spaces.dto;

import com.indore.pathome.spaces.entity.VisitSessionItemConfirmationStatus;
public record VisitSessionAvailabilityCommand(
        Long expectedSessionVersion,
        VisitSessionItemConfirmationStatus status) {}
