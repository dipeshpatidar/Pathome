package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.lessor.LandlordDraftData;
import com.indore.pathome.spaces.entity.*;
import com.indore.pathome.spaces.repository.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/** Exercises the production submission transaction against an isolated PostgreSQL schema. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({LandlordCapabilityService.class, LessorProfileService.class,
        ListingWorkflowService.class, LandlordSubmissionService.class})
@EnabledIfEnvironmentVariable(named = "PATHOME_LESSOR_POSTGRES_TEST", matches = "true")
class LandlordSubmissionPostgresRollbackTest {
    private static final String BASE_URL = System.getenv().getOrDefault("SPRING_DATASOURCE_URL", "jdbc:postgresql://localhost:5432/pathome_db");
    private static final String USERNAME = System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", "pathome");
    private static final String PASSWORD = System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", "");
    private static final String SCHEMA = "lessor_rollback_test_" + UUID.randomUUID().toString().replace("-", "");
    private static final String SCHEMA_URL = BASE_URL + (BASE_URL.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        try (var connection = DriverManager.getConnection(BASE_URL, USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + SCHEMA);
        }
        registry.add("spring.datasource.url", () -> SCHEMA_URL);
        registry.add("spring.datasource.username", () -> USERNAME);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
    }

    @AfterAll
    static void dropSchema() throws Exception {
        try (var connection = DriverManager.getConnection(BASE_URL, USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    @Autowired private UserRepository users;
    @Autowired private PropertyUploadDraftRepository drafts;
    @Autowired private PropertyDraftMediaRepository media;
    @Autowired private LandlordSubmissionService submissions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockBean private LandlordDraftService draftData;
    @MockBean private LandlordLocationService locations;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void lateFailureRollsBackProfileListingDraftAndCapability() {
        // Hibernate creates the disposable schema; this production partial index is
        // required by LessorProfileRepository.insertIfNotExists's conflict target.
        assertEquals(SCHEMA, jdbc.queryForObject("SELECT current_schema()", String.class));
        jdbc.execute("DROP INDEX IF EXISTS idx_lessor_profiles_linked_user");
        jdbc.execute("CREATE UNIQUE INDEX idx_lessor_profiles_linked_user ON lessor_profiles (linked_user_id) WHERE linked_user_id IS NOT NULL");
        User owner = new User();
        owner.setEmail("rollback@example.test");
        owner.setRole(Role.ROLE_TENANT);
        owner.setFullName("Test Owner");
        owner.setPhoneNumber("+919876543210");
        owner = users.saveAndFlush(owner);
        long ownerId = owner.getId();
        String draftId = UUID.randomUUID().toString();
        PropertyUploadDraft draft = new PropertyUploadDraft();
        draft.setDraftId(draftId);
        draft.setLandlordUserId(ownerId);
        draft.setPayload("{}");
        drafts.saveAndFlush(draft);
        PropertyDraftMedia photo = new PropertyDraftMedia();
        photo.setDraftId(draftId);
        photo.setLandlordUserId(ownerId);
        photo.setMediaId(UUID.randomUUID().toString());
        photo.setOriginalFilename("cover.jpg");
        photo.setContentType("image/jpeg");
        photo.setCloudinaryUrl("https://example.test/cover.jpg");
        photo.setUploadStatus("UPLOADED");
        photo.setIsCover(true);
        photo.setSortOrder(0);
        media.saveAndFlush(photo);
        LandlordDraftData data = new LandlordDraftData(
                new LandlordDraftData.Basics(PropertyType.FLAT, RentalMode.LONG_TERM_RENTAL, "2BHK"),
                new LandlordDraftData.Pricing(new BigDecimal("20000"), BigDecimal.ZERO),
                new LandlordDraftData.Location("Indore", null, "Vijay Nagar", "10 Test Road", null,
                        LocationResolution.MANUAL_PENDING, null, null, null),
                new LandlordDraftData.Details(LocalDate.now(), "UNFURNISHED", 850.0, 2, 5, "Balcony", "Test home"));
        when(draftData.readData(org.mockito.ArgumentMatchers.any(PropertyUploadDraft.class))).thenReturn(data);

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        assertThrows(IllegalStateException.class, () -> transaction.execute(status -> {
            submissions.submit("rollback@example.test", draftId);
            assertEquals(1, count("SELECT count(*) FROM lessor_profiles WHERE linked_user_id = ?", ownerId));
            assertEquals(1, count("SELECT count(*) FROM listings WHERE owner_user_id = ?", ownerId));
            assertEquals(1, count("SELECT count(*) FROM users WHERE id = ? AND landlord_activated_at IS NOT NULL", ownerId));
            assertEquals(1, count("SELECT count(*) FROM property_upload_drafts WHERE draft_id = ? AND status = 'SUBMITTED'", draftId));
            throw new IllegalStateException("late failure after activation");
        }));
        assertEquals(0, count("SELECT count(*) FROM lessor_profiles WHERE linked_user_id = ?", ownerId));
        assertEquals(0, count("SELECT count(*) FROM listings WHERE owner_user_id = ?", ownerId));
        assertEquals(0, count("SELECT count(*) FROM users WHERE id = ? AND landlord_activated_at IS NOT NULL", ownerId));
        assertEquals(1, count("SELECT count(*) FROM property_upload_drafts WHERE draft_id = ? AND status = 'DRAFT'", draftId));
        assertEquals(Role.ROLE_TENANT, users.findById(ownerId).orElseThrow().getRole());
    }

    private int count(String sql, Object parameter) {
        return jdbc.queryForObject(sql, Integer.class, parameter);
    }
}
