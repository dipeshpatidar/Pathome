package com.indore.pathome.spaces.dto.lessor;

public record LandlordMediaItem(String mediaId, String filename, String contentType,
                                String url, String status, boolean cover, int sortOrder) {}
