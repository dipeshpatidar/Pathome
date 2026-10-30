package com.indore.pathome.spaces.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PropertyActivityWriterInvariantTest {
    @Test
    void everyPublicationAndRevisionHistoryWriterHoldsTheListingLockFirst() throws Exception {
        String backoffice = source("BackofficePropertyService.java");
        String landlord = source("LandlordReviewService.java");
        String removal = source("PropertyRemovalService.java");
        String recorder = source("PropertyPublicationHistoryRecorder.java");

        assertTrue(recorder.contains("@Transactional(propagation = Propagation.MANDATORY)"));
        assertInOrder(backoffice, "private BackofficeDtos.Revision createRevision",
                "lockForSchedulingById(listingId)", "revisionEvent(listingId");
        assertInOrder(backoffice, "private BackofficeDtos.Revision updateRevision",
                "lockForSchedulingById(listingId)", "revisionEvent(listingId");
        assertInOrder(backoffice, "public BackofficeDtos.Committed discard",
                "lockForSchedulingById(listingId)", "revisionEvent(listingId");
        assertInOrder(backoffice, "public BackofficeDtos.Revision supersede",
                "lockForSchedulingById(listingId)", "revisionEvent(listingId");
        assertInOrder(backoffice, "public BackofficeDtos.PublishResult publish",
                "lockForSchedulingById(listingId)", "revisionEvent(listingId");
        assertInOrder(backoffice, "public BackofficeDtos.Committed lifecycle",
                "lockForSchedulingById(listingId)", "revisionEvent(listingId");

        assertInOrder(landlord, "public LandlordListingAction decideListing",
                "lockLandlordReviewId(listingId)", "publicationHistory.record");
        assertInOrder(landlord, "public LandlordListingAction approveRevision",
                "lockOwnedId(", "publicationHistory.record");

        assertInOrder(removal, "public PropertyRemovalDtos.CreateResult create",
                "lockOwnedFresh(listingId, userId)", "revisionEvent(listingId");
        assertInOrder(removal, "public PropertyRemovalDtos.RequestView withdraw",
                "lockOwnedFresh(listingId, userId)", "revisionEvent(listingId");
        assertInOrder(removal, "public PropertyRemovalDtos.RequestView decide",
                "lockForSchedulingById(listingId)", "revisionEvent(listingId");

        String activity = between(backoffice,
                "public BackofficeDtos.ActivityPage activity", "public BackofficeDtos.CommandOutcome");
        assertTrue(activity.contains("lockForSchedulingById(listingId)"));
        assertFalse(activity.contains("publications.save"));
        assertFalse(activity.contains("revisionEvents.save"));
    }

    @Test
    void v57IsAdditiveAndIndexesTheApprovedKeysetOrder() throws Exception {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V57__backoffice_activity_keyset_index.sql"));
        assertTrue(migration.contains(
                "property_publication_versions (listing_id, published_at DESC, id DESC)"));
        assertFalse(migration.contains("DROP "));
        assertFalse(migration.contains("DELETE "));
        assertFalse(migration.contains("UPDATE "));
    }

    private static String source(String file) throws Exception {
        return Files.readString(Path.of("src/main/java/com/indore/pathome/spaces/service", file));
    }

    private static void assertInOrder(String text, String method, String first, String second) {
        String body = between(text, method, "\n    @Transactional", "\n    private ", "\n    public ");
        assertTrue(body.indexOf(first) >= 0, method + " missing " + first);
        assertTrue(body.indexOf(second) >= 0, method + " missing " + second);
        assertTrue(body.indexOf(first) < body.indexOf(second), method + " must lock before writing evidence");
    }

    private static String between(String text, String start, String... ends) {
        int from = text.indexOf(start);
        assertTrue(from >= 0, "missing source segment " + start);
        int to = text.length();
        for (String end : ends) {
            int candidate = text.indexOf(end, from + start.length());
            if (candidate >= 0 && candidate < to) to = candidate;
        }
        return text.substring(from, to);
    }
}
