package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "PATHOME_VISIT_OPERATIONS_POSTGRES_TEST", matches = "true")
class VisitOperationsQueuePostgresTest {
    private static final String BASE_URL = System.getenv().getOrDefault(
            "SPRING_DATASOURCE_URL", "jdbc:postgresql://localhost:5432/pathome_db");
    private static final String USERNAME = System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", "pathome");
    private static final String PASSWORD = System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", "");
    private static final String SCHEMA = "visit_queue_test_" + UUID.randomUUID().toString().replace("-", "");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        try (var connection = DriverManager.getConnection(BASE_URL, USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + SCHEMA);
        }
        String schemaUrl = BASE_URL + (BASE_URL.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA;
        registry.add("spring.datasource.url", () -> schemaUrl);
        registry.add("spring.datasource.username", () -> USERNAME);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @AfterAll
    static void dropSchema() throws Exception {
        try (var connection = DriverManager.getConnection(BASE_URL, USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    @Autowired private PropertyVisitRequestRepository requests;
    @Autowired private UserRepository users;
    @Autowired private ListingRepository listings;
    @Autowired private LocalityRepository localities;

    @Test
    void nativeOperationsQueueMapsFiltersCountsAndPagesOnPostgres() {
        Locality canonical = localities.saveAndFlush(new Locality("Canonical City", "Central", null, null, null));
        LocalDateTime sameCreatedAt = LocalDateTime.of(2026, 1, 2, 10, 0);
        PropertyVisitRequest receivedOne = request("One", VisitRequestStatus.RECEIVED, sameCreatedAt,
                listing("Direct city", null));
        receivedOne.getListing().setCanonicalLocalityId(canonical.getId());
        receivedOne.getListing().setCity("Legacy city");
        receivedOne.setListing(listings.saveAndFlush(receivedOne.getListing()));
        requests.saveAndFlush(receivedOne);

        PropertyVisitRequest receivedTwo = request("Two", VisitRequestStatus.RECEIVED, sameCreatedAt,
                listing("Other city", null));
        receivedTwo = persist(receivedTwo);
        PropertyVisitRequest coordinating = persist(request("Three", VisitRequestStatus.COORDINATING,
                sameCreatedAt.plusDays(1), listing("Other city", null)));
        PropertyVisitRequest unavailable = persist(request("Four", VisitRequestStatus.UNAVAILABLE,
                sameCreatedAt.plusDays(2), listing("Other city", null)));

        Page<PropertyVisitRequestRepository.OperationsQueueRow> unfiltered = requests.findOperationsQueue(
                null, null, PageRequest.of(0, 2));
        assertEquals(4, unfiltered.getTotalElements());
        assertEquals(2, unfiltered.getTotalPages());
        assertEquals(receivedTwo.getId(), unfiltered.getContent().get(0).getId());
        assertEquals(receivedOne.getId(), unfiltered.getContent().get(1).getId());
        PropertyVisitRequestRepository.OperationsQueueRow mapped = unfiltered.getContent().get(1);
        assertEquals(receivedOne.getId(), mapped.getId());
        assertEquals("RECEIVED", mapped.getStatus());
        assertEquals(receivedOne.getVersion(), mapped.getVersion());
        assertEquals(sameCreatedAt, mapped.getCreatedAt());
        assertEquals(receivedOne.getListing().getId(), mapped.getListingId());
        assertEquals("Direct city", mapped.getListingTitle());
        assertEquals("Canonical City", mapped.getCity());
        assertEquals("Test sector", mapped.getSector());

        Page<PropertyVisitRequestRepository.OperationsQueueRow> nextPage = requests.findOperationsQueue(
                null, null, PageRequest.of(1, 2));
        assertEquals(coordinating.getId(), nextPage.getContent().get(0).getId());
        assertEquals(unavailable.getId(), nextPage.getContent().get(1).getId());

        Page<PropertyVisitRequestRepository.OperationsQueueRow> byStatus = requests.findOperationsQueue(
                "RECEIVED", null, PageRequest.of(0, 10));
        assertEquals(2, byStatus.getTotalElements());
        assertTrue(byStatus.getContent().stream().allMatch(row -> "RECEIVED".equals(row.getStatus())));

        Page<PropertyVisitRequestRepository.OperationsQueueRow> byCanonicalCity = requests.findOperationsQueue(
                null, " canonical city ", PageRequest.of(0, 10));
        assertEquals(1, byCanonicalCity.getTotalElements());
        assertEquals(receivedOne.getId(), byCanonicalCity.getContent().get(0).getId());

        Page<PropertyVisitRequestRepository.OperationsQueueRow> nullFilters = requests.findOperationsQueue(
                null, null, PageRequest.of(0, 10));
        assertEquals(4, nullFilters.getTotalElements());
        assertEquals("Direct city", nullFilters.getContent().get(1).getListingTitle());
    }

    private PropertyVisitRequest persist(PropertyVisitRequest request) {
        Listing savedListing = listings.saveAndFlush((Listing) request.getListing());
        request.setListing(savedListing);
        return requests.saveAndFlush(request);
    }

    private PropertyVisitRequest request(String suffix, VisitRequestStatus status, LocalDateTime createdAt,
                                         RentalDetails listing) {
        User tenant = new User();
        tenant.setEmail("queue-" + suffix.toLowerCase() + "@example.test");
        tenant.setRole(Role.ROLE_TENANT);
        tenant.setFullName("Queue " + suffix);
        tenant = users.saveAndFlush(tenant);
        PropertyVisitRequest request = new PropertyVisitRequest();
        request.setTenant(tenant);
        request.setListing(listing);
        request.setStatus(status);
        request.setVersion(null);
        request.setCreatedAt(createdAt);
        return request;
    }

    private RentalDetails listing(String suffix, Long localityId) {
        RentalDetails listing = new RentalDetails();
        listing.setTitle(suffix);
        listing.setStatus(ListingStatus.ACTIVE);
        listing.setPropertyType(PropertyType.FLAT);
        listing.setAddress("Test address " + suffix);
        listing.setSector("Test sector");
        listing.setCity("Other city");
        listing.setCanonicalLocalityId(localityId);
        listing.setBhkCount("1BHK");
        listing.setMonthlyRent(BigDecimal.ONE);
        listing.setSecurityDeposit(BigDecimal.ZERO);
        return listing;
    }
}
