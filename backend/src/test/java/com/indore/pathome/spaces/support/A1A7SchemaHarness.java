package com.indore.pathome.spaces.support;

import org.flywaydb.core.Flyway;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.ResultSet;
/** Isolated schema migration harness for the owner-approved V1-to-V63 N2/V63 gate. */
public final class A1A7SchemaHarness {
    public static final String V61_SHA256 =
            "b1f13eb476d5dccd7671acf16983bb607d162a2f343610612e4f6ec014bf016f";
    public static final String V62_SHA256 =
            "cd64c50a592dc7f82abdee746b805d15c27ce148c860bdccab70293cafc36a45";
    public static final String V63_SHA256 =
            "0470ffe80655e9693a18fe6aa8aea8e2ee0c3948827cfd337073149087c301e8";

    private A1A7SchemaHarness() {}

    public static void migrate(A1A7IsolatedTarget.Target migrator, int ceiling) {
        if (!A1A7IsolatedTarget.MIGRATOR_ROLE.equals(migrator.role())) {
            throw refuse("only the approved migrator may create or migrate a scenario schema");
        }
        if (ceiling < 2 || ceiling > 63) {
            throw refuse("migration ceiling is outside V2-V63");
        }
        verifyApprovedSources();
        try (Connection connection = migrator.open()) {
            prepareSchema(connection, migrator.schema());
            setSearchPath(connection, migrator.schema());
            if (!hasHistory(connection, migrator.schema())) {
                ScriptUtils.executeSqlScript(connection,
                        new ClassPathResource("db/baseline/pathome-v1-legacy-core.sql"));
            }
        } catch (Exception exception) {
            throw refuse("legacy baseline or scenario schema preparation failed", exception);
        }
        Flyway flyway = Flyway.configure()
                .dataSource(migrator.jdbcUrl(), migrator.role(), migrator.password())
                .schemas(migrator.schema())
                .defaultSchema(migrator.schema())
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .initSql("SET search_path TO " + migrator.schema()
                        + ", it_extensions, pg_catalog")
                .target(String.valueOf(ceiling))
                .load();
        flyway.migrate();
        flyway.validate();
        verifyLedger(migrator, ceiling);
        grantScenarioDml(migrator);
    }

    public static void verifyComplete(A1A7IsolatedTarget.Target migrator) {
        verifyLedger(migrator, 63);
        try (Connection connection = migrator.open()) {
            setSearchPath(connection, migrator.schema());
            require(relationExists(connection, migrator.schema() + ".lead_phone_challenges"),
                    "phone challenge table");
            require(relationExists(connection, migrator.schema()
                    + ".lead_phone_delivery_outbox"), "phone delivery outbox");
            require(relationExists(connection, migrator.schema()
                    + ".lead_lessor_availability_enquiries"), "availability enquiries");
            require(relationExists(connection, migrator.schema()
                    + ".lead_visit_proposals"), "visit proposals");
            require(relationExists(connection, migrator.schema()
                    + ".lead_assistance_command_receipts"), "assistance receipts");
            require(relationExists(connection, migrator.schema()
                    + ".idx_lead_phone_outbox_due"), "delivery outbox index");
            require(relationExists(connection, migrator.schema()
                    + ".idx_lead_availability_property_window"), "availability index");
            require(relationExists(connection, migrator.schema()
                    + ".uk_lead_phone_one_pending_generation"), "pending generation index");
            require(constraintExists(connection, migrator.schema(),
                    "uk_lead_visit_link_lead_user_listing"),
                    "per-property association constraint");
        } catch (Exception exception) {
            throw refuse("V61 schema verification failed", exception);
        }
    }

    public static void seedUpgrade52(A1A7IsolatedTarget.Target migrator) {
        if (!A1A7IsolatedTarget.MIGRATOR_ROLE.equals(migrator.role())) {
            throw refuse("upgrade fixture requires the approved migrator");
        }
        try (Connection connection = migrator.open()) {
            setSearchPath(connection, migrator.schema());
            if (scalarLong(connection,
                    "select count(*) from lead_source_events "
                            + "where external_event_id='source-upgrade'") != 0) {
                return;
            }
            try (var statement = connection.createStatement()) {
                statement.execute("""
                        insert into users(email,full_name,phone_number,role)
                        values ('a1a7-upgrade-fixture@example.test','Synthetic Upgrade',
                                '+919700000001','ROLE_TENANT')
                        """);
                statement.execute("""
                        insert into lead_routing_queue(
                            tenant_name,phone_number,contact_phone_normalized,status,intent,
                            legacy_provenance,currency)
                        values ('Synthetic Rahul Upgrade','+919700000001','+919700000001',
                                'NEW','RENT','VERIFIED_MANUAL','INR')
                        """);
                statement.execute("""
                        insert into lead_source_events(
                            id,provider,provider_account_id,external_event_id,event_kind,
                            payload_hash,lead_id,processing_state,available_at,received_at,updated_at)
                        select gen_random_uuid(),'MANUAL','a1a7-upgrade','source-upgrade','CALL',
                               repeat('a',64),id,'PROCESSED',clock_timestamp(),
                               clock_timestamp(),clock_timestamp()
                        from lead_routing_queue
                        where phone_number='+919700000001'
                        """);
            }
        } catch (Exception exception) {
            throw refuse("V52 synthetic upgrade fixture failed", exception);
        }
    }

    public static void seedUpgrade61(A1A7IsolatedTarget.Target migrator) {
        if (!A1A7IsolatedTarget.MIGRATOR_ROLE.equals(migrator.role())) {
            throw refuse("V61 upgrade fixture requires the approved migrator");
        }
        try (Connection connection = migrator.open()) {
            setSearchPath(connection, migrator.schema());
            if (scalarLong(connection,
                    "select count(*) from lead_visit_links "
                            + "where id='44444444-4444-4444-8444-444444444444'") != 0) {
                return;
            }
            try (var statement = connection.createStatement()) {
                statement.execute("""
                        insert into supported_cities(code,display_name,active)
                        values ('v62-upgrade','Synthetic Upgrade City',true)
                        """);
                statement.execute("""
                        insert into operating_teams(city_id,code,display_name,active)
                        select id,'v62-upgrade-team','Synthetic Upgrade Team',true
                        from supported_cities where code='v62-upgrade'
                        """);
                statement.execute("""
                        insert into localities(city,sector_name,supported_city_id)
                        select 'Synthetic Upgrade City','Synthetic Upgrade Sector',id
                        from supported_cities where code='v62-upgrade'
                        """);
                statement.execute("""
                        update lead_routing_queue
                        set supported_city_id=(select id from supported_cities where code='v62-upgrade'),
                            operating_team_id=(select id from operating_teams where code='v62-upgrade-team'),
                            owner_user_id=(select id from users where email='a1a7-upgrade-fixture@example.test')
                        where phone_number='+919700000001'
                        """);
                statement.execute("""
                        insert into users(email,full_name,phone_number,role)
                        values ('a1a7-upgrade-lessor@example.test','Synthetic Upgrade Lessor',
                                '+919700000099','ROLE_LANDLORD')
                        """);
                statement.execute("""
                        insert into lessor_profiles(linked_user_id,display_name,mobile_number,email,
                            source_type,created_at,updated_at)
                        select id,'Synthetic Upgrade Lessor','+919700000099','a1a7-upgrade-lessor@example.test',
                               'CRM',clock_timestamp(),clock_timestamp()
                        from users where email='a1a7-upgrade-lessor@example.test'
                        """);
                statement.execute("""
                        insert into listings(title,description,address,city,sector,latitude,longitude,
                            listing_type,status,property_type,bhk_count,owner_phone_number,owner_name,
                            workflow_status,canonical_locality_id,lessor_profile_id,owner_user_id,
                            location_resolution,version,published_at)
                        select 'Synthetic Upgrade Property','Synthetic upgrade fixture',
                               'Synthetic Upgrade Address','Synthetic Upgrade City',
                               'Synthetic Upgrade Sector',0,0,'RENT','ACTIVE','FLAT','2 BHK',
                               '+919700000099','Synthetic Upgrade Lessor','PUBLISHED',
                               l.id,lp.id,lp.linked_user_id,'CANONICAL',0,clock_timestamp()
                        from localities l
                        join lessor_profiles lp on lp.email='a1a7-upgrade-lessor@example.test'
                        where l.city='Synthetic Upgrade City' and l.sector_name='Synthetic Upgrade Sector'
                        """);
                statement.execute("""
                        insert into rental_details(id,monthly_rent,security_deposit,bachelor_allowed)
                        select id,18000,36000,true from listings
                        where title='Synthetic Upgrade Property'
                        """);
                statement.execute("""
                        insert into lead_shortlists(lead_id,version,created_at,updated_at)
                        select id,0,clock_timestamp(),clock_timestamp()
                        from lead_routing_queue where phone_number='+919700000001'
                        """);
                statement.execute("""
                        insert into lead_shortlist_items(shortlist_id,listing_id,position)
                        select s.id,l.id,0
                        from lead_shortlists s
                        join lead_routing_queue q on q.id=s.lead_id
                        join listings l on l.title='Synthetic Upgrade Property'
                        where q.phone_number='+919700000001'
                        """);
                statement.execute("""
                        insert into property_visit_requests(tenant_id,listing_id,status,created_at)
                        select u.id,l.id,'RECEIVED',clock_timestamp()
                        from users u, listings l
                        where u.email='a1a7-upgrade-fixture@example.test'
                          and l.title='Synthetic Upgrade Property'
                        """);
                statement.execute("""
                        insert into lead_visit_proposals(id,lead_id,shortlist_id,listing_id,
                            contact_preference_revision,lead_version,shortlist_version,listing_version,
                            token_hash,state,association_status,created_by_user_id,created_at,expires_at,
                            version)
                        select '33333333-3333-4333-8333-333333333333',q.id,s.id,l.id,1,0,0,0,
                               repeat('b',64),'OPEN','CONFIRMED',u.id,clock_timestamp(),
                               clock_timestamp()+interval '1 day',0
                        from lead_routing_queue q
                        join lead_shortlists s on s.lead_id=q.id
                        join lead_shortlist_items i on i.shortlist_id=s.id
                        join listings l on l.id=i.listing_id
                        join users u on u.id=q.owner_user_id
                        where q.phone_number='+919700000001'
                          and l.title='Synthetic Upgrade Property'
                        """);
                statement.execute("""
                        insert into lead_visit_links(id,lead_id,user_id,proposal_id,request_id,confirmed_at)
                        select '44444444-4444-4444-8444-444444444444',q.id,u.id,
                               '33333333-3333-4333-8333-333333333333',r.id,clock_timestamp()
                        from lead_routing_queue q, users u, property_visit_requests r
                        where q.phone_number='+919700000001'
                          and u.email='a1a7-upgrade-fixture@example.test'
                          and r.tenant_id=u.id
                        """);
            }
        } catch (Exception exception) {
            throw refuse("V61 synthetic preserved-link fixture failed", exception);
        }
    }

    private static void prepareSchema(Connection connection, String schema) throws Exception {
        String owner = scalar(connection,
                "select pg_get_userbyid(nspowner) from pg_namespace where nspname=?",
                schema);
        if (owner == null) {
            try (var statement = connection.createStatement()) {
                statement.execute("create schema " + schema
                        + " authorization " + A1A7IsolatedTarget.MIGRATOR_ROLE);
                statement.execute("revoke all on schema " + schema + " from public");
                statement.execute("grant usage on schema " + schema
                        + " to " + A1A7IsolatedTarget.RUNTIME_ROLE
                        + "," + A1A7IsolatedTarget.RUNNER_ROLE);
            }
        } else if (!A1A7IsolatedTarget.MIGRATOR_ROLE.equals(owner)) {
            throw refuse("scenario schema is owned by an unexpected role");
        }
    }

    private static void grantScenarioDml(A1A7IsolatedTarget.Target migrator) {
        try (Connection connection = migrator.open();
             var statement = connection.createStatement()) {
            for (String role : new String[]{
                    A1A7IsolatedTarget.RUNTIME_ROLE, A1A7IsolatedTarget.RUNNER_ROLE}) {
                statement.execute("grant usage on schema " + migrator.schema() + " to " + role);
                statement.execute("grant select,insert,update,delete on all tables in schema "
                        + migrator.schema() + " to " + role);
                statement.execute("grant usage,select on all sequences in schema "
                        + migrator.schema() + " to " + role);
                statement.execute("alter default privileges for role "
                        + A1A7IsolatedTarget.MIGRATOR_ROLE + " in schema "
                        + migrator.schema()
                        + " grant select,insert,update,delete on tables to " + role);
                statement.execute("alter default privileges for role "
                        + A1A7IsolatedTarget.MIGRATOR_ROLE + " in schema "
                        + migrator.schema() + " grant usage,select on sequences to " + role);
            }
        } catch (Exception exception) {
            throw refuse("scenario DML grants failed", exception);
        }
    }

    private static void verifyLedger(A1A7IsolatedTarget.Target migrator, int ceiling) {
        A1A7IsolatedTarget.verifyLiveIdentity(migrator);
        try (Connection connection = migrator.open()) {
            setSearchPath(connection, migrator.schema());
            require(scalarLong(connection, "select count(*) from "
                    + migrator.schema() + ".flyway_schema_history where version='1' and success") == 1,
                    "baseline V1 receipt");
            require(scalarLong(connection, "select count(*) from "
                    + migrator.schema() + ".flyway_schema_history where success "
                    + "and version::integer between 2 and " + ceiling)
                    == ceiling - 1, "successful V2-V" + ceiling + " receipts");
            require(scalarLong(connection, "select count(*) from "
                    + migrator.schema() + ".flyway_schema_history where version is not null "
                    + "and version::integer > " + ceiling) == 0, "above-ceiling migrations");
        } catch (Exception exception) {
            throw refuse("Flyway receipt verification failed", exception);
        }
    }

    private static void verifyApprovedSources() {
        try {
            FreshIntegrationSchemaHarness.verifySourcePins(
                    new FreshIntegrationTarget.SourcePins(
                            FreshIntegrationTarget.LEGACY_CORE_SHA256,
                            FreshIntegrationTarget.MIGRATIONS_SHA256,
                            FreshIntegrationTarget.V55_SHA256,
                            FreshIntegrationTarget.V56_SHA256,
                            FreshIntegrationTarget.V57_SHA256));
        } catch (java.io.IOException exception) {
            throw refuse("approved V1-V60 source bytes could not be verified", exception);
        }
        requireDigest("db/migration/V61__lead_phone_availability_and_visit_conversion.sql",
                V61_SHA256);
        requireDigest("db/migration/V62__lead_visit_link_per_property.sql",
                V62_SHA256);
        requireDigest("db/migration/V63__lead_phone_outbox_delivered_state.sql",
                V63_SHA256);
        try {
            var resources = new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                    .getResources("classpath:db/migration/V*.sql");
            long higher = java.util.Arrays.stream(resources)
                    .map(org.springframework.core.io.Resource::getFilename)
                    .filter(java.util.Objects::nonNull)
                    .map(name -> name.replaceFirst("^V([0-9]+)__.*$", "$1"))
                    .filter(value -> value.chars().allMatch(Character::isDigit))
                    .mapToInt(Integer::parseInt)
                    .filter(version -> version > 63)
                    .count();
            require(higher == 0, "no V64+ migration source");
        } catch (java.io.IOException exception) {
            throw refuse("migration inventory could not be read", exception);
        }
    }

    private static void requireDigest(String resource, String expected) {
        try {
            var input = new ClassPathResource(resource).getInputStream();
            String actual = java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
            require(expected.equals(actual), resource + " digest");
        } catch (Exception exception) {
            throw refuse(resource + " could not be verified", exception);
        }
    }

    private static void setSearchPath(Connection connection, String schema) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute("set search_path to " + schema + ", it_extensions, pg_catalog");
        }
    }

    private static boolean hasHistory(Connection connection, String schema) throws Exception {
        try (var statement = connection.prepareStatement(
                "select to_regclass(?) is not null")) {
            statement.setString(1, schema + ".flyway_schema_history");
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && rows.getBoolean(1);
            }
        }
    }

    private static boolean relationExists(Connection connection, String relation) throws Exception {
        try (var statement = connection.prepareStatement("select to_regclass(?) is not null")) {
            statement.setString(1, relation);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && rows.getBoolean(1);
            }
        }
    }

    private static boolean constraintExists(Connection connection, String schema,
                                            String constraint) throws Exception {
        try (var statement = connection.prepareStatement("""
                select count(*)
                from pg_constraint c
                join pg_class r on r.oid = c.conrelid
                join pg_namespace n on n.oid = r.relnamespace
                where n.nspname = ? and c.conname = ?
                """)) {
            statement.setString(1, schema);
            statement.setString(2, constraint);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && rows.getLong(1) == 1L;
            }
        }
    }

    private static String scalar(Connection connection, String sql, Object argument)
            throws Exception {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, argument);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    private static long scalarLong(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) throw refuse("verification query returned no row");
            return rows.getLong(1);
        }
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw refuse(label);
    }

    private static IllegalStateException refuse(String message) {
        return new IllegalStateException("REFUSED A1-A7 schema: " + message);
    }

    private static IllegalStateException refuse(String message, Throwable cause) {
        return new IllegalStateException("REFUSED A1-A7 schema: " + message, cause);
    }
}
