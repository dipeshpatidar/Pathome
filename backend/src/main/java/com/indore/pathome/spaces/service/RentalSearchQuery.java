package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.PropertyType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic, bounded interpretation of public rental text; location resolution is database-backed. */
public record RentalSearchQuery(
        String normalizedQuery, String location, String explicitCity, String bhkKey, String bhkLabel,
        PropertyType propertyType, String propertyTypeLabel, String furnishingKey,
        BigDecimal minRent, BigDecimal maxRent,
        PriceState priceState, LocationState locationState) {
    public enum PriceState {
        NONE,
        EXPECTING_PRICE,
        EXPECTING_MAX_RENT,
        EXPECTING_MIN_RENT,
        EXPECTING_RANGE_END,
        COMPLETE
    }

    public enum LocationState {
        NONE,
        EXPECTING_LOCATION,
        PROVIDED
    }

    public RentalSearchQuery(
            String normalizedQuery, String location, String bhkKey, String bhkLabel,
            PropertyType propertyType, String propertyTypeLabel, String furnishingKey,
            BigDecimal minRent, BigDecimal maxRent,
            PriceState priceState, LocationState locationState) {
        this(normalizedQuery, location, null, bhkKey, bhkLabel, propertyType, propertyTypeLabel,
                furnishingKey, minRent, maxRent, priceState, locationState);
    }

    public RentalSearchQuery(
            String normalizedQuery, String location, String bhkKey, String bhkLabel,
            PropertyType propertyType, String propertyTypeLabel, String furnishingKey,
            BigDecimal minRent, BigDecimal maxRent) {
        this(normalizedQuery, location, null, bhkKey, bhkLabel, propertyType, propertyTypeLabel,
                furnishingKey, minRent, maxRent, PriceState.NONE, LocationState.NONE);
    }

    public static final Set<String> SUPPORTED_CITIES = CityRegistry.getSupportedCities();
    public static final Set<String> KNOWN_METRO_CITIES = CityRegistry.getKnownCities();

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern THOUSANDS_COMMA = Pattern.compile("(?<=\\d),(?=\\d{3}\\b)");
    private static final Pattern PUNCTUATION = Pattern.compile("[,:!?]+");
    private static final Pattern SAFE_QUERY = Pattern.compile("^[\\p{L}\\p{N}][\\p{L}\\p{N}\\s.'₹-]{0,99}$");
    private static final Pattern BHK = Pattern.compile("(?i)(?<![\\p{L}\\p{N}])(?:([1-5])\\s*(BHK|RK|BEDROOMS?|BEDS?)|\\b(RK)\\b)");
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
    private static final Pattern MIN_PRICE = Pattern.compile(
            "(?i)\\b(?:above|min|over|from|starting\\s+at)\\s*(?:rs\\.?\\s*|inr\\s*)?(\\d+(?:\\.\\d+)?)\\s*(k|lakh|lac)?\\b");

    private static final Pattern PARTIAL_BETWEEN_WITH_FIRST = Pattern.compile(
            "(?i)\\bbetween\\s+(?:rs\\.?\\s*|inr\\s*)?(\\d+(?:\\.\\d+)?)\\s*(k|lakh|lac)?(?:\\s+and)?\\s*$");
    private static final Pattern PARTIAL_BETWEEN_EMPTY = Pattern.compile("(?i)\\bbetween\\s*$");
    private static final Pattern PARTIAL_MAX_PRICE = Pattern.compile(
            "(?i)\\b(?:under|below|max|up\\s*to)\\s*(?:rs\\.?\\s*|inr\\s*)?(\\d{1,3})?\\s*$");
    private static final Pattern PARTIAL_MIN_PRICE = Pattern.compile(
            "(?i)\\b(?:above|min|over|from|starting\\s+at)\\s*(?:rs\\.?\\s*|inr\\s*)?(\\d{1,3})?\\s*$");

    private static final Pattern GENERIC_CITY_CONNECTOR = Pattern.compile(
            "(?i)\\b(?:in|at|of)\\s+([\\p{L}]+(?:\\s+[\\p{L}]+)?)(?!\\s+(?:road|naka|gate|bypass|highway|circle|square))\\b");

    private static final Pattern TRAILING_LOCATION_CONNECTOR = Pattern.compile("(?i)(?:^|\\s+)(?:in|at|near)\\s*$");
    private static final Pattern TRAILING_FURNISHING_CONNECTOR = Pattern.compile("(?i)(?:^|\\s+)(?:with|without)\\s*$");
    private static final Pattern LEADING_LOCATION_WORD = Pattern.compile("(?i)^(?:in|near|at)\\s+");
    private static final BigDecimal MAX_SUPPORTED_RENT = new BigDecimal("10000000");

    public static RentalSearchQuery parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new RentalSearchQuery("", "", null, null, null, null, null, null, null, null, PriceState.NONE, LocationState.NONE);
        }
        String text = Normalizer.normalize(raw.trim(), Normalizer.Form.NFKC);
        text = THOUSANDS_COMMA.matcher(text).replaceAll("");
        text = PUNCTUATION.matcher(text).replaceAll(" ");
        text = WHITESPACE.matcher(text).replaceAll(" ").trim();
        if (!SAFE_QUERY.matcher(text).matches()) throw new IllegalArgumentException("The search query is invalid");
        String normalized = text.toLowerCase(Locale.ROOT);
        String remainder = text.replace('₹', ' ').trim();

        PriceState priceState = PriceState.NONE;
        BigDecimal minRent = null;
        BigDecimal maxRent = null;
        Matcher price = BETWEEN_PRICE.matcher(remainder);
        if (price.find()) {
            minRent = amount(price.group(1), price.group(2));
            maxRent = amount(price.group(3), price.group(4));
            priceState = PriceState.COMPLETE;
            remainder = remove(remainder, price);
        } else {
            price = UNIT_RANGE.matcher(remainder);
            if (price.find()) {
                minRent = amount(price.group(1), price.group(2));
                maxRent = amount(price.group(3), price.group(4) == null ? price.group(2) : price.group(4));
                priceState = PriceState.COMPLETE;
                remainder = remove(remainder, price);
            } else {
                price = PLAIN_RANGE.matcher(remainder);
                if (price.find()) {
                    minRent = amount(price.group(1), null);
                    maxRent = amount(price.group(2), null);
                    priceState = PriceState.COMPLETE;
                    remainder = remove(remainder, price);
                } else {
                    price = MAX_PRICE.matcher(remainder);
                    if (price.find() && isConfidentlyCompletePrice(price.group(1), price.group(2))) {
                        maxRent = amount(price.group(1), price.group(2));
                        priceState = PriceState.COMPLETE;
                        remainder = remove(remainder, price);
                    } else {
                        price = MIN_PRICE.matcher(remainder);
                        if (price.find() && isConfidentlyCompletePrice(price.group(1), price.group(2))) {
                            minRent = amount(price.group(1), price.group(2));
                            priceState = PriceState.COMPLETE;
                            remainder = remove(remainder, price);
                        }
                    }
                }
            }
        }
        if (priceState == PriceState.NONE) {
            Matcher partialBetween = PARTIAL_BETWEEN_WITH_FIRST.matcher(remainder);
            if (partialBetween.find()) {
                minRent = amount(partialBetween.group(1), partialBetween.group(2));
                priceState = PriceState.EXPECTING_RANGE_END;
                remainder = remove(remainder, partialBetween);
            } else {
                Matcher emptyBetween = PARTIAL_BETWEEN_EMPTY.matcher(remainder);
                if (emptyBetween.find()) {
                    priceState = PriceState.EXPECTING_PRICE;
                    remainder = remove(remainder, emptyBetween);
                } else {
                    Matcher partialMax = PARTIAL_MAX_PRICE.matcher(remainder);
                    if (partialMax.find()) {
                        priceState = PriceState.EXPECTING_MAX_RENT;
                        remainder = remove(remainder, partialMax);
                    } else {
                        Matcher partialMin = PARTIAL_MIN_PRICE.matcher(remainder);
                        if (partialMin.find()) {
                            priceState = PriceState.EXPECTING_MIN_RENT;
                            remainder = remove(remainder, partialMin);
                        }
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
            if (bhk.group(3) != null) {
                bhkKey = "1RK";
                bhkLabel = "1 RK";
            } else {
                String kind = bhk.group(2).equalsIgnoreCase("RK") ? "RK" : "BHK";
                bhkKey = bhk.group(1) + kind;
                bhkLabel = bhk.group(1) + " " + kind;
            }
            remainder = remove(remainder, bhk);
        }

        PropertyType propertyType = null;
        String propertyTypeLabel = null;
        Matcher property = PROPERTY_TYPE.matcher(remainder);
        boolean hasStructuredContext = bhkKey != null || minRent != null || maxRent != null || priceState != PriceState.NONE;
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

        String explicitCity = null;
        Matcher cityConn = GENERIC_CITY_CONNECTOR.matcher(remainder);
        while (cityConn.find()) {
            String candidate = cityConn.group(1).trim();
            String matched = CityRegistry.matchCityName(candidate);
            if (matched != null) {
                explicitCity = matched;
                remainder = remove(remainder, cityConn);
                break;
            }
        }
        if (explicitCity == null) {
            String[] tokens = remainder.split("\\s+");
            for (String token : tokens) {
                String clean = token.replaceAll("[^\\p{L}]", "");
                if (clean.length() >= 3) {
                    String matched = CityRegistry.matchCityName(clean);
                    if (matched != null) {
                        String remainderWithoutCity = removeWord(remainder, token);
                        if (hasStructuredContext || !remainderWithoutCity.isBlank()) {
                            explicitCity = matched;
                            remainder = remainderWithoutCity;
                            break;
                        }
                    }
                }
            }
        }

        LocationState locationState = LocationState.NONE;
        Matcher trailingLoc = TRAILING_LOCATION_CONNECTOR.matcher(remainder);
        if (trailingLoc.find()) {
            locationState = LocationState.EXPECTING_LOCATION;
            remainder = remove(remainder, trailingLoc);
        }
        Matcher trailingFurn = TRAILING_FURNISHING_CONNECTOR.matcher(remainder);
        if (trailingFurn.find()) {
            remainder = remove(remainder, trailingFurn);
        }
        remainder = LEADING_LOCATION_WORD.matcher(remainder).replaceFirst("").trim();
        if (!remainder.isBlank()) {
            locationState = LocationState.PROVIDED;
        }

        return new RentalSearchQuery(normalized, remainder, explicitCity, bhkKey, bhkLabel,
                propertyType, propertyTypeLabel, furnishingKey, minRent, maxRent,
                priceState, locationState);
    }

    public static String canonicalCityName(String raw) {
        return CityRegistry.canonicalCityName(raw);
    }

    private static String removeWord(String source, String word) {
        Pattern p = Pattern.compile("(?i)(?<!\\p{L})" + Pattern.quote(word) + "(?!\\p{L})");
        Matcher m = p.matcher(source);
        if (m.find()) {
            return remove(source, m);
        }
        return source;
    }

    private static boolean isConfidentlyCompletePrice(String number, String unit) {
        if (unit != null && !unit.isBlank()) return true;
        try {
            BigDecimal val = new BigDecimal(number);
            return val.compareTo(new BigDecimal("1000")) >= 0;
        } catch (Exception e) {
            return false;
        }
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
