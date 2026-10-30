package com.indore.pathome.spaces.support;

import java.util.ArrayList;
import java.util.List;

/** Shared negative oracle for public responses that must not disclose private fixture facts. */
final class OgV63PrivacyOracle {
    static final List<String> PRIVATE_CANARIES = List.of(
            "+91 90000 00001",
            "Private owner canary",
            "Private address canary",
            "Private description canary",
            "Private amenity canary",
            "private-cover.webp");

    private OgV63PrivacyOracle() {}

    static List<String> findings(String responseText) {
        if (responseText == null) return List.of("null response");
        List<String> findings = new ArrayList<>();
        for (String canary : PRIVATE_CANARIES) {
            if (responseText.contains(canary)) findings.add(canary);
        }
        return List.copyOf(findings);
    }

    static void requireClean(String responseText) {
        List<String> findings = findings(responseText);
        if (!findings.isEmpty()) {
            throw new AssertionError(
                    "Public response exposed " + findings.size() + " private fixture canary value(s)");
        }
    }
}
