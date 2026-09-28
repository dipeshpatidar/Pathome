package com.indore.pathome.spaces.dto.lessor;

public record LandlordContactDto(
        String fullName,
        String phoneNumber,
        boolean complete
) {
    public LandlordContactDto(String fullName, String phoneNumber) {
        this(fullName, phoneNumber, false);
    }
}
