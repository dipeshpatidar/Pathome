package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.LeadStatus;
import com.indore.pathome.spaces.exception.VisitOperationsConflictException;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.ZoneId;
import java.time.zone.ZoneRulesException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

final class LeadContract {
    private static final Pattern EXTENSION = Pattern.compile("(?i)\\b(?:ext\\.?|extension|x)\\s*\\d+\\b");
    private static final Pattern ALLOWED_PHONE = Pattern.compile("\\+[1-9]\\d{7,14}");
    private static final Pattern INDIAN_LOCAL = Pattern.compile("[6-9]\\d{9}");
    private static final Pattern PHONE_SEPARATORS = Pattern.compile("[\\s().-]");
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
    private static final Pattern REASON = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");
    private static final Pattern POSITIVE_INTEGER = Pattern.compile("[0-9]+");
    private static final BigDecimal MAX_BUDGET = new BigDecimal("999999999999.99");
    private static final int MAX_INTEGER_IDS = 20;
    private static final Set<LeadStatus> HISTORICAL =
            EnumSet.of(LeadStatus.ASSIGNED, LeadStatus.UNMAPPED, LeadStatus.CONTACTED, LeadStatus.STALE);

    private LeadContract() {}

    static String normalizePhone(String raw) {
        if (raw == null || raw.isBlank()) return null;
        if (EXTENSION.matcher(raw).find()) {
            throw new IllegalArgumentException("Contact number must not contain an extension");
        }
        String value = PHONE_SEPARATORS.matcher(raw.trim()).replaceAll("");
        if (value.startsWith("00")) value = "+" + value.substring(2);
        if (!value.startsWith("+") && INDIAN_LOCAL.matcher(value).matches()) {
            value = "+91" + value;
        }
        if (!ALLOWED_PHONE.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Contact number must be a valid E.164 number; local numbers require an explicit country code");
        }
        return value;
    }

    static void validateBudget(BigDecimal minimum, BigDecimal maximum) {
        if (minimum == null && maximum == null) return;
        validateStorableBudget(minimum, "Minimum budget");
        validateStorableBudget(maximum, "Maximum budget");
        if (minimum != null && minimum.signum() < 0) {
            throw new IllegalArgumentException("Minimum budget cannot be negative");
        }
        if (maximum != null && maximum.signum() < 0) {
            throw new IllegalArgumentException("Maximum budget cannot be negative");
        }
        if (minimum != null && maximum != null && minimum.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("Minimum budget cannot exceed maximum budget");
        }
    }

    private static void validateStorableBudget(BigDecimal value, String field) {
        if (value == null) return;
        if (value.compareTo(MAX_BUDGET) > 0) {
            throw new IllegalArgumentException(field + " exceeds the supported NUMERIC(14,2) range");
        }
        if (value.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException(field + " supports at most two decimal places");
        }
    }

    static List<Long> exactPositiveIds(List<?> raw, String field) {
        if (raw == null || raw.isEmpty()) return List.of();
        if (raw.size() > MAX_INTEGER_IDS) {
            throw new IllegalArgumentException("At most 20 preferred localities are allowed");
        }
        LinkedHashSet<Long> result = new LinkedHashSet<>();
        for (Object item : raw) {
            result.add(exactPositiveLong(item, field));
        }
        return new ArrayList<>(result);
    }

    private static Long exactPositiveLong(Object value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must contain only positive integer IDs");
        }
        BigInteger integer;
        if (value instanceof BigInteger bigInteger) {
            integer = bigInteger;
        } else if (value instanceof BigDecimal decimal) {
            try {
                integer = decimal.toBigIntegerExact();
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException(field + " must contain only exact positive integers");
            }
        } else if (value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long) {
            integer = BigInteger.valueOf(((Number) value).longValue());
        } else if (value instanceof String text && POSITIVE_INTEGER.matcher(text).matches()) {
            try {
                integer = new BigInteger(text);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(field + " must contain valid positive integer IDs");
            }
        } else {
            // Floating-point values are rejected even when they happen to be integral because JSON
            // exponent/decimal forms are not evidence of an exact identifier.
            throw new IllegalArgumentException(field + " must contain only exact positive integers");
        }
        if (integer.signum() <= 0 || integer.bitLength() > 63) {
            throw new IllegalArgumentException(field + " must contain only supported positive integer IDs");
        }
        return integer.longValue();
    }

    static ZoneId validateFollowUp(Instant at, String zoneId) {
        if ((at == null) != (zoneId == null || zoneId.isBlank())) {
            throw new IllegalArgumentException("Follow-up instant and IANA zone must be provided together");
        }
        if (at == null) return null;
        try {
            return ZoneId.of(zoneId);
        } catch (ZoneRulesException exception) {
            throw new IllegalArgumentException("Follow-up zone is not a valid IANA zone");
        }
    }

    static void validateCurrency(String currency) {
        if (currency == null || !CURRENCY.matcher(currency).matches()) {
            throw new IllegalArgumentException("Currency must be a three-letter ISO code");
        }
    }

    static void requireReasonCode(String reasonCode) {
        if (reasonCode == null || !REASON.matcher(reasonCode).matches()) {
            throw new IllegalArgumentException("Reason must use a bounded machine code");
        }
    }

    static String text(String value, int maximum, String field, boolean required) {
        if (value == null || value.isBlank()) {
            if (required) throw new IllegalArgumentException(field + " is required");
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > maximum) throw new IllegalArgumentException(field + " is too long");
        return trimmed;
    }

    static void requireTransition(LeadStatus current, LeadStatus next) {
        if (next == null || HISTORICAL.contains(current)) {
            throw new VisitOperationsConflictException("This Lead cannot make the requested lifecycle transition");
        }
        boolean allowed = switch (current) {
            case NEW -> next == LeadStatus.QUALIFYING;
            case QUALIFYING -> next == LeadStatus.QUALIFIED || next == LeadStatus.CLOSED;
            case CLOSED -> next == LeadStatus.QUALIFYING;
            case QUALIFIED -> false;
            case ASSIGNED, UNMAPPED, CONTACTED, STALE -> false;
        };
        if (!allowed) {
            throw new VisitOperationsConflictException("This Lead cannot make the requested lifecycle transition");
        }
    }
}
