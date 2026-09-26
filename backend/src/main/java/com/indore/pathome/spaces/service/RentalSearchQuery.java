package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.PropertyType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic, bounded interpretation of public rental text; location resolution is database-backed. */
public record RentalSearchQuery(
        String normalizedQuery, String location, String bhkKey, String bhkLabel,
        PropertyType propertyType, String propertyTypeLabel, String furnishingKey,
        BigDecimal minRent, BigDecimal maxRent) {
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern THOUSANDS_COMMA = Pattern.compile("(?<=\\d),(?=\\d{3}\\b)");
    private static final Pattern PUNCTUATION = Pattern.compile("[,:!?]+");
    private static final Pattern SAFE_QUERY = Pattern.compile("^[\\p{L}\\p{N}][\\p{L}\\p{N}\\s.'₹-]{0,99}$");
    private static final Pattern BHK = Pattern.compile("(?i)(?<![\\p{L}\\p{N}])([1-5])\\s*(BHK|RK|BEDROOMS?|BEDS?)\\b");
    private static final Pattern PROPERTY_TYPE = Pattern.compile(
            "(?i)\\b(serviced\\s+apartments?|independent\\s+houses?|penthouses?|apartments?|apt|flats?|flatt|flaat|flt|houses?|homes?|studios?)\\b");
    private static final Pattern PARTIAL_FLAT = Pattern.compile("(?i)\\b(f|fl|fla)\\b");
    private static final Pattern FURNISHING = Pattern.compile(
            "(?i)\\b(semi[\\s-]*(?:furnished|furn|fur)?|fully[\\s-]*furnished|full[\\s-]*furnished|unfurnished|furnished)\\b");
    private static final Pattern BETWEEN_PRICE = Pattern.compile(
            "(?i)\\bbetween\\s+(?:rs\\.?\\s*|inr\\s*)?(\\d+(?:\\.\\d+)?)\\s*(k|lakh|lac)?\\s+and\\s+(?:rs\\.?\\s*|inr\\s*)?(\\d+(?:\\.\\d+)?)\\s*(k|lakh|lac)?\\b");
    private static final Pattern UNIT_RANGE = Pattern.compile(
            "(?i)\\b(\\d+(?:\\.\\d+)?)\\s*(k|lakh|lac)\\s+to\\s+(\\d+(?:\\.\\d+)?)\\s*(k|lakh|lac)?\\b");
    private static final Pattern PLAIN_RANGE = Pattern.compile("\\b(\\d{4,7})\\s+to\\s+(\\d{4,7})\\b");
    private static final Pattern MAX_PRICE = Pattern.compile(
            "(?i)\\b(?:under|below|max|up\\s*to)\\s*(?:rs\\.?\\s*|inr\\s*)?(\\d+(?:\\.\\d+)?)\\s*(k|lakh|lac)?\\b");
    private static final Pattern LEADING_LOCATION_WORD = Pattern.compile("(?i)^(?:in|near|at)\\s+");
    private static final BigDecimal MAX_SUPPORTED_RENT = new BigDecimal("10000000");

    public static RentalSearchQuery parse(String raw) {
        if (raw == null || raw.isBlank()) return new RentalSearchQuery("", "", null, null, null, null, null, null, null);
        String text = Normalizer.normalize(raw.trim(), Normalizer.Form.NFKC);
        text = THOUSANDS_COMMA.matcher(text).replaceAll("");
        text = PUNCTUATION.matcher(text).replaceAll(" ");
        text = WHITESPACE.matcher(text).replaceAll(" ").trim();
        if (!SAFE_QUERY.matcher(text).matches()) throw new IllegalArgumentException("The search query is invalid");
        String normalized = text.toLowerCase(Locale.ROOT);
        String remainder = text.replace('₹', ' ').trim();

        BigDecimal minRent = null;
        BigDecimal maxRent = null;
        Matcher price = BETWEEN_PRICE.matcher(remainder);
        if (price.find()) {
            minRent = amount(price.group(1), price.group(2));
            maxRent = amount(price.group(3), price.group(4));
            remainder = remove(remainder, price);
        } else {
            price = UNIT_RANGE.matcher(remainder);
            if (price.find()) {
                minRent = amount(price.group(1), price.group(2));
                maxRent = amount(price.group(3), price.group(4) == null ? price.group(2) : price.group(4));
                remainder = remove(remainder, price);
            } else {
                price = PLAIN_RANGE.matcher(remainder);
                if (price.find()) {
                    minRent = amount(price.group(1), null);
                    maxRent = amount(price.group(2), null);
                    remainder = remove(remainder, price);
                } else {
                    price = MAX_PRICE.matcher(remainder);
                    if (price.find()) {
                        maxRent = amount(price.group(1), price.group(2));
                        remainder = remove(remainder, price);
                    }
                }
            }
        }
        if (minRent != null && maxRent != null && minRent.compareTo(maxRent) > 0) {
            throw new IllegalArgumentException("The rent range is invalid");
        }

        String bhkKey = null;
        String bhkLabel = null;
        Matcher bhk = BHK.matcher(remainder);
        if (bhk.find()) {
            String kind = bhk.group(2).equalsIgnoreCase("RK") ? "RK" : "BHK";
            bhkKey = bhk.group(1) + kind;
            bhkLabel = bhk.group(1) + " " + kind;
            remainder = remove(remainder, bhk);
        }

        PropertyType propertyType = null;
        String propertyTypeLabel = null;
        Matcher property = PROPERTY_TYPE.matcher(remainder);
        boolean hasStructuredContext = bhkKey != null || minRent != null || maxRent != null;
        if (property.find() && (hasStructuredContext || property.start() == 0 && property.group(1).toLowerCase(Locale.ROOT).startsWith("house"))) {
            String alias = normalizeLocation(property.group(1));
            if (alias.startsWith("serviced")) {
                propertyType = PropertyType.SERVICED_APARTMENT;
                propertyTypeLabel = "Serviced Apartment";
            } else if (alias.startsWith("penthouse")) {
                propertyType = PropertyType.PENTHOUSE;
                propertyTypeLabel = "Penthouse";
            } else if (alias.startsWith("house") || alias.startsWith("home") || alias.startsWith("independent")) {
                propertyType = PropertyType.HOUSE;
                propertyTypeLabel = "House";
            } else if (alias.startsWith("studio")) {
                propertyType = PropertyType.STUDIO;
                propertyTypeLabel = "Studio";
            } else {
                propertyType = PropertyType.FLAT;
                propertyTypeLabel = "Flat";
            }
            remainder = remove(remainder, property);
        } else if (bhkKey != null) {
            property = PARTIAL_FLAT.matcher(remainder);
            if (property.find()) {
                propertyType = PropertyType.FLAT;
                propertyTypeLabel = "Flat";
                remainder = remove(remainder, property);
            }
        }

        String furnishingKey = null;
        Matcher furnishing = FURNISHING.matcher(remainder);
        if (furnishing.find()) {
            String alias = normalizeLocation(furnishing.group(1));
            furnishingKey = alias.startsWith("semi") ? "SEMI_FURNISHED"
                    : alias.startsWith("un") ? "UNFURNISHED"
                    : alias.startsWith("full") ? "FULLY_FURNISHED" : "FURNISHED";
            remainder = remove(remainder, furnishing);
        }
        remainder = LEADING_LOCATION_WORD.matcher(remainder).replaceFirst("").trim();
        return new RentalSearchQuery(normalized, remainder, bhkKey, bhkLabel,
                propertyType, propertyTypeLabel, furnishingKey, minRent, maxRent);
    }

    private static String remove(String source, Matcher match) {
        return WHITESPACE.matcher((source.substring(0, match.start()) + " " + source.substring(match.end())).trim())
                .replaceAll(" ");
    }

    private static BigDecimal amount(String number, String unit) {
        BigDecimal multiplier = unit == null ? BigDecimal.ONE
                : unit.equalsIgnoreCase("k") ? new BigDecimal("1000") : new BigDecimal("100000");
        BigDecimal result = new BigDecimal(number).multiply(multiplier).setScale(0, RoundingMode.UNNECESSARY);
        if (result.signum() <= 0 || result.compareTo(MAX_SUPPORTED_RENT) > 0) {
            throw new IllegalArgumentException("The rent amount is invalid");
        }
        return result;
    }

    public static String normalizeLocation(String raw) {
        return raw == null ? "" : WHITESPACE.matcher(Normalizer.normalize(raw.trim(), Normalizer.Form.NFKC))
                .replaceAll(" ").toLowerCase(Locale.ROOT);
    }

    public static String normalizeBhk(String raw) {
        if (raw == null || raw.isBlank()) return "";
        RentalSearchQuery parsed = parse(raw);
        if (parsed.bhkKey() == null || parsed.propertyType() != null || parsed.furnishingKey() != null
                || parsed.minRent() != null || parsed.maxRent() != null || !parsed.location().isBlank()) {
            throw new IllegalArgumentException("The BHK filter is invalid");
        }
        return parsed.bhkKey();
    }

    public static String normalizeFurnishing(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String key = raw.trim().toUpperCase(Locale.ROOT);
        if (!key.equals("FURNISHED") && !key.equals("FULLY_FURNISHED")
                && !key.equals("SEMI_FURNISHED") && !key.equals("UNFURNISHED")) {
            throw new IllegalArgumentException("The furnishing filter is invalid");
        }
        return key;
    }

    public static void validateRentRange(BigDecimal min, BigDecimal max) {
        if (min != null && (min.signum() <= 0 || min.compareTo(MAX_SUPPORTED_RENT) > 0)
                || max != null && (max.signum() <= 0 || max.compareTo(MAX_SUPPORTED_RENT) > 0)
                || min != null && max != null && min.compareTo(max) > 0) {
            throw new IllegalArgumentException("The rent range is invalid");
        }
    }
}
