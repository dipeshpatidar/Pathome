package com.indore.pathome.spaces.dto;

public record ParserModelRegistrationRequest(
        String modelVersion,
        String artifactPath,
        String datasetFingerprint) {
}
