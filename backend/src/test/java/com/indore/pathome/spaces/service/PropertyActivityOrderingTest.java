package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.backoffice.BackofficeDtos;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PropertyActivityOrderingTest {
    private static final Instant T = Instant.parse("2026-10-24T08:00:00.123456Z");

    @Test
    void equalTimestampsAndEqualNumericIdsOrderPublicationsBeforeRevisions() {
        PropertyActivityOrdering.Entry publication = entry(1L, PropertyActivityOrdering.Source.PUBLICATION);
        PropertyActivityOrdering.Entry revision = entry(1L, PropertyActivityOrdering.Source.REVISION);
        List<PropertyActivityOrdering.Entry> merged = new ArrayList<>(List.of(revision, publication));
        merged.sort(PropertyActivityOrdering.ORDER);
        assertEquals(List.of(publication, revision), merged);
    }

    @Test
    void strictOlderThanNeverRepeatsAnAnchorFromEitherSource() {
        PropertyActivityOrdering.Entry publicationAnchor = entry(10L, PropertyActivityOrdering.Source.PUBLICATION);
        assertFalse(PropertyActivityOrdering.isOlder(
                entry(10L, PropertyActivityOrdering.Source.PUBLICATION), publicationAnchor));
        assertTrue(PropertyActivityOrdering.isOlder(
                entry(9L, PropertyActivityOrdering.Source.PUBLICATION), publicationAnchor));
        assertTrue(PropertyActivityOrdering.isOlder(
                entry(100L, PropertyActivityOrdering.Source.REVISION), publicationAnchor));

        PropertyActivityOrdering.Entry revisionAnchor = entry(10L, PropertyActivityOrdering.Source.REVISION);
        assertFalse(PropertyActivityOrdering.isOlder(
                entry(10L, PropertyActivityOrdering.Source.REVISION), revisionAnchor));
        assertTrue(PropertyActivityOrdering.isOlder(
                entry(9L, PropertyActivityOrdering.Source.REVISION), revisionAnchor));
        assertFalse(PropertyActivityOrdering.isOlder(
                entry(999L, PropertyActivityOrdering.Source.PUBLICATION), revisionAnchor));
    }

    @Test
    void twoSourceCutoffsExcludePostCohortBackdatedAndEqualTimeInserts() {
        assertTrue(PropertyActivityOrdering.withinCohort(50L,
                PropertyActivityOrdering.Source.PUBLICATION, 50L, 40L));
        assertFalse(PropertyActivityOrdering.withinCohort(51L,
                PropertyActivityOrdering.Source.PUBLICATION, 50L, 40L));
        assertTrue(PropertyActivityOrdering.withinCohort(40L,
                PropertyActivityOrdering.Source.REVISION, 50L, 40L));
        assertFalse(PropertyActivityOrdering.withinCohort(41L,
                PropertyActivityOrdering.Source.REVISION, 50L, 40L));
    }

    @Test
    void traversalOfZeroOneTwentyTwentyOneFiftyAndMoreReturnsEveryEntryOnce() {
        for (int count : List.of(0, 1, 20, 21, 23, 50, 73)) {
            List<PropertyActivityOrdering.Entry> publications = new ArrayList<>();
            List<PropertyActivityOrdering.Entry> revisions = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                (index % 2 == 0 ? publications : revisions).add(new PropertyActivityOrdering.Entry(
                        activity((long) index), index % 2 == 0
                        ? PropertyActivityOrdering.Source.PUBLICATION
                        : PropertyActivityOrdering.Source.REVISION,
                        index + 1L, T.minusSeconds(index / 2L)));
            }
            List<PropertyActivityOrdering.Entry> all = new ArrayList<>(publications);
            all.addAll(revisions);
            all.sort(PropertyActivityOrdering.ORDER);
            List<PropertyActivityOrdering.Entry> traversed = new ArrayList<>();
            for (int offset = 0; offset < all.size(); offset += 20) {
                traversed.addAll(all.subList(offset, Math.min(all.size(), offset + 20)));
            }
            assertEquals(count, traversed.size());
            assertEquals(all, traversed);
        }
    }

    private static PropertyActivityOrdering.Entry entry(long id,
                                                        PropertyActivityOrdering.Source source) {
        return new PropertyActivityOrdering.Entry(activity(id), source, id, T);
    }

    private static BackofficeDtos.Activity activity(long id) {
        return new BackofficeDtos.Activity("PUBLICATION:" + id, source(id),
                "EVENT", null, 1L, "Actor", T, id, "Summary", Map.<String, Object>of());
    }

    private static String source(long id) {
        return id % 2 == 0 ? "PUBLICATION" : "REVISION";
    }
}
