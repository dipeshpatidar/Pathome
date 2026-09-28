package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.Locality;
import com.indore.pathome.spaces.repository.LocalityRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@Service
public class LandlordLocationService {
    private final LocalityRepository localities;
    private final SearchLearningService aliases;
    private final ExternalLocalityProvider external;
    private final byte[] signingKey;

    @Autowired
    public LandlordLocationService(LocalityRepository localities, SearchLearningService aliases,
            ExternalLocalityProvider external, @Value("${app.jwt.secret}") String signingSecret) {
        this.localities = localities;
        this.aliases = aliases;
        this.external = external;
        this.signingKey = ("pathome-locality-selection:" + signingSecret).getBytes(StandardCharsets.UTF_8);
    }

    /** Test compatibility for the existing internal resolver checks. */
    public LandlordLocationService(LocalityRepository localities, SearchLearningService aliases) {
        this(localities, aliases, (city, query) -> List.of(), "test-locality-selection-secret");
    }

    public record Option(Long id, String city, String name, String match, String selectionToken,
                         String provider, String providerPlaceId) {
        public Option(Long id, String city, String name, String match) { this(id, city, name, match, null, null, null); }
    }

    @Transactional(readOnly = true)
    public List<String> cities() {
        return CityRegistry.getSupportedCities().stream()
                .map(CityRegistry::canonicalCityName).sorted().toList();
    }

    public List<Option> suggest(String city, String input) {
        if (city == null || !CityRegistry.isCitySupported(city)) {
            throw new IllegalArgumentException("Choose a supported city");
        }
        if (input == null || input.isBlank()) return List.of();
        if (input.length() > 120) throw new IllegalArgumentException("Locality search is too long");
        String term = RentalSearchQuery.normalizeLocation(input);
        if (term.length() < 2) return List.of();
        String canonicalCity = CityRegistry.canonicalCityName(city);
        var exact = localities.findByCityIgnoreCaseAndSectorNameIgnoreCase(canonicalCity, input.trim());
        if (exact.isPresent()) return List.of(toOption(exact.get(), "canonical"));
        String aliasName = aliases.resolveLocalityAlias(term, canonicalCity.toLowerCase());
        if (aliasName != null) {
            var aliasMatch = localities.findByCityIgnoreCaseAndSectorNameIgnoreCase(canonicalCity, aliasName);
            if (aliasMatch.isPresent()) return List.of(toOption(aliasMatch.get(), "alias"));
        }
        List<Option> inCity = localities.findOnboardingSuggestions(canonicalCity, term, PageRequest.of(0, 8))
                .stream().map(locality -> toOption(locality, "canonical")).toList();
        if (inCity.stream().anyMatch(option -> option.name().toLowerCase(Locale.ROOT).startsWith(term))) return inCity;
        LinkedHashMap<String, Option> choices = new LinkedHashMap<>();
        for (Option option : inCity.stream().limit(3).toList())
            choices.put(dedupeKey(option.name()), option);
        if (term.length() >= 3) {
            List<ExternalLocalityProvider.Result> externalResults;
            try { externalResults = external.search(canonicalCity, term); }
            catch (RuntimeException ex) { externalResults = List.of(); }
            for (ExternalLocalityProvider.Result result : externalResults) {
                if (!canonicalCity.equalsIgnoreCase(result.city()) || !"MAPTILER".equals(result.source()) || result.name() == null
                        || result.name().isBlank() || result.name().length() > 120
                        || result.placeId() == null || result.placeId().isBlank()) continue;
                String key = dedupeKey(result.name());
                choices.putIfAbsent(key, new Option(null, canonicalCity, result.name().trim(), "external",
                        sign(canonicalCity, result.name().trim(), result.source(), result.placeId()),
                        result.source(), result.placeId()));
                if (choices.size() == 8) break;
            }
        }
        if (!choices.isEmpty()) return new ArrayList<>(choices.values());
        // A locality from another supported city is shown as an alternative, never silently selected.
        return localities.findTop8BySectorNameIgnoreCaseOrderByCityAsc(input.trim()).stream()
                .filter(locality -> CityRegistry.isCitySupported(locality.getCity()))
                .map(locality -> toOption(locality, "different_city")).toList();
    }

    @Transactional(readOnly = true)
    public Locality requireMatchingLocality(String city, Long localityId) {
        if (city == null || !CityRegistry.isCitySupported(city) || localityId == null) {
            throw new IllegalArgumentException("Choose a supported city and locality");
        }
        Locality locality = localities.findById(localityId)
                .orElseThrow(() -> new IllegalArgumentException("Choose a canonical locality"));
        if (!locality.getCity().equalsIgnoreCase(city)) {
            throw new IllegalArgumentException("This locality belongs to " + locality.getCity() + ". Choose a matching city.");
        }
        return locality;
    }

    private Option toOption(Locality locality, String match) {
        return new Option(locality.getId(), locality.getCity(), locality.getSectorName(), match);
    }

    private String dedupeKey(String name) {
        StringBuilder key = new StringBuilder(name.length());
        name.toLowerCase(Locale.ROOT).codePoints()
                .filter(Character::isLetterOrDigit).forEach(key::appendCodePoint);
        return key.toString();
    }

    public boolean validExternalSelection(String city, String name, String provider, String placeId, String token) {
        if (city == null || name == null || provider == null || placeId == null || token == null
                || !CityRegistry.isCitySupported(city) || name.isBlank() || name.length() > 120
                || name.chars().anyMatch(Character::isISOControl)
                || !"MAPTILER".equals(provider) || placeId.isBlank() || placeId.length() > 160
                || placeId.chars().anyMatch(Character::isISOControl)) return false;
        byte[] expected = sign(city, name, provider, placeId).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, token.getBytes(StandardCharsets.UTF_8));
    }

    private String sign(String city, String name, String provider, String placeId) {
        try {
            String payload = CityRegistry.canonicalCityName(city) + "\n" + name + "\n" + provider + "\n" + placeId;
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(bytes));
        } catch (Exception ex) {
            throw new IllegalStateException("Locality selection could not be signed", ex);
        }
    }
}
