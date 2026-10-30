package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.backoffice.BackofficeDtos;

import java.time.Instant;
import java.util.Comparator;

/** Stable two-source ordering that never uses a table-local ID as a time-order proxy. */
public final class PropertyActivityOrdering {
    private PropertyActivityOrdering() {}

    public enum Source {
        PUBLICATION(0), REVISION(1);

        private final int rank;
        Source(int rank) { this.rank = rank; }
        public int rank() { return rank; }
    }

    public record Entry(BackofficeDtos.Activity activity, Source source,
                        long rowId, Instant timestamp) {}

    public static final Comparator<Entry> ORDER =
            Comparator.comparing(Entry::timestamp).reversed()
                    .thenComparingInt(entry -> entry.source().rank())
                    .thenComparing(Comparator.comparingLong(Entry::rowId).reversed());

    public static boolean isOlder(Entry candidate, Entry anchor) {
        int time = candidate.timestamp().compareTo(anchor.timestamp());
        if (time < 0) return true;
        if (time > 0) return false;
        int rank = Integer.compare(candidate.source().rank(), anchor.source().rank());
        if (rank > 0) return true;
        if (rank < 0) return false;
        return candidate.rowId() < anchor.rowId();
    }

    public static boolean withinCohort(long rowId, Source source,
                                       long publicationCutoff, long revisionCutoff) {
        long cutoff = source == Source.PUBLICATION ? publicationCutoff : revisionCutoff;
        return rowId > 0 && rowId <= cutoff;
    }
}
