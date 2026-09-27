package com.indore.pathome.spaces.service.searchlearning;

import com.indore.pathome.spaces.entity.SearchQueryEvent;
import java.util.Locale;
import java.util.Set;

/** Client result counts / confidence / method are not evidence of success. */
public final class AliasEvidenceClassifier {
    public enum Strength { STRONG, MODERATE, WEAK, IGNORED }
    private static final Set<String> STRONG_TYPES = Set.of("ENTITY_MATCH", "LOCALITY", "SEARCH_QUERY");
    private AliasEvidenceClassifier() {}
    public static Strength classify(SearchQueryEvent e, boolean canonicalValid, boolean deterministicAgreement) {
        if (!canonicalValid || e.getSessionHash() == null || e.getSessionHash().isBlank()) return Strength.IGNORED;
        String type = e.getSelectedType() == null ? "" : e.getSelectedType().toUpperCase(Locale.ROOT);
        if (!"SUGGESTION_SELECTED".equals(e.getEventType())) return Strength.IGNORED;
        if (STRONG_TYPES.contains(type)) return Strength.STRONG;
        if ("QUERY_INTENT".equals(type)) return deterministicAgreement ? Strength.MODERATE : Strength.WEAK;
        return Strength.IGNORED;
    }
}
