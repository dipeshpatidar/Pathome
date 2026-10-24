package com.indore.pathome.spaces.dto;

public record StaffAccountSearchView(
        Long userId,
        String email,
        String fullName,
        boolean profileExists,
        boolean staffActive) {
}
