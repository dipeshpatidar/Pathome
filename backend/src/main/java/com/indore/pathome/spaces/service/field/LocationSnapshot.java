package com.indore.pathome.spaces.service.field;

import java.time.Instant;

public record LocationSnapshot(
        Double latitude,
        Double longitude,
        Instant capturedAt,
        Instant receivedAt,
        Double accuracyMeters,
        LocationAssessment assessment,
        String reason) {}
