package com.indore.pathome.spaces.dto;

import java.math.BigDecimal;

/**
 * Lightweight, privacy-safe feedback payload sent when a user selects a suggestion
 * or executes a search. Contains structured identifiers only; no personal data or free text.
 */
public record SearchFeedbackRequest(
        String eventType,          // SUGGESTION_SELECTED | SEARCH_EXECUTED
        String candidateTerm,      // Normalised user-typed variant (max 120 chars)
        String canonicalLocality,  // Selected canonical locality (max 120 chars)
        String canonicalCity,      // Target city scope (max 120 chars)
        String selectedType,       // LOCALITY | CITY | SEARCH_QUERY
        Short selectedRank,        // 0-based rank of selected item
        String resolutionMethod,   // EXACT | PREFIX | ALIAS | FUZZY | UNRESOLVED | STRUCTURED
        BigDecimal fuzzyConfidence,// Similarity score if fuzzy
        String sessionId,          // Ephemeral anonymous session UUID (hashed by backend)
        String bhkKey,             // e.g. 2BHK
        String propertyTypeKey,    // e.g. FLAT
        String furnishingKey,      // e.g. SEMI_FURNISHED
        Boolean searchExecuted,    // true if search was submitted
        Integer resultCount,       // results returned
        Boolean zeroResult         // true if resultCount == 0
) {}
