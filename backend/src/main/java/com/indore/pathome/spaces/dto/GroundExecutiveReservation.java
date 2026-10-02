package com.indore.pathome.spaces.dto;

import com.indore.pathome.spaces.entity.VisitSessionStatus;
import java.time.Instant;

/** Read-only projection of Package 2B reservations for future scheduling calculations. */
public record GroundExecutiveReservation(Long sessionId, Long groundExecutiveUserId,
                                         Instant reservedStartAt, Instant reservedEndAt,
                                         VisitSessionStatus status) {}
