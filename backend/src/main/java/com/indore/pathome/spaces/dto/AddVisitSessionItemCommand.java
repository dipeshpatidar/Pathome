package com.indore.pathome.spaces.dto;

import com.indore.pathome.spaces.entity.VisitSessionItemOrigin;
public record AddVisitSessionItemCommand(
        Long expectedSessionVersion,
        Long listingId,
        Long originatingRequestId,
        VisitSessionItemOrigin origin) {}
