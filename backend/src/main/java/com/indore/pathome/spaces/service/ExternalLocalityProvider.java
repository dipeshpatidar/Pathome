package com.indore.pathome.spaces.service;

import java.util.List;

/** Optional, provider-neutral locality discovery. Results are never canonical records. */
public interface ExternalLocalityProvider {
    record Result(String name, String city, String source, String placeId) {}
    List<Result> search(String city, String query);
}
