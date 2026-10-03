package com.indore.pathome.spaces.dto;

import java.time.Instant;

public record GroundVisitStartCodeStatus(Long sessionId, boolean challengeAvailable, int generation,
                                         Instant expiresAt, Instant lockedUntil) {}
