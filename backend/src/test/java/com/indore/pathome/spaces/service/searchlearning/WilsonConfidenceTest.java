package com.indore.pathome.spaces.service.searchlearning;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class WilsonConfidenceTest {
    @ParameterizedTest
    @CsvSource({"3,3,0.4385029682", "30,30,0.8864866068", "300,300,0.9873570288", "15,30,0.3315412564", "0,0,0", "0,30,0"})
    void knownReferenceValues(long successes,long total,double expected) {
        assertEquals(expected,WilsonConfidence.lowerBound(successes,total,.95),1e-9);
    }
    @Test void smallSamplesAndThresholdBoundary() {
        assertTrue(WilsonConfidence.lowerBound(3,3,.95)<WilsonConfidence.lowerBound(30,30,.95));
        assertTrue(WilsonConfidence.lowerBound(30,30,.95)<WilsonConfidence.lowerBound(300,300,.95));
        assertTrue(WilsonConfidence.lowerBound(21,21,.95)<.85);
        assertTrue(WilsonConfidence.lowerBound(22,22,.95)>.85);
    }
    @ParameterizedTest @CsvSource({"-1,3,.95","4,3,.95","0,-1,.95","1,3,.97"})
    void invalidInputsFailClosed(long s,long n,double confidence) {
        assertThrows(IllegalArgumentException.class,()->WilsonConfidence.lowerBound(s,n,confidence));
    }
    @Test void supportedConfidenceLevelsAreConservative() {
        assertTrue(WilsonConfidence.lowerBound(30,30,.99)<WilsonConfidence.lowerBound(30,30,.95));
        assertTrue(WilsonConfidence.lowerBound(30,30,.95)<WilsonConfidence.lowerBound(30,30,.90));
    }
}
