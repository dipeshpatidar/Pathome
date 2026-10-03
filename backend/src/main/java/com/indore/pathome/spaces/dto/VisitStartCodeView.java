package com.indore.pathome.spaces.dto;

import java.time.Instant;

public record VisitStartCodeView(Long sessionId, int generation, String code,
                                 Instant expiresAt, Instant nextRequestAt, String deliveryChannel) {}
