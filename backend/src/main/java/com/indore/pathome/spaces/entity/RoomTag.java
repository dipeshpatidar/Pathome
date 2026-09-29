package com.indore.pathome.spaces.entity;

public enum RoomTag {
    GENERAL("🌐 General / Untagged"),
    LIVING_ROOM("🛋️ Living Room"),
    MASTER_BEDROOM("🛏️ Master Bedroom"),
    BEDROOM("🛏️ Guest Bedroom"),
    KITCHEN("🍳 Modular Kitchen"),
    BATHROOM("🚿 Bathroom & Restroom"),
    BALCONY("🌅 Balcony & View"),
    EXTERIOR("🏢 Building Exterior"),
    AMENITIES("🏊 Society Amenities"),
    FLOOR_PLAN("📐 Floor Plan Blueprint");

    private final String displayName;

    RoomTag(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static RoomTag fromStored(String value) {
        if (value == null || value.isBlank()) return GENERAL;
        try { return valueOf(value); }
        catch (IllegalArgumentException ignored) { return GENERAL; }
    }
}
