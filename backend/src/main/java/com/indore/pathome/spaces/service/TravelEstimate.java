package com.indore.pathome.spaces.service;

import java.time.Duration;

public record TravelEstimate(Duration duration, Confidence confidence, String originSource) {
    public enum Confidence { HIGH, MEDIUM, LOW, UNKNOWN }
}
