package com.indore.pathome.spaces.dto;

import java.util.List;

public record PublicSearchSuggestions(
        String query,
        List<PublicSearchSuggestion> suggestions,
        String effectiveCity,
        String citySource) {
    public PublicSearchSuggestions(String query, List<PublicSearchSuggestion> suggestions) {
        this(query, suggestions, null, null);
    }
}
