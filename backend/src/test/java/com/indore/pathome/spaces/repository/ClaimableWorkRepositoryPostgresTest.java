package com.indore.pathome.spaces.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.dto.ClaimableWorkItem;
import com.indore.pathome.spaces.dto.ClaimableWorkTargetType;
import com.indore.pathome.spaces.entity.*;
import com.indore.pathome.spaces.service.ClaimableWorkService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "PATHOME_VISIT_OPERATIONS_POSTGRES_TEST", matches = "true")
class ClaimableWorkRepositoryPostgresTest {
    private static final String BASE_URL = System.getenv().getOrDefault(
            "SPRING_DATASOURCE_URL", "jdbc:postgresql://localhost:5432/pathome_db");
    private static final String USERNAME = System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", "pathome");
    private static final String PASSWORD = System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", "");
    private static final String SCHEMA = "claimable_work_test_" + UUID.randomUUID().toString().replace("-", "");

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
        registry.add("spring.jpa.show-sql", () -> "false");
    }

    @AfterAll
    static void dropSchema() throws Exception {
        try (var connection = DriverManager.getConnection(BASE_URL, USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    @Autowired private UserRepository users;
    @Autowired private EmployeeProfileRepository employees;
    @Autowired private StaffAccessGrantRepository grants;
    @Autowired private SupportedCityRepository cities;
    @Autowired private OperatingTeamRepository teams;
    @Autowired private ListingRepository listings;
    @Autowired private PropertyVisitRequestRepository requests;
    @Autowired private VisitSessionRepository sessions;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void singleSnapshotScopesAndPagesCanonicalTargetsWithPrivacySafeCompleteVersions() throws Exception {
        var service = service();
        User coordinator = user("coordinator", Role.ROLE_TENANT);
        activate(coordinator);
        SupportedCity cityA = city("north");
        SupportedCity cityB = city("south");
        OperatingTeam teamA = team(cityA, "north-one");
        OperatingTeam sameCityOtherTeam = team(cityA, "north-two");
        OperatingTeam otherCityTeam = team(cityB, "south-one");
        grant(coordinator, StaffCapability.OPS_COORDINATE, StaffScopeType.TEAM, null, teamA,
                Instant.now().minusSeconds(120), null);
        grant(coordinator, StaffCapability.OPS_COORDINATE, StaffScopeType.TEAM, null, teamA,
                Instant.now().minusSeconds(60), null);

        PropertyVisitRequest unlinked = scopedRequest("claimable-unlinked", cityA, teamA, null, null,
                VisitRequestStatus.RECEIVED, true);
        scopedRequest("same-city-other-team", cityA, sameCityOtherTeam, null, null,
                VisitRequestStatus.RECEIVED, true);
        scopedRequest("other-city", cityB, otherCityTeam, null, null, VisitRequestStatus.RECEIVED, true);

        VisitSession linked = session("claimable-linked", cityA, teamA);
        PropertyVisitRequest linkedSecond = scopedRequest("linked-second", cityA, teamA, linked, null,
                VisitRequestStatus.CANCELLED, true);
        PropertyVisitRequest linkedFirst = scopedRequest("linked-first", cityA, teamA, linked, null,
                VisitRequestStatus.SCHEDULED, true);
        VisitSession zeroLinked = session("claimable-zero-linked", cityA, teamA);

        var page0 = service.list(coordinator.getId(), 0, 2);
        var page1 = service.list(coordinator.getId(), 1, 2);
        assertEquals(3, page0.totalCount());
        assertEquals(2, page0.items().size());
        assertEquals(2, page0.totalPages());
        assertEquals(1, page1.items().size());
        assertEquals(unlinked.getId(), page0.items().get(0).targetId());
        assertEquals(ClaimableWorkTargetType.REQUEST, page0.items().get(0).targetType());
        assertEquals(linked.getId(), page0.items().get(1).targetId());
        assertEquals(ClaimableWorkTargetType.SESSION, page0.items().get(1).targetType());
        assertEquals(zeroLinked.getId(), page1.items().get(0).targetId());
        assertEquals(0, service.list(coordinator.getId(), 5, 2).items().size());
        assertEquals(3, service.list(coordinator.getId(), 5, 2).totalCount());

        ClaimableWorkItem linkedItem = page0.items().get(1);
        assertEquals(sessions.findById(linked.getId()).orElseThrow().getVersion(), linkedItem.expectedVersion());
        assertEquals(2, linkedItem.linkedRequestVersions().size(), "child lifecycle must not filter concurrency tokens");
        assertEquals(linkedSecond.getId(), linkedItem.linkedRequestVersions().get(0).requestId());
        assertEquals(linkedFirst.getId(), linkedItem.linkedRequestVersions().get(1).requestId());
        assertEquals(linkedSecond.getVersion(), linkedItem.linkedRequestVersions().get(0).version());
        assertEquals(linkedFirst.getVersion(), linkedItem.linkedRequestVersions().get(1).version());
        assertTrue(page1.items().get(0).linkedRequestVersions().isEmpty());
        assertTrue(page0.items().get(0).linkedRequestVersions().isEmpty());

        assertEquals(linkedItem, service.refresh(coordinator.getId(), ClaimableWorkTargetType.SESSION, linked.getId()));
        assertThrows(EntityNotFoundException.class,
                () -> service.refresh(coordinator.getId(), ClaimableWorkTargetType.REQUEST, linkedFirst.getId()));
        assertThrows(EntityNotFoundException.class,
                () -> service.refresh(coordinator.getId(), ClaimableWorkTargetType.REQUEST, 9_999_999L));

        JsonNode json = new ObjectMapper().valueToTree(linkedItem);
        assertEquals(Set.of("targetType", "targetId", "expectedVersion", "cityId", "cityDisplayName",
                        "teamId", "teamDisplayName", "status", "linkedRequestVersions"), fieldNames(json));
        assertEquals(Set.of("requestId", "version"), fieldNames(json.path("linkedRequestVersions").get(0)));
        String serialized = json.toString();
        assertFalse(serialized.toLowerCase().contains("tenant"));
        assertFalse(serialized.toLowerCase().contains("property"));
        assertFalse(serialized.toLowerCase().contains("lessor"));
        assertFalse(serialized.toLowerCase().contains("note"));
        assertFalse(json.has("tenant"));
        assertFalse(json.has("property"));
        assertFalse(json.has("lessor"));
        assertFalse(json.has("ge"));
    }

    @Test
    void exactCurrentCoordinatorGrantIsRequiredAndInactiveScopesAreNotEligible() {
        var service = service();
        SupportedCity city = city("authority");
        OperatingTeam team = team(city, "authority-team");
        User active = user("active-no-grant", Role.ROLE_TENANT);
        activate(active);
        User supervisor = user("supervisor-only", Role.ROLE_TENANT);
        activate(supervisor);
        grant(supervisor, StaffCapability.OPS_SUPERVISE, StaffScopeType.TEAM, null, team,
                Instant.now().minusSeconds(60), null);
        User intake = user("intake-only", Role.ROLE_TENANT);
        activate(intake);
        grant(intake, StaffCapability.OPS_INTAKE, StaffScopeType.CITY, city, null,
                Instant.now().minusSeconds(60), null);
        User cityAdmin = user("city-admin-only", Role.ROLE_ADMIN);
        activate(cityAdmin);
        grant(cityAdmin, StaffCapability.CITY_TEAM_ADMIN, StaffScopeType.CITY, city, null,
                Instant.now().minusSeconds(60), null);
        User globalAdmin = user("staff-admin-only", Role.ROLE_ADMIN);
        activate(globalAdmin);
        grant(globalAdmin, StaffCapability.STAFF_ADMIN, StaffScopeType.GLOBAL, null, null,
                Instant.now().minusSeconds(60), null);
        User customer = user("customer", Role.ROLE_TENANT);

        for (User denied : new User[]{active, supervisor, intake, cityAdmin, globalAdmin, customer}) {
            assertThrows(AccessDeniedException.class, () -> service.list(denied.getId(), 0, 20));
        }

        User future = user("future-grant", Role.ROLE_TENANT);
        activate(future);
        grant(future, StaffCapability.OPS_COORDINATE, StaffScopeType.TEAM, null, team,
                Instant.now().plusSeconds(300), null);
        User expired = user("expired-grant", Role.ROLE_TENANT);
        activate(expired);
        Instant expiredAt = Instant.now().minusSeconds(120);
        grant(expired, StaffCapability.OPS_COORDINATE, StaffScopeType.TEAM, null, team,
                expiredAt.minusSeconds(60), expiredAt);
        User revoked = user("revoked-grant", Role.ROLE_TENANT);
        activate(revoked);
        StaffAccessGrant revokedGrant = grant(revoked, StaffCapability.OPS_COORDINATE,
                StaffScopeType.TEAM, null, team, Instant.now().minusSeconds(60), null);
        revokedGrant.revoke(globalAdmin, Instant.now(), "TEST_REVOKED");
        grants.saveAndFlush(revokedGrant);
        User inactiveProfile = user("inactive-profile", Role.ROLE_TENANT);
        grant(inactiveProfile, StaffCapability.OPS_COORDINATE, StaffScopeType.TEAM, null, team,
                Instant.now().minusSeconds(60), null);
        for (User denied : new User[]{future, expired, revoked, inactiveProfile}) {
            assertThrows(AccessDeniedException.class, () -> service.list(denied.getId(), 0, 20));
        }

        User coordinator = user("active-coordinator", Role.ROLE_TENANT);
        activate(coordinator);
        grant(coordinator, StaffCapability.OPS_COORDINATE, StaffScopeType.TEAM, null, team,
                Instant.now().minusSeconds(60), null);
        PropertyVisitRequest inactiveTeamTarget = scopedRequest("inactive-team-target", city, team, null, null,
                VisitRequestStatus.RECEIVED, true);
        team.setActive(false);
        teams.saveAndFlush(team);
        assertThrows(AccessDeniedException.class, () -> service.list(coordinator.getId(), 0, 20));
        assertThrows(AccessDeniedException.class,
                () -> service.refresh(coordinator.getId(), ClaimableWorkTargetType.REQUEST, inactiveTeamTarget.getId()));

        team.setActive(true);
        teams.saveAndFlush(team);
        city.setActive(false);
        cities.saveAndFlush(city);
        assertThrows(AccessDeniedException.class, () -> service.list(coordinator.getId(), 0, 20));
        assertThrows(AccessDeniedException.class,
                () -> service.refresh(coordinator.getId(), ClaimableWorkTargetType.REQUEST, inactiveTeamTarget.getId()));
    }

    @Test
    void activeScopePreservesEmptyPageAndHidesTargetsInAnotherInactiveScope() {
        var service = service();
        User coordinator = user("mixed-scope-coordinator", Role.ROLE_TENANT);
        activate(coordinator);
        SupportedCity activeCity = city("mixed-active");
        OperatingTeam activeTeam = team(activeCity, "mixed-active");
        OperatingTeam inactiveTeam = team(activeCity, "mixed-inactive");
        grant(coordinator, StaffCapability.OPS_COORDINATE, StaffScopeType.TEAM, null, activeTeam,
                Instant.now().minusSeconds(60), null);
        grant(coordinator, StaffCapability.OPS_COORDINATE, StaffScopeType.TEAM, null, inactiveTeam,
                Instant.now().minusSeconds(60), null);
        PropertyVisitRequest inactiveTeamTarget = scopedRequest("inactive-team-target-mixed", activeCity, inactiveTeam,
                null, null, VisitRequestStatus.RECEIVED, true);
        inactiveTeam.setActive(false);
        teams.saveAndFlush(inactiveTeam);

        var page = service.list(coordinator.getId(), 0, 20);
        assertEquals(0, page.totalCount());
        assertTrue(page.items().isEmpty());
        assertThrows(EntityNotFoundException.class,
                () -> service.refresh(coordinator.getId(), ClaimableWorkTargetType.REQUEST, inactiveTeamTarget.getId()));
    }

    @Test
    void requestLifecycleMatchesClaimEligibilityAndPaginationInputsAreBounded() {
        var service = service();
        User coordinator = user("status-coordinator", Role.ROLE_TENANT);
        activate(coordinator);
        SupportedCity city = city("status");
        OperatingTeam team = team(city, "status-team");
        grant(coordinator, StaffCapability.OPS_COORDINATE, StaffScopeType.TEAM, null, team,
                Instant.now().minusSeconds(60), null);

        for (VisitRequestStatus status : VisitRequestStatus.values()) {
            scopedRequest("status-" + status.name().toLowerCase(), city, team, null, null, status, true);
        }
        for (VisitSessionStatus status : VisitSessionStatus.values()) {
            VisitSession session = session("session-status-" + status.name().toLowerCase(), city, team);
            jdbc.update("UPDATE visit_sessions SET status = ? WHERE id = ?", status.name(), session.getId());
        }
        var page = service.list(coordinator.getId(), 0, 20);
        assertEquals(9, page.totalCount());
        assertEquals(Set.of("RECEIVED", "COORDINATING", "SCHEDULED"),
                page.items().stream().filter(item -> item.targetType() == ClaimableWorkTargetType.REQUEST)
                        .map(ClaimableWorkItem::status).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of("DRAFT", "SCHEDULED", "STARTED", "PROVISIONAL_NO_SHOW", "REPAIR_REQUIRED", "INTERRUPTED"),
                page.items().stream().filter(item -> item.targetType() == ClaimableWorkTargetType.SESSION)
                        .map(ClaimableWorkItem::status).collect(java.util.stream.Collectors.toSet()));
        assertThrows(IllegalArgumentException.class, () -> service.list(coordinator.getId(), -1, 20));
        assertThrows(IllegalArgumentException.class, () -> service.list(coordinator.getId(), 0, 0));
        assertThrows(IllegalArgumentException.class, () -> service.list(coordinator.getId(), 0, 101));
    }

    private ClaimableWorkService service() {
        var repository = new ClaimableWorkRepository(new NamedParameterJdbcTemplate(jdbc.getDataSource()), new ObjectMapper());
        return new ClaimableWorkService(repository);
    }

    private Set<String> fieldNames(JsonNode node) {
        var fields = new java.util.HashSet<String>();
        node.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    private User user(String suffix, Role role) {
        User user = new User();
        user.setEmail("claimable-" + suffix + "-" + UUID.randomUUID() + "@example.test");
        user.setFullName(suffix);
        user.setRole(role);
        return users.saveAndFlush(user);
    }

    private void activate(User user) {
        EmployeeProfile profile = new EmployeeProfile(user, "OPERATIONS", null, null);
        profile.setStaffActive(true);
        employees.saveAndFlush(profile);
    }

    private SupportedCity city(String suffix) {
        return cities.saveAndFlush(new SupportedCity("claimable-" + suffix + "-"
                + UUID.randomUUID().toString().substring(0, 8), "City " + suffix, true));
    }

    private OperatingTeam team(SupportedCity city, String suffix) {
        return teams.saveAndFlush(new OperatingTeam(city,
                "claimable-" + suffix + "-" + UUID.randomUUID().toString().substring(0, 8),
                "Team " + suffix, true));
    }

    private StaffAccessGrant grant(User user, StaffCapability capability, StaffScopeType scope,
            SupportedCity city, OperatingTeam team, Instant effectiveAt, Instant expiresAt) {
        return grants.saveAndFlush(new StaffAccessGrant(user, capability, scope, city, team, effectiveAt, expiresAt,
                null, Instant.now(), "TEST_CLAIMABLE", StaffGrantProvisioningSource.ADMIN_API));
    }

    private VisitSession session(String suffix, SupportedCity city, OperatingTeam team) {
        User tenant = user("tenant-" + suffix, Role.ROLE_TENANT);
        VisitSession session = new VisitSession();
        session.setTenant(tenant);
        session.setCity(city.getDisplayName());
        session.setSupportedCity(city);
        session.setOperatingTeam(team);
        session.setOperationalScopeReady(true);
        return sessions.saveAndFlush(session);
    }

    private PropertyVisitRequest scopedRequest(String suffix, SupportedCity city, OperatingTeam team,
            VisitSession session, User coordinator, VisitRequestStatus status, boolean ready) {
        User tenant = user("request-tenant-" + suffix, Role.ROLE_TENANT);
        RentalDetails listing = new RentalDetails();
        listing.setTitle("Private listing title " + suffix);
        listing.setAddress("Private address " + suffix);
        listing.setCity(city.getDisplayName());
        listing.setSector("Private sector");
        listing.setStatus(ListingStatus.ACTIVE);
        listing.setPropertyType(PropertyType.FLAT);
        listing.setBhkCount("1BHK");
        listing.setMonthlyRent(BigDecimal.ONE);
        listing.setSecurityDeposit(BigDecimal.ZERO);
        listing = (RentalDetails) listings.saveAndFlush(listing);

        PropertyVisitRequest request = new PropertyVisitRequest();
        request.setTenant(tenant);
        request.setListing(listing);
        request.setStatus(status);
        request.setCreatedAt(LocalDateTime.now());
        request.setSupportedCity(city);
        request.setOperatingTeam(team);
        request.setCoordinator(coordinator);
        request.setOperationalScopeReady(ready);
        request.setSession(session);
        return requests.saveAndFlush(request);
    }
}
