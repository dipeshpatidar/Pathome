package com.indore.pathome.spaces.exception;

public class LocationLookupRateLimitException extends RuntimeException {
    public LocationLookupRateLimitException() { super("Please wait a moment before searching again."); }
}
