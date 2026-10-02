package com.indore.pathome.spaces.service;

import java.time.Instant;

public interface TravelTimeEstimator {
    TravelEstimate estimate(TravelPoint origin, TravelPoint destination, Instant departureAt);
}
