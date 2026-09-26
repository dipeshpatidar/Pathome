package com.indore.pathome.spaces.dto;

import com.indore.pathome.spaces.entity.MediaType;
import com.indore.pathome.spaces.entity.RoomTag;

/**
 * Media metadata that is safe to expose with a public property listing.
 * Internal storage, idempotency, and location metadata intentionally stay server-side.
 */
public record PublicPropertyMediaResponse(
        String mediaUrl,
        MediaType mediaType,
        RoomTag roomTag,
        boolean primaryCover
) {}
