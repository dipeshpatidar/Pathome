package com.indore.pathome.spaces.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.service.CloudinaryService;
import com.indore.pathome.spaces.service.PublicPropertyReadService;
import com.indore.pathome.spaces.repository.ListingRepository;
import com.indore.pathome.spaces.repository.LocalityRepository;
import com.indore.pathome.spaces.repository.PropertyMediaAssetRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage C/D only: genuine Spring, Hibernate, HTTP and PostgreSQL on the exact sealed V63 run.
 * Every listing/media/city/locality fixture is synthetic projection data, not a publication workflow.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=" + OgV63Target.URL,
        "spring.datasource.username=" + OgV63Target.RUNTIME,
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.default_schema=public",
        "spring.task.scheduling.enabled=false",
        "spring.sql.init.mode=never",
        "spring.main.allow-bean-definition-overriding=true",
        "spring.jpa.show-sql=false",
        "APP_FIRST_ADMIN_PROVISIONING_ENABLED=false",
        "server.address=127.0.0.1",
        "server.forward-headers-strategy=none",
        "app.jwt.secret=pathome-og-v63-connected-jwt-secret-32-bytes",
        "pathome.visit.otp.hmac-secret=pathome-og-v63-connected-otp-secret-32-bytes",
        "pathome.crm.contact-encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "pathome.crm.contact-encryption-key-id=og-v63-primary",
        "pathome.crm.public-origin=https://pathome.test",
        "pathome.crm.meta-source-enabled=false",
        "pathome.crm.phone-verification-simulator-enabled=true",
        "pathome.crm.secure-cookies=false",
        "pathome.crm.phone-delivery-poll-delay-ms=3600000",
        "pathome.visit.outbox.poll-delay-ms=3600000",
        "pathome.visit.no-show-settlement-delay-ms=3600000",
        "pathome.visit.entitlement-reservation-poll-delay-ms=3600000",
        "pathome.visit.overrun-alert-poll-delay-ms=3600000",
        "search.learning.autonomous.enabled=false",
        "cloudinary.cloud-name=hbgzetjy",
        "cloudinary.api-key=og-v63-test-key",
        "cloudinary.api-secret=og-v63-test-secret"
})
@Import(OgV63RuntimeConfiguration.class)
@EnabledIfEnvironmentVariable(named = "PATHOME_OG_V63_CONNECTED", matches = "true")
class OgV63ConnectedIntegrationTest {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private static final long INDORE_ID = 9_100_001L;
    private static final long BHOPAL_ID = 9_100_002L;
    private static final long TRANSITION_ID = 9_100_003L;
    private static final long CONCURRENCY_ID = 9_100_004L;
    private static final long WRITE_ID = 9_100_005L;
    private static final long VISIBILITY_BASE = 9_110_000L;
    private static final long MEDIA_BASE = 9_120_000L;
    private static final long LITERAL_BASE = 9_130_000L;
    private static final long HEADERS_BASE = 9_140_000L;
    private static final long SIDE_EFFECT_ID = 9_150_001L;
    private static final String PRIVATE_CANARY = "+91 90000 00001";
    private static final String CLOUD = "https://res.cloudinary.com/hbgzetjy";
    private static FileChannel RUN_LEASE_CHANNEL;
    private static FileLock RUN_LEASE;

    @LocalServerPort private int port;
    @Autowired private ObjectMapper mapper;
    @Autowired private CloudinaryService cloudinary;
    @Autowired private PublicPropertyReadService publicReadService;
    @Autowired private ListingRepository listingRepository;
    @Autowired private LocalityRepository localityRepository;
    @Autowired private PropertyMediaAssetRepository mediaAssetRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ApplicationContext context;

    @BeforeAll
    static void acquireFixtureCampaignLease() throws Exception {
        RUN_LEASE_CHANNEL = FileChannel.open(
                OgV63Target.ROOT.resolve("run.lease"), StandardOpenOption.WRITE);
        RUN_LEASE = RUN_LEASE_CHANNEL.tryLock();
        assertNotNull(RUN_LEASE, "A competing V63 fixture/sealing stage owns the run lease");
    }

    @AfterAll
    static void releaseFixtureCampaignLease() throws Exception {
        try {
            if (RUN_LEASE != null) RUN_LEASE.release();
        } finally {
            if (RUN_LEASE_CHANNEL != null) RUN_LEASE_CHANNEL.close();
        }
    }

    @BeforeEach
    void clearCheckpoint() {
        OgV63MediaCheckpoint.clear();
    }

    @AfterEach
    void removeCheckpoint() {
        OgV63MediaCheckpoint.clear();
    }

    @Test
    void h01ExactDatabaseJsonAndHtmlMetadataParity() throws Exception {
        JdbcTemplate db = fixtures();
        seedListing(db, INDORE_ID, "indore", "Indore", "OG Synthetic West",
                "2 BHK", "21000.01", "PUBLISHED", "ACTIVE");
        seedAsset(db, 9_200_001L, INDORE_ID, "IMAGE",
                "pathome/properties/images/og-v63-indore-cover",
                CLOUD + "/image/upload/pathome/properties/images/og-v63-indore-cover.webp",
                null, true, false, 0);
        seedAsset(db, 9_200_002L, INDORE_ID, "IMAGE", null,
                "https://images.invalid.example/private-cover.webp",
                null, false, false, 2);
        seedAsset(db, 9_200_003L, INDORE_ID, "IMAGE",
                "pathome/properties/images/og-v63-retired-cover",
                CLOUD + "/image/upload/pathome/properties/images/og-v63-retired-cover.webp",
                null, false, true, 1);

        String databaseCity = db.queryForObject("""
                select sc.display_name from listings l
                join localities loc on loc.id=l.canonical_locality_id
                join supported_cities sc on sc.id=loc.supported_city_id
                where l.id=?
                """, String.class, INDORE_ID);
        String databaseSector = db.queryForObject("""
                select loc.sector_name from listings l
                join localities loc on loc.id=l.canonical_locality_id where l.id=?
                """, String.class, INDORE_ID);
        BigDecimal databaseRent = db.queryForObject(
                "select monthly_rent from rental_details where id=?", BigDecimal.class, INDORE_ID);
        long databaseVersion = db.queryForObject(
                "select version from listings where id=?", Long.class, INDORE_ID);
        String expectedTitle = "2 BHK Flat in " + databaseSector + ", " + databaseCity;
        String expectedCover = CLOUD
                + "/image/upload/pathome/properties/images/og-v63-indore-cover.webp";

        HttpResponse<String> jsonResponse = send("GET", "/api/v1/properties/" + INDORE_ID, null);
        assertEquals(200, jsonResponse.statusCode());
        JsonNode json = mapper.readTree(jsonResponse.body());
        assertEquals(INDORE_ID, json.path("id").asLong());
        assertEquals(expectedTitle, json.path("title").asText());
        assertEquals(databaseCity, json.path("city").asText());
        assertEquals(databaseSector, json.path("sector").asText());
        assertEquals(0, databaseRent.compareTo(json.path("monthlyRent").decimalValue()));
        assertEquals(databaseVersion, json.path("listingVersion").asLong());
        assertEquals(1, json.path("media").size());
        assertEquals(expectedCover, json.path("media").get(0).path("mediaUrl").asText());
        assertNoPrivateCanaries(jsonResponse.body());

        HttpResponse<String> htmlResponse = assertHtmlRepresentation(
                "GET", "/property/" + INDORE_ID, 200);
        OgV63HtmlMetadata metadata = OgV63HtmlMetadata.parse(htmlResponse.body());
        assertEquals(expectedTitle, metadata.singleProperty("og:title"));
        assertEquals(metadata.singleProperty("og:title"), json.path("title").asText());
        assertEquals("https://pathome.test/property/" + INDORE_ID,
                metadata.singleProperty("og:url"));
        assertEquals(expectedCover, metadata.singleProperty("og:image"));
        assertEquals(json.path("media").get(0).path("mediaUrl").asText(),
                metadata.singleProperty("og:image"));
        assertTrue(metadata.singleProperty("og:description")
                .contains("Rent ₹21,000.01 per month"));
        assertEquals("index,follow", metadata.singleNamed("robots"));
        assertEquals(List.of("https://pathome.test/property/" + INDORE_ID),
                metadata.canonicals);
        assertEquals(1, metadata.titles.size());
        assertFalse(metadata.properties.getOrDefault("og:image", List.of()).isEmpty());
        assertNoPrivateCanaries(htmlResponse.body());
        assertEquals(0, OgV63SideEffectObserver.differences(
                OgV63SideEffectObserver.snapshot(), OgV63SideEffectObserver.snapshot()).size());
    }

    @Test
    void h02PublishedNonPublicInactiveAndMissingVisibilityMatrix() throws Exception {
        JdbcTemplate db = fixtures();
        long published = VISIBILITY_BASE + 1;
        long paused = VISIBILITY_BASE + 2;
        long submitted = VISIBILITY_BASE + 3;
        long archived = VISIBILITY_BASE + 4;
        long inactive = VISIBILITY_BASE + 5;
        long missingContext = VISIBILITY_BASE + 6;
        long missingCityContext = VISIBILITY_BASE + 7;
        long draft = VISIBILITY_BASE + 8;
        long transition = VISIBILITY_BASE + 20;
        long citySnapshot = VISIBILITY_BASE + 21;
        seedListing(db, published, "indore", "Indore", "OG Visible",
                "2 BHK", "21000", "PUBLISHED", "ACTIVE");
        assertVisible(published, "Indore", "OG Visible");
        seedListing(db, paused, "indore", "Indore", "OG Paused",
                "2 BHK", "22000", "PAUSED", "ACTIVE");
        seedListing(db, submitted, "indore", "Indore", "OG Submitted",
                "2 BHK", "23000", "SUBMITTED", "ACTIVE");
        seedListing(db, archived, "indore", "Indore", "OG Archived",
                "2 BHK", "24000", "ARCHIVED", "ACTIVE");
        seedListing(db, draft, "indore", "Indore", "OG Draft",
                "2 BHK", "24500", "DRAFT", "ACTIVE");
        long inactiveCity = seedCity(db, 9_710_001L, "og-inactive-city",
                "OG Inactive City", false);
        seedListing(db, inactive, inactiveCity, "OG Inactive City", "OG Inactive Sector",
                "1 BHK", "18000", "PUBLISHED", "ACTIVE");
        long missingLocality = seedListing(db, missingContext, "indore", "Indore",
                "OG Missing Context", "1 BHK", "17000", "PUBLISHED", "ACTIVE");
        db.update("update listings set canonical_locality_id=null where id=?", missingLocality);
        seedListing(db, missingCityContext, "indore", "Indore",
                "OG Missing City Context", "1 BHK", "16000", "PUBLISHED", "ACTIVE");
        db.update("update localities set supported_city_id=null where id=?",
                missingCityContext + 1_000_000L);

        Map<Long,String> hiddenCases = new LinkedHashMap<>();
        hiddenCases.put(paused, "paused");
        hiddenCases.put(submitted, "submitted");
        hiddenCases.put(archived, "archived");
        hiddenCases.put(draft, "draft");
        hiddenCases.put(missingContext, "missing canonical locality");
        hiddenCases.put(VISIBILITY_BASE + 999, "missing property");
        hiddenCases.put(-1L, "invalid numeric identifier");
        hiddenCases.put(missingCityContext, "missing canonical City");
        hiddenCases.put(inactive, "inactive canonical City");
        assertAll(hiddenCases.entrySet().stream()
                .map(hidden -> (org.junit.jupiter.api.function.Executable)
                        () -> assertHidden(hidden.getKey(), hidden.getValue()))
                .toList());
        assertHiddenText("not-a-positive-long");
        assertEquals(404, send("GET", "/property/0", null).statusCode());
        assertEquals(404, send("GET", "/property/999999999999999999999999", null).statusCode());

        long activeCity = db.queryForObject(
                "select id from supported_cities where code='indore'", Long.class);
        seedListing(db, transition, activeCity, "Indore", "OG City Transition",
                "2 BHK", "20500", "PUBLISHED", "ACTIVE");
        assertVisible(transition, "Indore", "OG City Transition");
        db.update("update localities set supported_city_id=? where id=?",
                inactiveCity, transition + 1_000_000L);
        assertHidden(transition, "active-to-inactive City transition");
        db.update("update localities set supported_city_id=? where id=?",
                activeCity, transition + 1_000_000L);
        assertVisible(transition, "Indore", "OG City Transition");

        long snapshotCity = seedCity(db, 9_710_002L, "og-city-snapshot",
                "OG Snapshot City", true);
        seedListing(db, citySnapshot, snapshotCity, "OG Snapshot City",
                "OG Snapshot Sector", "2 BHK", "20750", "PUBLISHED", "ACTIVE");
        CountDownLatch cityReadReached = new CountDownLatch(1);
        CountDownLatch cityReadRelease = new CountDownLatch(1);
        OgV63MediaCheckpoint.install(() -> {
            cityReadReached.countDown();
            assertTrue(cityReadRelease.await(10, TimeUnit.SECONDS));
        });
        var cityReadPool = Executors.newSingleThreadExecutor();
        try {
            Future<HttpResponse<String>> cityRead =
                    cityReadPool.submit(() -> send("GET", "/property/" + citySnapshot, null));
            assertTrue(cityReadReached.await(10, TimeUnit.SECONDS));
            transaction(db, () -> db.update(
                    "update supported_cities set active=false where id=?", snapshotCity));
            cityReadRelease.countDown();
            HttpResponse<String> oldSnapshot = cityRead.get(10, TimeUnit.SECONDS);
            assertEquals(200, oldSnapshot.statusCode());
            assertEquals("2 BHK Flat in OG Snapshot Sector, OG Snapshot City",
                    OgV63HtmlMetadata.parse(oldSnapshot.body()).singleProperty("og:title"));
        } finally {
            cityReadRelease.countDown();
            cityReadPool.shutdownNow();
            OgV63MediaCheckpoint.clear();
        }
        assertHidden(citySnapshot, "committed City deactivation");
        transaction(db, () -> db.update(
                "update supported_cities set active=true where id=?", snapshotCity));
        assertVisible(citySnapshot, "OG Snapshot City", "OG Snapshot Sector");
    }

    @Test
    void h03CompleteCoverEligibilityReplacementAndRetirementMatrix() throws Exception {
        JdbcTemplate db = fixtures();
        long noMedia = MEDIA_BASE + 1;
        long valid = MEDIA_BASE + 2;
        long retired = MEDIA_BASE + 3;
        long video = MEDIA_BASE + 4;
        long foreign = MEDIA_BASE + 5;
        long malformed = MEDIA_BASE + 6;
        long wrongListing = MEDIA_BASE + 7;
        long replacement = MEDIA_BASE + 8;
        long reverseReplacement = MEDIA_BASE + 9;
        long rolledBackReplacement = MEDIA_BASE + 10;
        for (long id : List.of(noMedia, valid, retired, video, foreign, malformed,
                wrongListing, replacement, reverseReplacement, rolledBackReplacement)) {
            seedListing(db, id, "indore", "Indore", "OG Media " + id,
                    "2 BHK", "21000", "PUBLISHED", "ACTIVE");
        }
        assertNoImage(noMedia);
        String validUrl = CLOUD
                + "/image/upload/pathome/properties/images/og-v63-media-valid.webp";
        seedAsset(db, 9_220_002L, valid, "IMAGE",
                "pathome/properties/images/og-v63-media-valid",
                validUrl, null, true, false, 0);
        assertImage(valid, validUrl);
        seedAsset(db, 9_220_003L, retired, "IMAGE",
                "pathome/properties/images/og-v63-media-retired",
                CLOUD + "/image/upload/pathome/properties/images/og-v63-media-retired.webp",
                null, true, true, 0);
        assertNoImage(retired);
        seedAsset(db, 9_220_004L, video, "VIDEO_WALKTHROUGH",
                "pathome/properties/videos/og-v63-media-video",
                CLOUD + "/video/upload/pathome/properties/videos/og-v63-media-video.mp4",
                null, true, false, 0);
        assertNoImage(video);
        seedAsset(db, 9_220_005L, foreign, "IMAGE", "pathome/private/foreign",
                "https://foreign.invalid/private/cover.webp", null, true, false, 0);
        assertNoImage(foreign);
        seedAsset(db, 9_220_006L, malformed, "IMAGE",
                "pathome/properties/images/does-not-match",
                CLOUD + "/image/upload/pathome/properties/images/private/og-v63-malformed.webp",
                null, true, false, 0);
        assertNoImage(malformed);
        seedAsset(db, 9_220_007L, wrongListing, "IMAGE",
                "pathome/properties/images/og-v63-wrong-listing",
                CLOUD + "/image/upload/pathome/properties/images/og-v63-wrong-listing.webp",
                null, true, false, 1);
        String oldUrl = CLOUD
                + "/image/upload/pathome/properties/images/og-v63-replace-old.webp";
        String newUrl = CLOUD
                + "/image/upload/pathome/properties/images/og-v63-replace-new.webp";
        db.update("update property_media_assets set retired_at=current_timestamp where id=?",
                9_220_009L);
        seedAsset(db, 9_220_008L, replacement, "IMAGE",
                "pathome/properties/images/og-v63-replace-old", oldUrl,
                null, true, false, 10);
        assertImage(replacement, oldUrl);
        transaction(db, () -> {
            db.update("update property_media_assets set retired_at=current_timestamp where id=?",
                    9_220_008L);
            seedAsset(db, 9_220_009L, replacement, "IMAGE",
                    "pathome/properties/images/og-v63-replace-new", newUrl,
                    null, true, false, 0);
        });
        assertImage(replacement, newUrl);

        String reverseOld = CLOUD
                + "/image/upload/pathome/properties/images/og-v63-reverse-old.webp";
        String reverseNew = CLOUD
                + "/image/upload/pathome/properties/images/og-v63-reverse-new.webp";
        db.update("""
                update property_media_assets set retired_at=current_timestamp
                where listing_id=? and retired_at is null
                """, reverseReplacement);
        seedAsset(db, 9_260_001L, reverseReplacement, "IMAGE",
                "pathome/properties/images/og-v63-reverse-old", reverseOld,
                null, true, false, 0);
        assertImage(reverseReplacement, reverseOld);
        transaction(db, () -> {
            seedAsset(db, 9_260_002L, reverseReplacement, "IMAGE",
                    "pathome/properties/images/og-v63-reverse-new", reverseNew,
                    null, true, false, 0);
            db.update("""
                    update property_media_assets set is_primary_cover=false
                    where id=? and retired_at is null
                    """, 9_260_001L);
        });
        assertImage(reverseReplacement, reverseNew);
        transaction(db, () -> db.update("""
                update property_media_assets set retired_at=current_timestamp
                where id=? and retired_at is null
                """, 9_260_001L));
        assertImage(reverseReplacement, reverseNew);

        String rollbackOld = CLOUD
                + "/image/upload/pathome/properties/images/og-v63-rollback-old.webp";
        db.update("""
                update property_media_assets set retired_at=current_timestamp
                where listing_id=? and retired_at is null
                """, rolledBackReplacement);
        seedAsset(db, 9_260_010L, rolledBackReplacement, "IMAGE",
                "pathome/properties/images/og-v63-rollback-old", rollbackOld,
                null, true, false, 0);
        assertImage(rolledBackReplacement, rollbackOld);
        rollbackTransaction(db, () -> {
            db.update("""
                    update property_media_assets set
                        retired_at=current_timestamp,is_primary_cover=false
                    where id=? and retired_at is null
                    """, 9_260_010L);
            seedAsset(db, 9_260_011L, rolledBackReplacement, "IMAGE",
                    "pathome/properties/images/og-v63-rollback-valid",
                    CLOUD + "/image/upload/pathome/properties/images/og-v63-rollback-valid.webp",
                    null, true, false, 0);
            seedAsset(db, 9_260_012L, rolledBackReplacement, "IMAGE",
                    "pathome/properties/images/og-v63-rollback-retired",
                    CLOUD + "/image/upload/pathome/properties/images/og-v63-rollback-retired.webp",
                    null, true, true, 0);
            seedAsset(db, 9_260_013L, rolledBackReplacement, "IMAGE",
                    "pathome/private/rollback-foreign",
                    "https://foreign.invalid/private/rollback.webp",
                    null, true, false, 0);
            seedAsset(db, 9_260_014L, rolledBackReplacement, "IMAGE",
                    "pathome/properties/images/og-v63-rollback-untrusted",
                    CLOUD + "/image/upload/pathome/properties/images/private/og-v63-rollback-untrusted.webp",
                    null, true, false, 0);
        });
        assertImage(rolledBackReplacement, rollbackOld);
        HttpResponse<String> rollbackHtml =
                send("GET", "/property/" + rolledBackReplacement, null);
        assertFalse(rollbackHtml.body().contains("rollback-valid"));
        assertFalse(rollbackHtml.body().contains("rollback-retired"));
        assertFalse(rollbackHtml.body().contains("foreign.invalid"));
        assertFalse(rollbackHtml.body().contains("rollback-untrusted"));

        JsonNode validMedia = mapper.readTree(
                send("GET", "/api/v1/properties/" + valid, null).body());
        assertEquals(1, validMedia.path("media").size());
        assertFalse(validMedia.toString().contains("wrong-listing"));
    }

    @Test
    void h04LiteralMetadataCrossCityAndFailureRecoveryOrder() throws Exception {
        JdbcTemplate db = fixtures();
        long cityA = seedCity(db, 9_720_001L, "og-literal-a",
                "मूल्य $1 \\\\ ${name} & \"City\" <script>", true);
        long cityB = seedCity(db, 9_720_002L, "og-literal-b",
                "दूसरा $0 \\\\ trailing\\\\ ${name}", true);
        long a = LITERAL_BASE + 1;
        long b = LITERAL_BASE + 2;
        String sectorA = "पश्चिम $1 \\\\ ${name} & \"Sector\" <b>";
        String sectorB = "पूर्व $0 \\\\ ${name} trailing\\\\";
        seedListing(db, a, cityA, "मूल्य $1 \\\\ ${name} & \"City\" <script>",
                sectorA, "2 BHK", "21000.01", "PUBLISHED", "ACTIVE");
        seedListing(db, b, cityB, "दूसरा $0 \\\\ trailing\\\\ ${name}",
                sectorB, "3 BHK", "31000.50", "PUBLISHED", "ACTIVE");
        seedAsset(db, 9_230_001L, a, "IMAGE",
                "pathome/properties/images/og-v63-literal-a",
                CLOUD + "/image/upload/pathome/properties/images/og-v63-literal-a.webp",
                null, true, false, 0);

        assertLiteralProperty(a, sectorA, "मूल्य $1 \\\\ ${name} & \"City\" <script>");
        assertLiteralProperty(b, sectorB, "दूसरा $0 \\\\ trailing\\\\ ${name}");
        assertLiteralProperty(a, sectorA, "मूल्य $1 \\\\ ${name} & \"City\" <script>");
        db.update("update listings set workflow_status='PAUSED' where id=?", a);
        assertHidden(a);
        db.update("update listings set workflow_status='PUBLISHED' where id=?", a);
        assertLiteralProperty(a, sectorA, "मूल्य $1 \\\\ ${name} & \"City\" <script>");
    }

    @Test
    void h05DeterministicRepeatableReadWithCommittedWriterAndTornControl() throws Exception {
        JdbcTemplate db = fixtures();
        long id = WRITE_ID;
        seedListing(db, id, "indore", "Indore", "OG Checkpoint",
                "2 BHK", "25000.00", "PUBLISHED", "ACTIVE");
        db.update("""
                update property_media_assets set retired_at=current_timestamp
                where listing_id=? and retired_at is null
                """, id);
        String before = CLOUD
                + "/image/upload/pathome/properties/images/og-v63-checkpoint-before.webp";
        String after = CLOUD
                + "/image/upload/pathome/properties/images/og-v63-checkpoint-after.webp";
        seedAsset(db, 9_250_001L, id, "IMAGE",
                "pathome/properties/images/og-v63-checkpoint-before",
                before, null, true, false, 0);

        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        OgV63MediaCheckpoint.install(() -> {
            reached.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS),
                    "checkpoint release was not signalled");
        });
        var pool = Executors.newSingleThreadExecutor();
        try {
            Future<HttpResponse<String>> reader = pool.submit(
                    () -> send("GET", "/api/v1/properties/" + id, null));
            assertTrue(reached.await(10, TimeUnit.SECONDS));
            commitCoherentWriterChange(db, id, "26500.25", after,
                    "pathome/properties/images/og-v63-checkpoint-after");
            release.countDown();
            JsonNode result = mapper.readTree(reader.get(10, TimeUnit.SECONDS).body());
            assertEquals(0, new BigDecimal("25000.00")
                    .compareTo(result.path("monthlyRent").decimalValue()));
            assertTrue(result.path("media").get(0).path("mediaUrl").asText().endsWith(
                    "og-v63-checkpoint-before.webp"));
        } finally {
            release.countDown();
            pool.shutdownNow();
            OgV63MediaCheckpoint.clear();
        }
        JsonNode fresh = mapper.readTree(send(
                "GET", "/api/v1/properties/" + id, null).body());
        assertEquals(0, new BigDecimal("26500.25")
                .compareTo(fresh.path("monthlyRent").decimalValue()));
        assertTrue(fresh.path("media").get(0).path("mediaUrl").asText()
                .endsWith("og-v63-checkpoint-after.webp"));

        String second = CLOUD
                + "/image/upload/pathome/properties/images/og-v63-checkpoint-second.webp";
        CountDownLatch tornReached = new CountDownLatch(1);
        CountDownLatch tornRelease = new CountDownLatch(1);
        OgV63MediaCheckpoint.install(() -> {
            tornReached.countDown();
            assertTrue(tornRelease.await(10, TimeUnit.SECONDS));
        });
        var tornPool = Executors.newSingleThreadExecutor();
        TransactionTemplate weakenedTransaction = new TransactionTemplate(transactionManager);
        weakenedTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        weakenedTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        try {
            PropertyMediaAssetRepository checkpointedMedia =
                    OgV63MediaCheckpoint.decorate(mediaAssetRepository);
            PublicPropertyReadService directRead = new PublicPropertyReadService(
                    listingRepository, localityRepository, checkpointedMedia, cloudinary);
            Future<PublicPropertyReadService.PublicPropertySnapshot> weakened =
                    tornPool.submit(() -> weakenedTransaction.execute(status ->
                            directRead.findPublicSnapshot(id).orElseThrow()));
            assertTrue(tornReached.await(10, TimeUnit.SECONDS));
            commitCoherentWriterChange(db, id, "28900.75", second,
                    "pathome/properties/images/og-v63-checkpoint-second");
            tornRelease.countDown();
            var torn = weakened.get(10, TimeUnit.SECONDS);
            assertEquals(0, new BigDecimal("26500.25").compareTo(torn.monthlyRent()),
                    "the weakened transaction must retain the first rent read");
            assertEquals(second, torn.coverImageUrl(),
                    "the READ_COMMITTED mutant must expose a mixed old-rent/new-media snapshot");
        } finally {
            tornRelease.countDown();
            tornPool.shutdownNow();
            OgV63MediaCheckpoint.clear();
        }

        long reverseHttpId = WRITE_ID + 100;
        long reverseReadCommittedId = WRITE_ID + 101;
        long rollbackId = WRITE_ID + 102;
        for (long snapshotId : List.of(reverseHttpId, reverseReadCommittedId, rollbackId)) {
            seedListing(db, snapshotId, "indore", "Indore", "OG Reverse Snapshot " + snapshotId,
                    "2 BHK", "23000.00", "PUBLISHED", "ACTIVE");
        }
        String reverseHttpOld = CLOUD
                + "/image/upload/pathome/properties/images/og-v63-reverse-http-old.webp";
        String reverseHttpNew = CLOUD
                + "/image/upload/pathome/properties/images/og-v63-reverse-http-new.webp";
        db.update("""
                update property_media_assets set retired_at=current_timestamp
                where listing_id=? and retired_at is null
                """, reverseHttpId);
        seedAsset(db, 9_260_101L, reverseHttpId, "IMAGE",
                "pathome/properties/images/og-v63-reverse-http-old", reverseHttpOld,
                null, true, false, 0);
        CountDownLatch reverseHttpReached = new CountDownLatch(1);
        CountDownLatch reverseHttpRelease = new CountDownLatch(1);
        OgV63MediaCheckpoint.install(() -> {
            reverseHttpReached.countDown();
            assertTrue(reverseHttpRelease.await(10, TimeUnit.SECONDS));
        });
        var reverseHttpPool = Executors.newSingleThreadExecutor();
        try {
            Future<HttpResponse<String>> reader = reverseHttpPool.submit(
                    () -> send("GET", "/api/v1/properties/" + reverseHttpId, null));
            assertTrue(reverseHttpReached.await(10, TimeUnit.SECONDS));
            transaction(db, () -> db.update("""
                    update property_media_assets set media_url=?,cloudinary_public_id=?
                    where listing_id=? and retired_at is null
                    """, reverseHttpNew,
                    "pathome/properties/images/og-v63-reverse-http-new", reverseHttpId));
            rollbackTransaction(db, () -> db.update(
                    "update rental_details set monthly_rent=27999.99 where id=?",
                    reverseHttpId));
            reverseHttpRelease.countDown();
            JsonNode reverseSnapshot = mapper.readTree(
                    reader.get(10, TimeUnit.SECONDS).body());
            assertEquals(0, new BigDecimal("23000.00")
                    .compareTo(reverseSnapshot.path("monthlyRent").decimalValue()));
            assertEquals(reverseHttpOld,
                    reverseSnapshot.path("media").get(0).path("mediaUrl").asText());
        } finally {
            reverseHttpRelease.countDown();
            reverseHttpPool.shutdownNow();
            OgV63MediaCheckpoint.clear();
        }
        JsonNode reverseFresh = mapper.readTree(
                send("GET", "/api/v1/properties/" + reverseHttpId, null).body());
        assertEquals(0, new BigDecimal("23000.00")
                .compareTo(reverseFresh.path("monthlyRent").decimalValue()));
        assertEquals(reverseHttpNew,
                reverseFresh.path("media").get(0).path("mediaUrl").asText());

        String reverseReadCommittedOld = CLOUD
                + "/image/upload/pathome/properties/images/og-v63-reverse-rc-old.webp";
        String reverseReadCommittedNew = CLOUD
                + "/image/upload/pathome/properties/images/og-v63-reverse-rc-new.webp";
        db.update("""
                update property_media_assets set retired_at=current_timestamp
                where listing_id=? and retired_at is null
                """, reverseReadCommittedId);
        seedAsset(db, 9_260_102L, reverseReadCommittedId, "IMAGE",
                "pathome/properties/images/og-v63-reverse-rc-old",
                reverseReadCommittedOld, null, true, false, 0);
        CountDownLatch reverseReadCommittedReached = new CountDownLatch(1);
        CountDownLatch reverseReadCommittedRelease = new CountDownLatch(1);
        OgV63MediaCheckpoint.install(() -> {
            reverseReadCommittedReached.countDown();
            assertTrue(reverseReadCommittedRelease.await(10, TimeUnit.SECONDS));
        });
        var reverseReadCommittedPool = Executors.newSingleThreadExecutor();
        TransactionTemplate reverseReadCommittedTransaction =
                new TransactionTemplate(transactionManager);
        reverseReadCommittedTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        reverseReadCommittedTransaction.setIsolationLevel(
                TransactionDefinition.ISOLATION_READ_COMMITTED);
        try {
            PropertyMediaAssetRepository checkpointedMedia =
                    OgV63MediaCheckpoint.decorate(mediaAssetRepository);
            PublicPropertyReadService directRead = new PublicPropertyReadService(
                    listingRepository, localityRepository, checkpointedMedia, cloudinary);
            Future<PublicPropertyReadService.PublicPropertySnapshot> reader =
                    reverseReadCommittedPool.submit(() -> reverseReadCommittedTransaction.execute(
                            status -> directRead.findPublicSnapshot(reverseReadCommittedId)
                                    .orElseThrow()));
            assertTrue(reverseReadCommittedReached.await(10, TimeUnit.SECONDS));
            transaction(db, () -> db.update("""
                    update property_media_assets set media_url=?,cloudinary_public_id=?
                    where listing_id=? and retired_at is null
                    """, reverseReadCommittedNew,
                    "pathome/properties/images/og-v63-reverse-rc-new",
                    reverseReadCommittedId));
            rollbackTransaction(db, () -> db.update(
                    "update rental_details set monthly_rent=28999.99 where id=?",
                    reverseReadCommittedId));
            reverseReadCommittedRelease.countDown();
            var tornReverse = reader.get(10, TimeUnit.SECONDS);
            assertEquals(0, new BigDecimal("23000.00")
                    .compareTo(tornReverse.monthlyRent()));
            assertEquals(reverseReadCommittedNew, tornReverse.coverImageUrl(),
                    "the reverse READ_COMMITTED schedule must expose old rent with new media");
        } finally {
            reverseReadCommittedRelease.countDown();
            reverseReadCommittedPool.shutdownNow();
            OgV63MediaCheckpoint.clear();
        }

        String rollbackOld = CLOUD
                + "/image/upload/pathome/properties/images/og-v63-writer-rollback-old.webp";
        String rollbackUncommitted = CLOUD
                + "/image/upload/pathome/properties/images/og-v63-writer-rollback-new.webp";
        db.update("""
                update property_media_assets set retired_at=current_timestamp
                where listing_id=? and retired_at is null
                """, rollbackId);
        seedAsset(db, 9_260_103L, rollbackId, "IMAGE",
                "pathome/properties/images/og-v63-writer-rollback-old", rollbackOld,
                null, true, false, 0);
        CountDownLatch rollbackReached = new CountDownLatch(1);
        CountDownLatch rollbackRelease = new CountDownLatch(1);
        OgV63MediaCheckpoint.install(() -> {
            rollbackReached.countDown();
            assertTrue(rollbackRelease.await(10, TimeUnit.SECONDS));
        });
        var rollbackPool = Executors.newSingleThreadExecutor();
        try {
            Future<HttpResponse<String>> reader = rollbackPool.submit(
                    () -> send("GET", "/api/v1/properties/" + rollbackId, null));
            assertTrue(rollbackReached.await(10, TimeUnit.SECONDS));
            rollbackTransaction(db, () -> {
                db.update("update rental_details set monthly_rent=29999.99 where id=?", rollbackId);
                db.update("""
                        update property_media_assets set media_url=?,cloudinary_public_id=?
                        where listing_id=? and retired_at is null
                        """, rollbackUncommitted,
                        "pathome/properties/images/og-v63-writer-rollback-new", rollbackId);
            });
            rollbackRelease.countDown();
            JsonNode rollbackSnapshot = mapper.readTree(
                    reader.get(10, TimeUnit.SECONDS).body());
            assertEquals(0, new BigDecimal("23000.00")
                    .compareTo(rollbackSnapshot.path("monthlyRent").decimalValue()));
            assertEquals(rollbackOld,
                    rollbackSnapshot.path("media").get(0).path("mediaUrl").asText());
        } finally {
            rollbackRelease.countDown();
            rollbackPool.shutdownNow();
            OgV63MediaCheckpoint.clear();
        }
        JsonNode rollbackFresh = mapper.readTree(
                send("GET", "/api/v1/properties/" + rollbackId, null).body());
        assertEquals(0, new BigDecimal("23000.00")
                .compareTo(rollbackFresh.path("monthlyRent").decimalValue()));
        assertEquals(rollbackOld,
                rollbackFresh.path("media").get(0).path("mediaUrl").asText());
    }

    @Test
    void h06GenuineGetHeadLengthsHeadersUnicodeAndRecovery() throws Exception {
        JdbcTemplate db = fixtures();
        long id = CONCURRENCY_ID;
        seedListing(db, id, "indore", "Indore", "OG Unicode ₹ café",
                "1 BHK", "18000", "PUBLISHED", "ACTIVE");
        assertHtmlRepresentation("GET", "/property/" + id, 200);
        assertHtmlRepresentation("GET", "/property/" + VISIBILITY_BASE + 999, 404);
        assertHtmlRepresentation("HEAD", "/property/" + VISIBILITY_BASE + 999, 404);
        assertHtmlRepresentation("GET", "/property/not-a-long", 404);

        OgV63Target.Target runner = OgV63Target.requireTarget(OgV63Target.RUNNER, true);
        try (Connection lock = DriverManager.getConnection(
                OgV63Target.URL, runner.role(), runner.password());
             Statement statement = lock.createStatement()) {
            lock.setAutoCommit(false);
            statement.execute("lock table public.listings in access exclusive mode");
            assertHtmlRepresentation("GET", "/property/" + id, 503);
            assertHtmlRepresentation("HEAD", "/property/" + id, 503);
            lock.rollback();
        }
        HttpResponse<String> recovered = assertHtmlRepresentation(
                "GET", "/property/" + id, 200);
        assertTrue(recovered.body().contains("₹"));
        assertTrue(recovered.headers().firstValue("Content-Length").orElseThrow()
                .matches("[0-9]+"));
        assertEquals(recovered.body().getBytes(StandardCharsets.UTF_8).length,
                Long.parseLong(recovered.headers().firstValue("Content-Length").orElseThrow()));
    }

    @Test
    void h07HostForwardedHeaderAttacksAndPrivacyCanaries() throws Exception {
        JdbcTemplate db = fixtures();
        long published = HEADERS_BASE + 1;
        long paused = HEADERS_BASE + 2;
        long archived = HEADERS_BASE + 3;
        long inactiveCity = HEADERS_BASE + 4;
        long missingCity = HEADERS_BASE + 5;
        seedListing(db, published, "bhopal", "Bhopal", "OG Hostile Headers",
                "2 BHK", "21000", "PUBLISHED", "ACTIVE");
        seedListing(db, paused, "indore", "Indore", "OG Paused Privacy",
                "2 BHK", "22000", "PAUSED", "ACTIVE");
        seedListing(db, archived, "indore", "Indore", "OG Archived Privacy",
                "2 BHK", "23000", "ARCHIVED", "ACTIVE");
        long hostileInactiveCity = seedCity(db, 9_710_003L, "og-hostile-inactive-city",
                "OG Hostile Inactive City", false);
        seedListing(db, inactiveCity, hostileInactiveCity, "OG Hostile Inactive City",
                "OG Hostile Inactive Sector", "1 BHK", "19000",
                "PUBLISHED", "ACTIVE");
        seedListing(db, missingCity, "indore", "Indore", "OG Hostile Missing City",
                "1 BHK", "18500", "PUBLISHED", "ACTIVE");
        db.update("update localities set supported_city_id=null where id=?",
                missingCity + 1_000_000L);
        Map<String,String> hostileHeaders = Map.of(
                "Forwarded", "host=evil.example;proto=http",
                "X-Forwarded-Host", "evil.example",
                "X-Forwarded-Proto", "http",
                "X-Forwarded-Port", "8080",
                "Origin", "https://evil.example",
                "Referer", "https://evil.example/private");
        HttpResponse<String> hostile = send(
                "GET", "/property/" + published, null, hostileHeaders);
        assertEquals(200, hostile.statusCode());
        assertEquals("https://pathome.test/property/" + published,
                OgV63HtmlMetadata.parse(hostile.body()).singleProperty("og:url"));
        assertFalse(hostile.body().contains("evil.example"));
        assertNoPrivateCanaries(hostile.body());

        RawResponse raw = sendRaw("/property/" + published, Map.of(
                "Host", "attacker.example",
                "X-Forwarded-Host", "attacker.example",
                "Forwarded", "host=attacker.example"));
        assertEquals(200, raw.status);
        assertEquals("https://pathome.test/property/" + published,
                OgV63HtmlMetadata.parse(raw.body).singleProperty("og:url"));
        assertFalse(raw.body.contains("attacker.example"));
        for (long hidden : List.of(paused, archived, inactiveCity, missingCity,
                HEADERS_BASE + 999)) {
            assertHidden(hidden);
            assertHostileHidden(hidden, hostileHeaders);
        }
        assertEquals(404, send("GET", "/api/v1/properties/" + missingCity, null,
                Map.of("Forwarded", "host=missing-city.attacker.example",
                        "X-Forwarded-Host", "missing-city.attacker.example")).statusCode());
        RawResponse inactiveRaw = sendRaw("/property/" + inactiveCity, Map.of(
                "Host", "inactive-city.attacker.example",
                "Forwarded", "host=inactive-city.attacker.example"));
        assertEquals(404, inactiveRaw.status);
        assertFalse(inactiveRaw.body.contains("OG Hostile Inactive City"));
        assertFalse(inactiveRaw.body.contains("OG Hostile Inactive Sector"));
        assertFalse(inactiveRaw.body.contains("og:title"));
        OgV63PrivacyOracle.requireClean(inactiveRaw.body);
        RawResponse missingRaw = sendRaw("/property/" + missingCity, Map.of(
                "Host", "missing-city.attacker.example",
                "X-Forwarded-Host", "missing-city.attacker.example"));
        assertEquals(404, missingRaw.status);
        assertFalse(missingRaw.body.contains("OG Hostile Missing City"));
        assertFalse(missingRaw.body.contains("OG Hostile Missing Sector"));
        assertFalse(missingRaw.body.contains("og:title"));
        OgV63PrivacyOracle.requireClean(missingRaw.body);
    }

    @Test
    void h08PreviewErrorRecoveryShutdownSideEffectSnapshotAndProviderDenial()
            throws Exception {
        JdbcTemplate db = fixtures();
        long id = SIDE_EFFECT_ID;
        seedListing(db, id, "indore", "Indore", "OG Side Effect",
                "2 BHK", "21000", "PUBLISHED", "ACTIVE");
        seedAsset(db, 9_280_001L, id, "IMAGE",
                "pathome/properties/images/og-v63-side-effect",
                CLOUD + "/image/upload/pathome/properties/images/og-v63-side-effect.webp",
                null, true, false, 0);
        Map<String,String> before = OgV63SideEffectObserver.snapshot();
        try {
            assertEquals(200, send("GET", "/property/" + id, null).statusCode());
            assertEquals(200, send("HEAD", "/property/" + id, null).statusCode());
            assertEquals(404, send("GET", "/property/" + (id + 99), null).statusCode());
            OgV63Target.Target runner =
                    OgV63Target.requireTarget(OgV63Target.RUNNER, true);
            try (Connection lock = DriverManager.getConnection(
                    OgV63Target.URL, runner.role(), runner.password());
                 Statement statement = lock.createStatement()) {
                lock.setAutoCommit(false);
                statement.execute("lock table public.listings in access exclusive mode");
                assertEquals(503, send("GET", "/property/" + id, null).statusCode());
                lock.rollback();
            }
            assertEquals(200, send("GET", "/property/" + id, null).statusCode());
            IllegalStateException denied = assertThrows(IllegalStateException.class,
                    () -> cloudinary.deleteResource("og-v63-provider-canary", false));
            assertTrue(denied.getMessage().contains("disabled"));
        } finally {
            Map<String,String> after = OgV63SideEffectObserver.snapshot();
            assertEquals(List.of(), OgV63SideEffectObserver.differences(before, after),
                    "public previews changed operational rows");
        }
    }

    @Test
    void g06RealRolePermissionsAndRollbackOnlyFixtureDml() throws Exception {
        OgV63Target.Target runtime = OgV63Target.requireTarget(OgV63Target.RUNTIME, true);
        OgV63Target.Target runner = OgV63Target.requireTarget(OgV63Target.RUNNER, true);
        OgV63Target.Target migrator = OgV63Target.requireTarget(OgV63Target.MIGRATOR, true);

        try (Connection connection = DriverManager.getConnection(
                OgV63Target.URL, runtime.role(), runtime.password())) {
            assertTrue(count(connection, "select count(*) from public.listings") >= 0);
            assertDenied(connection,
                    "insert into public.listings select * from public.listings limit 0");
            assertDenied(connection, "update public.listings set version=version");
            assertDenied(connection, "delete from public.listings");
            assertDenied(connection, "truncate public.listings");
            assertDenied(connection, "create schema og_v63_forbidden");
            assertDenied(connection, "create role og_v63_forbidden");
            assertDenied(connection, "create temp table og_v63_temp(id int)");
            assertDenied(connection, "select nextval('public.listings_id_seq')");
            assertDenied(connection, "select * from it_forbidden.probe");
            assertDenied(connection,
                    "update it_control.migration_receipt set receipt_sha256=receipt_sha256");
        }
        try (Connection connection = DriverManager.getConnection(
                OgV63Target.URL, runner.role(), runner.password())) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                assertEquals(1, statement.executeUpdate("""
                        insert into public.supported_cities
                            (id,code,display_name,active,version)
                        values (9799999,'og-v63-rollback-probe','Rollback Only',true,0)
                        """));
            } finally {
                connection.rollback();
            }
            connection.setAutoCommit(true);
            assertDenied(connection, "delete from public.listings");
            assertDenied(connection, "truncate public.property_media_assets");
            assertDenied(connection,
                    "insert into public.operational_audit_events default values");
            assertDenied(connection, "create temp table og_v63_runner_temp(id int)");
            assertDenied(connection,
                    "update it_control.run_identity set system_identifier=system_identifier");
        }
        try (Connection connection = DriverManager.getConnection(
                OgV63Target.URL, migrator.role(), migrator.password());
             Statement statement = connection.createStatement();
             var rows = statement.executeQuery("""
                     select has_database_privilege(current_user,current_database(),'TEMP')
                     """)) {
            assertTrue(rows.next());
            assertFalse(rows.getBoolean(1), "migrator TEMP must remain revoked");
        }
    }

    @Test
    void g07ReadOnlyCatalogContainmentAudit() throws Exception {
        OgV63Target.Target runtime = OgV63Target.requireTarget(OgV63Target.RUNTIME, true);
        try (Connection connection = DriverManager.getConnection(
                OgV63Target.URL, runtime.role(), runtime.password())) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            int functions = 0;
            try (Statement statement = connection.createStatement();
                 var rows = statement.executeQuery("""
                         select n.nspname,p.proname,pg_get_userbyid(p.proowner),p.prosecdef,
                                exists(select 1 from pg_depend d
                                    where d.classid='pg_proc'::regclass and d.objid=p.oid
                                      and d.deptype='e'),
                                p.prorettype='pg_catalog.trigger'::regtype,
                                has_function_privilege('public',p.oid,'EXECUTE'),
                                p.proconfig
                         from pg_proc p join pg_namespace n on n.oid=p.pronamespace
                         where n.nspname not in ('pg_catalog','information_schema')
                         order by n.nspname,p.proname
                         """)) {
                while (rows.next()) {
                    functions++;
                    OgV63CatalogAudit.requireContained(new OgV63CatalogAudit.FunctionFact(
                            rows.getString(1), rows.getString(2), rows.getString(3),
                            rows.getBoolean(4), rows.getBoolean(5), rows.getBoolean(6),
                            rows.getBoolean(7)));
                    Object[] functionSettings = rows.getArray(8) == null
                            ? new Object[0]
                            : (Object[]) rows.getArray(8).getArray();
                    assertTrue(functionSettings.length == 0,
                            "application function carries mutable search_path configuration");
                }
            }
            assertTrue(functions > 0);
            try (Statement statement = connection.createStatement();
                 var rows = statement.executeQuery("""
                         select n.nspname,rn.nspname
                         from pg_constraint c
                         join pg_namespace n on n.oid=c.connamespace
                         join pg_class r on r.oid=c.confrelid
                         join pg_namespace rn on rn.oid=r.relnamespace
                         where c.contype='f' and n.nspname='public'
                         """)) {
                while (rows.next()) {
                    OgV63CatalogAudit.requireDependency(rows.getString(1), rows.getString(2));
                }
            }
            try (Statement statement = connection.createStatement();
                 var rows = statement.executeQuery("""
                         select n.nspname,fn.nspname,pg_get_userbyid(p.proowner)
                         from pg_trigger t
                         join pg_class c on c.oid=t.tgrelid
                         join pg_namespace n on n.oid=c.relnamespace
                         join pg_proc p on p.oid=t.tgfoid
                         join pg_namespace fn on fn.oid=p.pronamespace
                         where not t.tgisinternal and n.nspname='public'
                         """)) {
                while (rows.next()) {
                    assertEquals("public", rows.getString(1));
                    assertEquals("public", rows.getString(2));
                    assertEquals(OgV63Target.MIGRATOR, rows.getString(3));
                }
            }
            try (Statement statement = connection.createStatement();
                 var rows = statement.executeQuery("""
                         select e.extname,n.nspname,e.extversion
                         from pg_extension e join pg_namespace n on n.oid=e.extnamespace
                         order by e.extname
                         """)) {
                List<String> extensions = new ArrayList<>();
                while (rows.next()) extensions.add(rows.getString(1) + "|" + rows.getString(2)
                        + "|" + rows.getString(3));
                assertEquals(List.of("btree_gist|public|1.6", "pg_trgm|public|1.6",
                        "plpgsql|pg_catalog|1.0"), extensions);
            }
            connection.rollback();
        }
    }

    @Test
    void g08StartupContainmentAndDisabledScheduledOrProviderBeans() {
        OgV63RuntimeConfiguration.validateSettings(context.getEnvironment()::getProperty);
        assertFalse(context.containsBeanDefinition(
                FreshIntegrationRuntimeConfiguration.SCHEDULED_PROCESSOR));
        assertFalse(context.containsBeanDefinition("dataInitializer"));
        assertFalse(context.containsBeanDefinition("searchLearningStartupConfig"));
        assertTrue(cloudinary instanceof FreshIntegrationProviderGuard);
        assertFalse("true".equalsIgnoreCase(context.getEnvironment().getProperty(
                "spring.flyway.enabled")));
        assertEquals("validate", context.getEnvironment().getProperty(
                "spring.jpa.hibernate.ddl-auto"));
        assertEquals("never", context.getEnvironment().getProperty(
                "spring.sql.init.mode"));
        assertEquals("false", context.getEnvironment().getProperty(
                "APP_FIRST_ADMIN_PROVISIONING_ENABLED"));
    }

    private JdbcTemplate fixtures() {
        OgV63Target.Target runner = OgV63Target.requireTarget(OgV63Target.RUNNER, true);
        DriverManagerDataSource source = new DriverManagerDataSource(
                OgV63Target.URL, runner.role(), runner.password());
        source.setDriverClassName("org.postgresql.Driver");
        return new JdbcTemplate(source);
    }

    private long seedCity(JdbcTemplate fixtures, long id, String code,
                          String displayName, boolean active) {
        fixtures.update("""
                insert into supported_cities
                    (id,code,display_name,active,version,created_at,updated_at)
                values (?,?,?,?,0,current_timestamp,current_timestamp)
                on conflict (id) do update set code=excluded.code,
                    display_name=excluded.display_name,active=excluded.active,version=0,
                    updated_at=current_timestamp
                """, id, code, displayName, active);
        return fixtures.queryForObject(
                "select id from supported_cities where code=?", Long.class, code);
    }

    private long seedListing(JdbcTemplate fixtures, long id, String cityCode,
                             String cityLabel, String sector, String bhk,
                             String rent, String workflow, String status) {
        Long cityId = fixtures.queryForObject(
                "select id from supported_cities where code=? and active=true",
                Long.class, cityCode);
        assertNotNull(cityId);
        return seedListing(fixtures, id, cityId, cityLabel, sector, bhk,
                rent, workflow, status);
    }

    private long seedListing(JdbcTemplate fixtures, long id, long cityId,
                             String cityLabel, String sector, String bhk,
                             String rent, String workflow, String status) {
        long localityId = id + 1_000_000L;
        fixtures.update("""
                insert into localities
                    (id,city,sector_name,aliases,demand_score,avg_rent_amount,supported_city_id)
                values (?,?,?,?,?,?,?)
                on conflict (id) do update set city=excluded.city,
                    sector_name=excluded.sector_name,supported_city_id=excluded.supported_city_id
                """, localityId, cityLabel, sector, "og-v63-fixture", 1, 1.0, cityId);
        fixtures.update("""
                insert into listings
                    (id,listing_category,title,description,address,city,sector,latitude,longitude,
                     listing_type,status,property_type,bhk_count,furnishing_status,amenities,
                     total_area_sq_ft,owner_phone_number,owner_name,bathroom_count,floor_number,
                     total_floors,origin_draft_id,canonical_locality_id,workflow_status,version,
                     submitted_at,published_at,last_content_published_at,title_provenance)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,current_timestamp,
                        current_timestamp,current_timestamp,?)
                on conflict (id) do update set listing_category=excluded.listing_category,
                    title=excluded.title,description=excluded.description,address=excluded.address,
                    city=excluded.city,sector=excluded.sector,status=excluded.status,
                    property_type=excluded.property_type,bhk_count=excluded.bhk_count,
                    total_area_sq_ft=excluded.total_area_sq_ft,
                    owner_phone_number=excluded.owner_phone_number,
                    owner_name=excluded.owner_name,canonical_locality_id=excluded.canonical_locality_id,
                    workflow_status=excluded.workflow_status,version=0,
                    submitted_at=current_timestamp,published_at=current_timestamp,
                    last_content_published_at=current_timestamp
                """, id, "RENTAL", "Private title canary", "Private description canary",
                "Private address canary", cityLabel, sector, null, null, "RENT", status,
                "FLAT", bhk, "UNFURNISHED", "Parking, Private amenity canary", 850.0,
                PRIVATE_CANARY, "Private owner canary", 2, 3, 8, "og-v63-" + id,
                localityId, workflow, 0L, "MANUAL");
        fixtures.update("""
                insert into rental_details
                    (id,monthly_rent,security_deposit,maintenance_charge,bachelor_allowed)
                values (?,?,?,?,true)
                on conflict (id) do update set monthly_rent=excluded.monthly_rent,
                    security_deposit=excluded.security_deposit,
                    maintenance_charge=excluded.maintenance_charge
                """, id, new BigDecimal(rent), new BigDecimal("60000"),
                new BigDecimal("2500"));
        return id;
    }

    private void seedAsset(JdbcTemplate fixtures, long id, long listingId,
                           String mediaType, String publicId, String url,
                           String uploadRequestId, boolean primary,
                           boolean retired, int sortOrder) {
        fixtures.update("""
                insert into property_media_assets
                    (id,listing_id,media_type,room_tag,caption,is_primary_cover,media_url,
                     cloudinary_public_id,upload_request_id,sort_order,retired_at,uploaded_at)
                values (?,?,?,'GENERAL','Synthetic projection asset',?,?,?,?,?,?,current_timestamp)
                on conflict (id) do update set listing_id=excluded.listing_id,
                    media_type=excluded.media_type,media_url=excluded.media_url,
                    cloudinary_public_id=excluded.cloudinary_public_id,
                    upload_request_id=excluded.upload_request_id,
                    is_primary_cover=excluded.is_primary_cover,
                    sort_order=excluded.sort_order,retired_at=excluded.retired_at
                """, id, listingId, mediaType, primary, url, publicId, uploadRequestId,
                sortOrder, retired ? java.sql.Timestamp.from(java.time.Instant.now()) : null);
    }

    private void assertLiteralProperty(long id, String sector, String city) throws Exception {
        HttpResponse<String> jsonResponse = send(
                "GET", "/api/v1/properties/" + id, null);
        assertEquals(200, jsonResponse.statusCode());
        JsonNode json = mapper.readTree(jsonResponse.body());
        assertEquals(sector, json.path("sector").asText());
        assertEquals(city, json.path("city").asText());
        HttpResponse<String> htmlResponse = assertHtmlRepresentation(
                "GET", "/property/" + id, 200);
        OgV63HtmlMetadata metadata = OgV63HtmlMetadata.parse(htmlResponse.body());
        String expectedTitle = (json.path("bhk").asText().startsWith("3")
                ? "3 BHK Flat in " : "2 BHK Flat in ") + sector + ", " + city;
        assertEquals(expectedTitle, metadata.singleProperty("og:title"));
        assertEquals(1, metadata.properties.get("og:title").size());
        if (city.contains("<script>") || sector.contains("<b>")) {
            assertFalse(htmlResponse.body().contains("$1\"><"),
                    "literal replacement text was not safely escaped");
        }
        assertFalse(htmlResponse.body().contains(PRIVATE_CANARY));
    }

    private void assertImage(long id, String expectedUrl) throws Exception {
        HttpResponse<String> jsonResponse = send(
                "GET", "/api/v1/properties/" + id, null);
        assertEquals(200, jsonResponse.statusCode());
        JsonNode json = mapper.readTree(jsonResponse.body());
        assertTrue(json.path("media").findValuesAsText("mediaUrl").contains(expectedUrl));
        HttpResponse<String> htmlResponse = send("GET", "/property/" + id, null);
        assertEquals(200, htmlResponse.statusCode());
        assertEquals(expectedUrl,
                OgV63HtmlMetadata.parse(htmlResponse.body()).singleProperty("og:image"));
    }

    private void assertNoImage(long id) throws Exception {
        HttpResponse<String> jsonResponse = send(
                "GET", "/api/v1/properties/" + id, null);
        assertEquals(200, jsonResponse.statusCode());
        HttpResponse<String> htmlResponse = send("GET", "/property/" + id, null);
        assertEquals(200, htmlResponse.statusCode());
        assertFalse(OgV63HtmlMetadata.parse(htmlResponse.body()).properties
                .containsKey("og:image"));
        assertFalse(htmlResponse.body().contains("private-cover.webp"));
        assertFalse(htmlResponse.body().contains("foreign.invalid"));
        assertFalse(htmlResponse.body().contains("retired"));
    }

    private void assertHidden(long id) throws Exception {
        assertHidden(id, "non-public property");
    }

    private void assertVisible(long id, String city, String sector) throws Exception {
        HttpResponse<String> jsonResponse =
                send("GET", "/api/v1/properties/" + id, null);
        assertEquals(200, jsonResponse.statusCode());
        JsonNode json = mapper.readTree(jsonResponse.body());
        assertEquals(city, json.path("city").asText());
        assertEquals(sector, json.path("sector").asText());
        assertNoPrivateCanaries(jsonResponse.body());
        HttpResponse<String> htmlResponse =
                assertHtmlRepresentation("GET", "/property/" + id, 200);
        OgV63HtmlMetadata metadata = OgV63HtmlMetadata.parse(htmlResponse.body());
        assertEquals(json.path("title").asText(), metadata.singleProperty("og:title"));
        assertEquals("https://pathome.test/property/" + id,
                metadata.singleProperty("og:url"));
        assertEquals(1, metadata.titles.size());
    }

    private void assertHidden(long id, String label) throws Exception {
        HttpResponse<String> html = assertHtmlRepresentation(
                "GET", "/property/" + id, 404);
        OgV63HtmlMetadata metadata = OgV63HtmlMetadata.parse(html.body());
        assertFalse(metadata.properties.containsKey("og:title"), label);
        assertFalse(metadata.properties.containsKey("og:description"), label);
        assertFalse(metadata.properties.containsKey("og:url"), label);
        assertFalse(metadata.properties.containsKey("og:image"), label);
        assertFalse(html.body().contains("Private title canary"), label);
        HttpResponse<String> json = send("GET", "/api/v1/properties/" + id, null);
        assertEquals(404, json.statusCode(), label + " must not expose JSON");
        assertEquals("", json.body(), label + " must not expose JSON facts");
        assertNoPrivateCanaries(json.body());
    }

    private void assertHiddenText(String id) throws Exception {
        HttpResponse<String> response = assertHtmlRepresentation(
                "GET", "/property/" + id, 404);
        OgV63HtmlMetadata metadata = OgV63HtmlMetadata.parse(response.body());
        assertEquals("noindex,nofollow", metadata.singleNamed("robots"));
        assertFalse(metadata.properties.containsKey("og:title"));
    }

    private void assertHostileHidden(long id, Map<String,String> hostileHeaders)
            throws Exception {
        HttpResponse<String> html = send(
                "GET", "/property/" + id, null, hostileHeaders);
        assertEquals(404, html.statusCode());
        assertHeader(html, "Cache-Control", "no-store");
        assertHeader(html, "X-Content-Type-Options", "nosniff");
        assertHeader(html, "Referrer-Policy", "no-referrer");
        OgV63HtmlMetadata metadata = OgV63HtmlMetadata.parse(html.body());
        assertEquals("noindex,nofollow", metadata.singleNamed("robots"));
        assertFalse(metadata.properties.containsKey("og:title"));
        assertFalse(metadata.properties.containsKey("og:image"));
        assertFalse(html.body().contains("attacker.example"));
        assertNoPrivateCanaries(html.body());

        HttpResponse<String> head = send(
                "HEAD", "/property/" + id, null, hostileHeaders);
        assertEquals(404, head.statusCode());
        assertEquals("", head.body());
        assertEquals(html.headers().firstValue("Content-Length"),
                head.headers().firstValue("Content-Length"));

        HttpResponse<String> json = send(
                "GET", "/api/v1/properties/" + id, null, hostileHeaders);
        assertEquals(404, json.statusCode());
        assertEquals("", json.body());
        assertNoPrivateCanaries(json.body());
    }

    private HttpResponse<String> assertHtmlRepresentation(
            String method, String path, int status) throws Exception {
        HttpResponse<String> get = send("GET", path, null);
        assertEquals(status, get.statusCode());
        assertHeader(get, "Cache-Control", "no-store");
        assertHeader(get, "X-Content-Type-Options", "nosniff");
        assertHeader(get, "Referrer-Policy", "no-referrer");
        MediaType type = MediaType.parseMediaType(
                get.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("text", type.getType());
        assertEquals("html", type.getSubtype());
        assertEquals(StandardCharsets.UTF_8, type.getCharset());
        long length = Long.parseLong(
                get.headers().firstValue("Content-Length").orElseThrow());
        assertEquals(get.body().getBytes(StandardCharsets.UTF_8).length, length);
        OgV63HtmlMetadata parsed = OgV63HtmlMetadata.parse(get.body());
        assertEquals(status != 200 ? "noindex,nofollow" : "index,follow",
                parsed.singleNamed("robots"));
        assertFalse(get.body().contains(PRIVATE_CANARY));
        assertFalse(get.body().contains("Private owner canary"));
        assertFalse(get.body().contains("Private description canary"));

        HttpResponse<String> head = send("HEAD", path, null);
        assertEquals(status, head.statusCode());
        assertEquals("", head.body());
        assertEquals(length, Long.parseLong(
                head.headers().firstValue("Content-Length").orElseThrow()));
        assertEquals(get.headers().firstValue("Content-Type"),
                head.headers().firstValue("Content-Type"));
        assertEquals(get.headers().firstValue("Cache-Control"),
                head.headers().firstValue("Cache-Control"));
        return get;
    }

    private static void assertHeader(HttpResponse<?> response, String name, String value) {
        assertEquals(value, response.headers().firstValue(name).orElseThrow(
                () -> new AssertionError("Missing header " + name)));
    }

    private void assertNoPrivateCanaries(String value) {
        OgV63PrivacyOracle.requireClean(value);
    }

    private void commitCoherentWriterChange(
            JdbcTemplate fixtures, long id, String rent, String mediaUrl,
            String publicId) {
        transaction(fixtures, () -> {
            fixtures.update("update rental_details set monthly_rent=? where id=?",
                    new BigDecimal(rent), id);
            fixtures.update("""
                    update property_media_assets set media_url=?,cloudinary_public_id=?
                    where listing_id=? and retired_at is null
                    """, mediaUrl, publicId, id);
        });
    }

    private void transaction(JdbcTemplate fixtures, Runnable action) {
        TransactionTemplate tx = new TransactionTemplate(
                new DataSourceTransactionManager(fixtures.getDataSource()));
        tx.executeWithoutResult(status -> action.run());
    }

    private void rollbackTransaction(JdbcTemplate fixtures, Runnable action) {
        TransactionTemplate tx = new TransactionTemplate(
                new DataSourceTransactionManager(fixtures.getDataSource()));
        tx.executeWithoutResult(status -> {
            action.run();
            status.setRollbackOnly();
        });
    }

    private static int count(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    private static void assertDenied(Connection connection, String sql) {
        SQLException failure;
        try (Statement statement = connection.createStatement()) {
            failure = assertThrows(SQLException.class,
                    () -> statement.execute(sql),
                    "Adverse statement unexpectedly succeeded: " + sql);
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not close denied statement", exception);
        }
        assertEquals("42501", failure.getSQLState(),
                "Unexpected SQLSTATE for: " + sql + " / " + failure.getMessage());
    }

    private HttpResponse<String> send(String method, String path, String body)
            throws Exception {
        return send(method, path, body, Map.of());
    }

    private HttpResponse<String> send(String method, String path, String body,
                                      Map<String,String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(12))
                .header("Accept", "text/html,application/json");
        headers.forEach(request::header);
        if (body == null) request.method(method, HttpRequest.BodyPublishers.noBody());
        else request.method(method, HttpRequest.BodyPublishers.ofString(body));
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private RawResponse sendRaw(String path, Map<String,String> headers) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(12_000);
            StringBuilder request = new StringBuilder("GET " + path + " HTTP/1.1\r\n");
            headers.forEach((name, value) ->
                    request.append(name).append(": ").append(value).append("\r\n"));
            request.append("Connection: close\r\n\r\n");
            socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            byte[] response = socket.getInputStream().readAllBytes();
            int split = indexOf(response, new byte[]{'\r','\n','\r','\n'});
            assertTrue(split > 0);
            String head = new String(response, 0, split, StandardCharsets.UTF_8);
            String body = new String(response, split + 4, response.length - split - 4,
                    StandardCharsets.UTF_8);
            String[] lines = head.split("\r\n");
            int status = Integer.parseInt(lines[0].split(" ")[1]);
            Map<String,String> parsedHeaders = new LinkedHashMap<>();
            for (int index = 1; index < lines.length; index++) {
                int colon = lines[index].indexOf(':');
                if (colon > 0) parsedHeaders.put(lines[index].substring(0, colon).toLowerCase(),
                        lines[index].substring(colon + 1).strip());
            }
            return new RawResponse(status, parsedHeaders, body);
        }
    }

    private static int indexOf(byte[] source, byte[] target) {
        outer:
        for (int index = 0; index <= source.length - target.length; index++) {
            for (int offset = 0; offset < target.length; offset++) {
                if (source[index + offset] != target[offset]) continue outer;
            }
            return index;
        }
        return -1;
    }

    private record RawResponse(int status, Map<String,String> headers, String body) {}
}
