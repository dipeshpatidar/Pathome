package com.indore.pathome.spaces.service.field;

public record FieldResourceKey(String type, String stableId) {
    public FieldResourceKey {
        if (type == null || type.isBlank() || stableId == null || stableId.isBlank())
            throw new IllegalArgumentException("A field resource type and stable ID are required");
    }
}
