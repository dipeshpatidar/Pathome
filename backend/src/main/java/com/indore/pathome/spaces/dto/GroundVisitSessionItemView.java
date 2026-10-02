package com.indore.pathome.spaces.dto;

import java.time.Instant;

public record GroundVisitSessionItemView(
        Long itemId,
        Long listingId,
        String title,
        String address,
        String city,
        String sector,
        Integer position,
        Instant availabilityConfirmedAt) {}
