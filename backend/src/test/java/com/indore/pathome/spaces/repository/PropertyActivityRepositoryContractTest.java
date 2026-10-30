package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.service.PropertyActivityOrdering;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-query contract test. Genuine PostgreSQL execution remains a separate deferred gate.
 */
class PropertyActivityRepositoryContractTest {
    private static final Instant T = Instant.parse("2026-10-24T08:00:00.123456Z");

    @Test
    void repositorySourcesUseTheApprovedTwoSourceComparator() throws Exception {
        String publications = query(PropertyPublicationVersionRepository.class, "findActivityAfterAnchor");
        String revisions = query(PropertyRevisionEventRepository.class, "findActivityAfterAnchor");

        assertTrue(publications.contains(
                "p.publishedAt = :timestamp and :anchorRank = 0 and p.id < :anchorRowId"));
        assertFalse(publications.contains(":anchorRank = 1 or p.id < :anchorRowId"));
        assertTrue(publications.contains("order by p.publishedAt desc, p.id desc"));
        assertTrue(revisions.contains(
                "e.occurredAt = :timestamp and (:anchorRank = 0 or e.id < :anchorRowId)"));
        assertTrue(revisions.contains("order by e.occurredAt desc, e.id desc"));
    }

    @Test
    void equalTimeAndMixedTimestampRowsFollowStrictOlderThanAtBothSourceAnchors() {
        PropertyActivityOrdering.Entry publicationAnchor = entry(10L, PropertyActivityOrdering.Source.PUBLICATION);
        assertTrue(older(entry(9L, PropertyActivityOrdering.Source.PUBLICATION), publicationAnchor));
        assertFalse(older(entry(10L, PropertyActivityOrdering.Source.PUBLICATION), publicationAnchor));
        assertFalse(older(entry(11L, PropertyActivityOrdering.Source.PUBLICATION), publicationAnchor));
        assertTrue(older(entry(8L, PropertyActivityOrdering.Source.REVISION), publicationAnchor));
        assertTrue(older(entry(11L, PropertyActivityOrdering.Source.REVISION), publicationAnchor));

        PropertyActivityOrdering.Entry revisionAnchor = entry(10L, PropertyActivityOrdering.Source.REVISION);
        assertFalse(older(entry(11L, PropertyActivityOrdering.Source.PUBLICATION), revisionAnchor));
        assertTrue(older(entry(9L, PropertyActivityOrdering.Source.REVISION), revisionAnchor));
        assertFalse(older(entry(10L, PropertyActivityOrdering.Source.REVISION), revisionAnchor));
        assertFalse(older(entry(9L, PropertyActivityOrdering.Source.PUBLICATION), revisionAnchor));
        assertTrue(older(new PropertyActivityOrdering.Entry(null,
                PropertyActivityOrdering.Source.PUBLICATION, 99L, T.minusNanos(1_000)), revisionAnchor));
        assertFalse(older(new PropertyActivityOrdering.Entry(null,
                PropertyActivityOrdering.Source.PUBLICATION, 99L, T), revisionAnchor));
    }

    @Test
    void reportedPublicationRevisionCycleCannotReturnTheEarlierPublication() {
        List<PropertyActivityOrdering.Entry> rows = new ArrayList<>(List.of(
                entry(2L, PropertyActivityOrdering.Source.REVISION),
                entry(1L, PropertyActivityOrdering.Source.REVISION),
                entry(1L, PropertyActivityOrdering.Source.PUBLICATION)));
        rows.sort(PropertyActivityOrdering.ORDER);
        assertEquals(List.of("PUBLICATION:1", "REVISION:2", "REVISION:1"),
                rows.stream().map(row -> row.source() + ":" + row.rowId()).toList());

        PropertyActivityOrdering.Entry revisionAnchor = rows.get(1);
        assertTrue(rows.stream().noneMatch(candidate ->
                candidate.source() == PropertyActivityOrdering.Source.PUBLICATION
                        && older(candidate, revisionAnchor)));
    }

    private static boolean older(PropertyActivityOrdering.Entry candidate,
                                 PropertyActivityOrdering.Entry anchor) {
        return PropertyActivityOrdering.isOlder(candidate, anchor);
    }

    private static PropertyActivityOrdering.Entry entry(long id,
                                                         PropertyActivityOrdering.Source source) {
        return new PropertyActivityOrdering.Entry(null, source, id, T);
    }

    private static String query(Class<?> repository, String method) throws Exception {
        return repository.getMethod(method, Long.class, long.class, Instant.class,
                        int.class, long.class, org.springframework.data.domain.Pageable.class)
                .getAnnotation(Query.class).value();
    }
}
