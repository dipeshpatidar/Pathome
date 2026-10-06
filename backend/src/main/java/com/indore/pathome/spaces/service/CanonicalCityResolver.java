package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.SupportedCity;
import com.indore.pathome.spaces.repository.SupportedCityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Exact canonical identity lookup; active-state policy remains with the calling workflow. */
@Service
@Transactional(readOnly = true)
public class CanonicalCityResolver {
    private static final Pattern CANONICAL_CODE = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    private final SupportedCityRepository supportedCityRepository;

    public CanonicalCityResolver(SupportedCityRepository supportedCityRepository) {
        this.supportedCityRepository = supportedCityRepository;
    }

    public Optional<SupportedCity> resolveCode(String candidate) {
        if (candidate == null) return Optional.empty();
        String normalized = candidate.strip().toLowerCase(Locale.ROOT);
        if (!CANONICAL_CODE.matcher(normalized).matches()) return Optional.empty();
        return supportedCityRepository.findByCode(normalized);
    }

    public Optional<SupportedCity> resolveDisplayName(String candidate) {
        if (candidate == null || candidate.isBlank()) return Optional.empty();
        String normalized = candidate.strip().toLowerCase(Locale.ROOT);
        List<SupportedCity> exactMatches = supportedCityRepository.findExactDisplayNameMatches(normalized);
        return exactMatches.size() == 1 ? Optional.of(exactMatches.get(0)) : Optional.empty();
    }
}
