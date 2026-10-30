package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.Listing;
import com.indore.pathome.spaces.entity.ListingType;
import com.indore.pathome.spaces.entity.Locality;
import com.indore.pathome.spaces.entity.PropertyType;
import com.indore.pathome.spaces.entity.RentalDetails;
import com.indore.pathome.spaces.entity.SupportedCity;

import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Conservative server-side projection for public property content. Private source text is never
 * treated as safe merely because a DTO omits dedicated contact fields.
 */
public final class PublicPropertyDisclosurePolicy {
    private static final Pattern SAFE_TOKEN = Pattern.compile("[\\p{L}\\p{N} /&().+-]{1,80}");
    private static final Pattern SAFE_BHK = Pattern.compile(
            "(?i)^(?:[1-9]|[1-2][0-9])\\s*(?:BHK|RK)(?:\\s*\\+\\s*(?:STUDY|SERVANT))?$");
    private static final Pattern TRUSTED_PUBLIC_ID =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_./-]{0,199}$");
    private static final Pattern UPLOAD_REQUEST_ID = Pattern.compile("^[A-Za-z0-9_-]{8,80}$");
    private static final Pattern PRIVATE_IP = Pattern.compile(
            "^(?:localhost|127\\.|10\\.|192\\.168\\.|169\\.254\\.|172\\.(?:1[6-9]|2\\d|3[01])\\.).*");
    private static final Pattern SAFE_MEDIA_EXTENSION =
            Pattern.compile("(?i).+\\.(?:jpg|jpeg|png|webp|avif|gif|mp4|webm|mov)$");
    private static final Pattern SAFE_MEDIA_EXTENSION_SUFFIX =
            Pattern.compile("(?i)\\.(?:jpg|jpeg|png|webp|avif|gif|mp4|webm|mov)$");
    private static final Set<String> AMENITIES = Set.of(
            "lift", "elevator", "parking", "car parking", "two wheeler parking",
            "security", "cctv", "power backup", "generator", "gym", "swimming pool",
            "park", "garden", "play area", "clubhouse", "balcony", "terrace", "modular kitchen",
            "air conditioning", "air conditioner", "water supply", "gas pipeline", "pet friendly",
            "bachelor friendly", "family friendly");
    private static final Set<String> APPROVED_PUBLIC_MEDIA_HOSTS =
            Set.of("res.cloudinary.com");

    private PublicPropertyDisclosurePolicy() {}

    public static String safeTitle(Listing listing) {
        if (listing == null) return "Property in a Pathome city";
        String bhk = safeBhk(listing.getBhkCount());
        String type = propertyTypeLabel(listing.getPropertyType());
        StringBuilder title = new StringBuilder();
        if (bhk != null) title.append(bhk);
        if (type != null) {
            if (!title.isEmpty()) title.append(' ');
            title.append(type);
        }
        if (title.isEmpty()) title.append(listing.getListingType() == ListingType.RENT
                ? "Rental home" : "Property");
        return title.toString();
    }

    public static String safeCanonicalTitle(Listing listing, Locality locality,
                                            SupportedCity city) {
        String base = safeTitle(listing);
        String approvedLocality = canonicalLocalityLabel(locality);
        String approvedCity = canonicalCityLabel(city, locality);
        if (approvedLocality == null && approvedCity == null) return base;
        StringBuilder title = new StringBuilder(base);
        if (approvedLocality != null) title.append(" in ").append(approvedLocality);
        if (approvedCity != null && (approvedLocality == null
                || !approvedCity.equalsIgnoreCase(approvedLocality))) {
            title.append(approvedLocality == null ? " in " : ", ").append(approvedCity);
        }
        return title.toString();
    }

    public static String canonicalLocalityLabel(Locality locality) {
        return locality == null ? null : cleanCanonicalLabel(locality.getSectorName(), 120);
    }

    public static String canonicalCityLabel(SupportedCity city, Locality locality) {
        SupportedCity canonical = city != null ? city
                : locality == null ? null : locality.getSupportedCity();
        return canonical == null ? null : cleanCanonicalLabel(canonical.getDisplayName(), 160);
    }

    public static String safeBhk(String raw) {
        if (raw == null) return null;
        String normalized = raw.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        return SAFE_BHK.matcher(normalized).matches() ? normalized : null;
    }

    /** Free-form descriptions are omitted until a separately approved public-content review exists. */
    public static String safeDescription(Listing listing) {
        return null;
    }

    public static String safeAmenities(String raw) {
        if (raw == null || raw.isBlank()) return null;
        LinkedHashSet<String> safe = new LinkedHashSet<>();
        Arrays.stream(raw.split("[,;|]"))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(value -> value.replaceAll("\\s+", " "))
                .filter(value -> value.length() <= 80)
                .filter(value -> SAFE_TOKEN.matcher(value).matches())
                .filter(value -> AMENITIES.contains(value.toLowerCase(Locale.ROOT)))
                .forEach(safe::add);
        return safe.isEmpty() ? null : String.join(", ", safe);
    }

    public static String safeMediaUrl(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 1000) return null;
        try {
            URI uri = URI.create(raw.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            String path = uri.getPath();
            if (!"https".equalsIgnoreCase(scheme) || host == null || path == null
                    || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || (uri.getPort() != -1 && uri.getPort() != 443)) {
                return null;
            }
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            String normalizedPath = path.toLowerCase(Locale.ROOT);
            if (PRIVATE_IP.matcher(normalizedHost).matches()
                    || !APPROVED_PUBLIC_MEDIA_HOSTS.contains(normalizedHost)
                    || normalizedHost.endsWith(".local")
                    || normalizedHost.contains("staging")
                    || normalizedHost.contains("internal")
                    || normalizedHost.contains("admin")
                    || normalizedHost.contains("metadata")
                    || normalizedPath.contains("/private/")
                    || normalizedPath.contains("/authenticated/")
                    || normalizedPath.contains("/raw/upload/")
                    || normalizedPath.contains("/staging/")
                    || normalizedPath.contains("/draft/")
                    || normalizedPath.contains("..")) {
                return null;
            }
            if (normalizedPath.contains("/image/upload/") || normalizedPath.contains("/video/upload/")) {
                return uri.toASCIIString();
            }
            return SAFE_MEDIA_EXTENSION.matcher(path).matches() ? uri.toASCIIString() : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    public static String safeOwnedMediaUrl(String raw, String publicId, String uploadRequestId,
                                           String trustedCloudName) {
        String safe = safeMediaUrl(raw);
        if (safe == null || trustedCloudName == null || trustedCloudName.isBlank()) return null;
        try {
            URI uri = URI.create(safe);
            String path = uri.getPath();
            if (path == null || !path.startsWith("/" + trustedCloudName + "/")) return null;
            String normalizedPublicId = publicId == null ? null : publicId.trim();
            if (normalizedPublicId != null && !normalizedPublicId.isBlank()) {
                if (normalizedPublicId.length() > 200
                        || !TRUSTED_PUBLIC_ID.matcher(normalizedPublicId).matches()
                        || normalizedPublicId.contains("..")) {
                    return null;
                }
                return pathMatchesPublicId(path, normalizedPublicId) ? safe : null;
            }
            String requestId = uploadRequestId == null ? null : uploadRequestId.trim();
            if (requestId == null || !UPLOAD_REQUEST_ID.matcher(requestId).matches()) return null;
            String generatedImage = "/pathome/properties/images/" + requestId;
            String generatedVideo = "/pathome/properties/videos/" + requestId;
            return pathMatchesGeneratedId(path, generatedImage)
                    || pathMatchesGeneratedId(path, generatedVideo) ? safe : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    public static String safeRentalFact(RentalDetails rental) {
        if (rental == null || rental.getMonthlyRent() == null) return null;
        return rental.getMonthlyRent().toPlainString();
    }

    private static String cleanStructured(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().replaceAll("\\s+", " ");
        if (normalized.length() > 60 || !SAFE_TOKEN.matcher(normalized).matches()) return null;
        return normalized;
    }

    private static String cleanCanonicalLabel(String value, int maximumLength) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().replaceAll("\\s+", " ");
        return normalized.length() <= maximumLength ? normalized : null;
    }

    private static boolean pathMatchesPublicId(String path, String publicId) {
        String suffix = "/" + publicId;
        int index = path.lastIndexOf(suffix);
        if (index < 0) return false;
        String trailing = path.substring(index + suffix.length());
        return SAFE_MEDIA_EXTENSION_SUFFIX.matcher(trailing).matches();
    }

    private static boolean pathMatchesGeneratedId(String path, String generatedPath) {
        int index = path.lastIndexOf(generatedPath);
        if (index < 0) return false;
        String trailing = path.substring(index + generatedPath.length());
        return SAFE_MEDIA_EXTENSION_SUFFIX.matcher(trailing).matches();
    }

    private static String propertyTypeLabel(PropertyType type) {
        if (type == null) return null;
        return switch (type) {
            case FLAT -> "Flat";
            case HOUSE -> "House";
            case PLOT -> "Plot";
            case LAND -> "Land";
            case PENTHOUSE -> "Penthouse";
            case STUDIO -> "Studio";
            case SERVICED_APARTMENT -> "Serviced apartment";
        };
    }
}
