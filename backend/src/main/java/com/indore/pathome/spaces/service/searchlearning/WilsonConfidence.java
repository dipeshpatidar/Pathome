package com.indore.pathome.spaces.service.searchlearning;

/** Two-sided Wilson interval lower endpoint; explicit supported confidence levels avoid approximation dependencies. */
public final class WilsonConfidence {
    private WilsonConfidence() {}
    public static double lowerBound(long successes, long observations, double confidence) {
        double z = confidence == .90 ? 1.6448536269514722 : confidence == .95 ? 1.959963984540054
                : confidence == .99 ? 2.5758293035489004 : Double.NaN;
        if (!Double.isFinite(z) || successes < 0 || observations < 0 || successes > observations)
            throw new IllegalArgumentException("Invalid Wilson inputs");
        if (observations == 0) return 0;
        double n = observations, p = successes / n, z2 = z * z;
        return Math.max(0, Math.min(1, (p + z2 / (2 * n)
                - z * Math.sqrt(p * (1 - p) / n + z2 / (4 * n * n))) / (1 + z2 / n)));
    }
}
