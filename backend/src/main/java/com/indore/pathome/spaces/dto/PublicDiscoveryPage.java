package com.indore.pathome.spaces.dto;

import java.util.List;

/**
 * Envelope returned by the paginated public discovery endpoint.
 *
 * <p>{@code hasMore} indicates whether a subsequent page exists and the client should
 * show a "Show More Properties" control. No total count is exposed because counting
 * all matching rows on every page request is expensive and the frontend does not need it.</p>
 */
public record PublicDiscoveryPage(
        List<PublicDiscoveryResponse> properties,
        int page,
        int pageSize,
        boolean hasMore
) {}
