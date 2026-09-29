package com.indore.pathome.spaces.dto.lessor;

import com.indore.pathome.spaces.entity.RoomTag;

public record LandlordMediaItem(String mediaId, String filename, String contentType,
                                String url, String status, boolean cover, int sortOrder, RoomTag roomTag) {
    public LandlordMediaItem(String mediaId, String filename, String contentType,
                             String url, String status, boolean cover, int sortOrder) {
        this(mediaId, filename, contentType, url, status, cover, sortOrder, RoomTag.GENERAL);
    }
}
