package com.indore.pathome.spaces.dto;

import java.util.Set;

/**
 * Optional, additive filters for the scoped Operations work queues.
 *
 * <p>Every value is validated before it reaches SQL. Type-specific status codes are kept separate
 * because Request and Session statuses are different enums with colliding codes; a status without a
 * target type is rejected instead of being interpreted against both populations.</p>
 */
public record OperationsWorkFilter(
        OperationsRootType targetType,
        String status,
        Long cityId,
        Long teamId,
        Long targetId) {

    private static final Set<String> REQUEST_STATUSES =
            Set.of("RECEIVED", "COORDINATING", "SCHEDULED", "UNAVAILABLE", "CANCELLED");
    private static final Set<String> SESSION_STATUSES = Set.of(
            "DRAFT", "SCHEDULED", "STARTED", "COMPLETED", "EXPIRED", "CANCELLED", "NO_SHOW",
            "PROVISIONAL_NO_SHOW", "REPAIR_REQUIRED", "INTERRUPTED");

    public static final OperationsWorkFilter NONE = new OperationsWorkFilter(null, null, null, null, null);

    public static OperationsWorkFilter parse(
            String targetType,
            String status,
            String cityId,
            String teamId,
            String targetId) {
        OperationsRootType type = parseTargetType(targetType);
        String normalizedStatus = trimToNull(status);
        Long parsedCityId = parsePositiveLong(cityId, "cityId");
        Long parsedTeamId = parsePositiveLong(teamId, "teamId");
        Long parsedTargetId = parsePositiveLong(targetId, "targetId");
        if (normalizedStatus != null && type == null) {
            throw new IllegalArgumentException("targetType is required when filtering by status");
        }
        if (parsedTargetId != null && type == null) {
            throw new IllegalArgumentException("targetType is required when filtering by targetId");
        }
        if (normalizedStatus != null && !statusesFor(type).contains(normalizedStatus)) {
            throw new IllegalArgumentException("status is not valid for the selected targetType");
        }
        return new OperationsWorkFilter(type, normalizedStatus, parsedCityId, parsedTeamId, parsedTargetId);
    }

    public boolean isEmpty() {
        return targetType == null && status == null && cityId == null && teamId == null && targetId == null;
    }

    private static OperationsRootType parseTargetType(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) return null;
        try {
            return OperationsRootType.valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("targetType must be REQUEST or SESSION");
        }
    }

    private static Long parsePositiveLong(String value, String field) {
        String normalized = trimToNull(value);
        if (normalized == null) return null;
        try {
            long parsed = Long.parseLong(normalized);
            if (parsed <= 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(field + " must be a positive integer");
        }
    }

    private static Set<String> statusesFor(OperationsRootType type) {
        return type == OperationsRootType.SESSION ? SESSION_STATUSES : REQUEST_STATUSES;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
