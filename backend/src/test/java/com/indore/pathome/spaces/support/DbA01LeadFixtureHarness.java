package com.indore.pathome.spaces.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Installs and verifies only the nine owner-approved DB-A01 test fixture objects. */
public final class DbA01LeadFixtureHarness {
    private static final List<String> OBJECTS = List.of(
            "public.similarity(text,text)",
            "public.db_a01_pg_trgm_match(text,text)",
            "public.%(text,text)",
            "public.db_a01_activity_failure",
            "public.db_a01_event_gate_seq",
            "public.db_a01_hold_share_event()",
            "public.lead_share_events.trg_db_a01_hold_share_event",
            "public.db_a01_fail_lead_activity()",
            "public.lead_activities.trg_db_a01_fail_lead_activity"
    );

    private DbA01LeadFixtureHarness() {}

    public static String installAndVerify(
            Connection connection,
            DbA01LeadTestTarget.Target target
    ) throws SQLException {
        int existingObjects = fixtureSurfaceCount(connection);
        if (existingObjects == 9) {
            verifyInstalled(connection, target);
            return definitionDigest(connection);
        }
        require(existingObjects == 0,
                "partial fixture surface prevents safe setup or retry");
        verifyPrewrite(connection, target);
        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    create function public.similarity(text, text)
                    returns real language sql immutable parallel safe
                    security definer set search_path = public, it_extensions, pg_catalog as $$
                        select it_extensions.similarity($1, $2)
                    $$
                    """);
            statement.execute("""
                    create function public.db_a01_pg_trgm_match(text, text)
                    returns boolean language sql immutable parallel safe
                    security definer set search_path = public, it_extensions, pg_catalog as $$
                        select $1 operator(it_extensions.%) $2
                    $$
                    """);
            statement.execute("""
                    do $$ begin
                        execute 'set local search_path = public, it_extensions, pg_catalog';
                        execute 'create operator public.% (
                            leftarg = text, rightarg = text,
                            procedure = public.db_a01_pg_trgm_match)';
                    end $$
                    """);
            statement.execute("""
                    create table public.db_a01_activity_failure (
                        lead_id bigint primary key,
                        fail boolean not null
                    )
                    """);
            statement.execute("create sequence public.db_a01_event_gate_seq");
            statement.execute("""
                    create function public.db_a01_hold_share_event()
                    returns trigger language plpgsql security definer
                    set search_path = public as $$
                    begin
                        perform nextval('public.db_a01_event_gate_seq');
                        perform pg_advisory_xact_lock(918273645);
                        return new;
                    end
                    $$
                    """);
            statement.execute("""
                    create trigger trg_db_a01_hold_share_event
                    before insert on public.lead_share_events
                    for each row execute function public.db_a01_hold_share_event()
                    """);
            statement.execute("""
                    create function public.db_a01_fail_lead_activity()
                    returns trigger language plpgsql security definer
                    set search_path = public as $$
                    begin
                        if exists (
                            select 1 from public.db_a01_activity_failure
                            where lead_id = new.lead_id and fail
                        ) then
                            raise exception 'DB-A01 injected activity failure';
                        end if;
                        return new;
                    end
                    $$
                    """);
            statement.execute("""
                    create trigger trg_db_a01_fail_lead_activity
                    before insert on public.lead_activities
                    for each row execute function public.db_a01_fail_lead_activity()
                    """);
            statement.execute("""
                    revoke all on function
                        public.similarity(text, text),
                        public.db_a01_pg_trgm_match(text, text),
                        public.db_a01_hold_share_event(),
                        public.db_a01_fail_lead_activity()
                    from public
                    """);
            statement.execute("""
                    revoke all on table public.db_a01_activity_failure from public,
                        oe_dbf_runtime_cbb093e7
                    """);
            statement.execute("""
                    revoke all on sequence public.db_a01_event_gate_seq from public,
                        oe_dbf_runtime_cbb093e7
                    """);
            statement.execute("""
                    grant execute on function public.similarity(text, text),
                        public.db_a01_pg_trgm_match(text, text)
                    to oe_dbf_runtime_cbb093e7
                    """);
            connection.commit();
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(originalAutoCommit);
        }
        verifyInstalled(connection, target);
        return definitionDigest(connection);
    }

    public static void verifyInstalled(
            Connection connection,
            DbA01LeadTestTarget.Target target
    ) throws SQLException {
        requireIdentity(connection, target);
        require(count(connection, """
                select count(*) from pg_proc p
                join pg_namespace n on n.oid = p.pronamespace
                where n.nspname = 'public'
                  and p.proname in ('similarity', 'db_a01_pg_trgm_match',
                                    'db_a01_hold_share_event', 'db_a01_fail_lead_activity')
                """) == 4, "exactly four approved fixture functions are required");
        require(count(connection, """
                select count(*) from pg_proc p
                join pg_namespace n on n.oid = p.pronamespace
                where n.nspname = 'public'
                  and p.proname in ('similarity', 'db_a01_pg_trgm_match',
                                    'db_a01_hold_share_event', 'db_a01_fail_lead_activity')
                  and p.prosecdef
                  and pg_get_userbyid(p.proowner) = current_user
                  and (
                    (p.proname in ('similarity', 'db_a01_pg_trgm_match') and exists (
                        select 1 from unnest(p.proconfig) config
                        where config = 'search_path=public, it_extensions, pg_catalog'
                    ))
                    or
                    (p.proname in ('db_a01_hold_share_event', 'db_a01_fail_lead_activity') and exists (
                        select 1 from unnest(p.proconfig) config
                        where config = 'search_path=public'
                    ))
                  )
                """) == 4, "fixture function security properties changed");
        require(count(connection, """
                select count(*) from pg_operator o
                join pg_namespace n on n.oid = o.oprnamespace
                where n.nspname = 'public' and o.oprname = '%'
                  and o.oprleft = 'text'::regtype and o.oprright = 'text'::regtype
                  and o.oprresult = 'boolean'::regtype
                  and o.oprcode = 'public.db_a01_pg_trgm_match(text,text)'::regprocedure
                """) == 1, "fixture operator changed");
        require(count(connection, """
                select count(*) from pg_class c
                join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = 'public' and c.relname = 'db_a01_activity_failure'
                  and c.relkind = 'r' and pg_get_userbyid(c.relowner) = current_user
                """) == 1, "fixture failure table changed");
        require(count(connection, """
                select count(*) from pg_class c
                join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = 'public' and c.relname = 'db_a01_event_gate_seq'
                  and c.relkind = 'S' and pg_get_userbyid(c.relowner) = current_user
                """) == 1, "fixture gate sequence changed");
        require(count(connection, """
                select count(*) from pg_trigger t
                join pg_class c on c.oid = t.tgrelid
                join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = 'public'
                  and (
                    (c.relname = 'lead_share_events'
                     and t.tgname = 'trg_db_a01_hold_share_event'
                     and t.tgfoid = 'public.db_a01_hold_share_event()'::regprocedure)
                    or
                    (c.relname = 'lead_activities'
                     and t.tgname = 'trg_db_a01_fail_lead_activity'
                     and t.tgfoid = 'public.db_a01_fail_lead_activity()'::regprocedure)
                  )
                  and t.tgenabled = 'O'
                  and not t.tgisinternal
                """) == 2, "fixture trigger attachment changed");
        require(!scalarBoolean(connection,
                "select has_table_privilege('oe_dbf_runtime_cbb093e7', "
                        + "'public.db_a01_activity_failure', 'SELECT')"),
                "runtime can read the fixture failure table");
        require(!scalarBoolean(connection,
                "select has_sequence_privilege('oe_dbf_runtime_cbb093e7', "
                        + "'public.db_a01_event_gate_seq', 'USAGE')"),
                "runtime can consume the fixture gate sequence");
        require(!scalarBoolean(connection,
                "select has_function_privilege('oe_dbf_runtime_cbb093e7', "
                        + "'public.db_a01_fail_lead_activity()', 'EXECUTE')"),
                "runtime can execute the fixture failure trigger");
        require(scalarBoolean(connection,
                "select has_function_privilege('oe_dbf_runtime_cbb093e7', "
                        + "'public.similarity(text,text)', 'EXECUTE')"),
                "runtime cannot execute the approved similarity wrapper");
        require(scalarBoolean(connection,
                "select has_function_privilege('oe_dbf_runtime_cbb093e7', "
                        + "'public.db_a01_pg_trgm_match(text,text)', 'EXECUTE')"),
                "runtime cannot execute the approved trigram wrapper");
    }

    private static void verifyPrewrite(
            Connection connection,
            DbA01LeadTestTarget.Target target
    ) throws SQLException {
        requireIdentity(connection, target);
        require(!scalarBoolean(connection,
                "select has_schema_privilege('oe_dbf_runtime_cbb093e7', 'public', 'CREATE')"),
                "runtime has CREATE privilege before fixture setup");
        require(!scalarBoolean(connection,
                "select has_database_privilege('oe_dbf_runtime_cbb093e7', "
                        + "current_database(), 'CREATE')"),
                "runtime has database CREATE privilege before fixture setup");
        require(count(connection, """
                select count(*) from pg_auth_members m
                join pg_roles granted on granted.oid = m.roleid
                join pg_roles member on member.oid = m.member
                where member.rolname = 'oe_dbf_runtime_cbb093e7'
                  and granted.rolname in ('oe_dbf_migrator_cbb093e7',
                                          'oe_dbf_db_owner_cbb093e7',
                                          'oe_dbf_provisioner_cbb093e7')
                """) == 0, "runtime inherits an approved privileged role");
        require(count(connection, """
                select count(*) from pg_proc p
                join pg_namespace n on n.oid = p.pronamespace
                where n.nspname = 'public'
                  and p.proname in ('similarity', 'db_a01_pg_trgm_match',
                                    'db_a01_hold_share_event', 'db_a01_fail_lead_activity')
                """) == 0, "fixture function appears before receipted setup");
        require(count(connection, """
                select count(*) from pg_operator o
                join pg_namespace n on n.oid = o.oprnamespace
                where n.nspname = 'public' and o.oprname = '%'
                  and o.oprleft = 'text'::regtype and o.oprright = 'text'::regtype
                """) == 0, "fixture operator appears before receipted setup");
        require(count(connection, """
                select count(*) from pg_class c
                join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = 'public'
                  and c.relname in ('db_a01_activity_failure', 'db_a01_event_gate_seq')
                """) == 0, "fixture relation appears before receipted setup");
        require(count(connection, """
                select count(*) from pg_trigger t
                join pg_class c on c.oid = t.tgrelid
                where c.relname in ('lead_share_events', 'lead_activities')
                  and t.tgname in ('trg_db_a01_hold_share_event',
                                   'trg_db_a01_fail_lead_activity')
                """) == 0, "fixture trigger appears before receipted setup");
        require(count(connection, """
                select count(*) from pg_class c
                join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = 'public'
                  and c.relname in ('lead_share_events', 'lead_activities')
                  and c.relkind = 'r' and pg_get_userbyid(c.relowner) = current_user
                """) == 2, "fixture target tables are missing or owned incorrectly");
        require(count(connection, """
                select count(*) from pg_proc p
                join pg_namespace n on n.oid = p.pronamespace
                join pg_namespace e on e.nspname = 'it_extensions'
                where n.nspname = 'it_extensions' and p.proname = 'similarity'
                """) >= 1, "approved pg_trgm extension function is unavailable");
    }

    private static String definitionDigest(Connection connection) throws SQLException {
        String sql = """
                select entry from (
                  select 'function|' || n.nspname || '|' || p.proname || '|' ||
                         pg_get_function_identity_arguments(p.oid) || '|' ||
                         p.prosecdef || '|' || coalesce(array_to_string(p.proconfig, ','), '') || '|' ||
                         md5(p.prosrc) as entry
                  from pg_proc p join pg_namespace n on n.oid = p.pronamespace
                  where n.nspname = 'public'
                    and p.proname in ('similarity', 'db_a01_pg_trgm_match',
                                      'db_a01_hold_share_event', 'db_a01_fail_lead_activity')
                  union all
                  select 'operator|' || o.oprname || '|' || o.oprcode::text
                  from pg_operator o join pg_namespace n on n.oid = o.oprnamespace
                  where n.nspname = 'public' and o.oprname = '%'
                  union all
                  select 'relation|' || c.relname || '|' || c.relkind::text
                  from pg_class c join pg_namespace n on n.oid = c.relnamespace
                  where n.nspname = 'public'
                    and c.relname in ('db_a01_activity_failure', 'db_a01_event_gate_seq')
                  union all
                  select 'trigger|' || t.tgname || '|' || t.tgfoid::regprocedure::text || '|' ||
                         t.tgenabled::text
                  from pg_trigger t join pg_class c on c.oid = t.tgrelid
                  where c.relname in ('lead_share_events', 'lead_activities')
                    and t.tgname in ('trg_db_a01_hold_share_event',
                                     'trg_db_a01_fail_lead_activity')
                ) fixture
                order by entry
                """;
        List<String> entries = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) entries.add(rows.getString(1));
        }
        require(entries.size() == 9, "fixture definition snapshot does not contain nine objects");
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            String.join("\n", entries).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("fixture definition digest failed", exception);
        }
    }

    private static int fixtureSurfaceCount(Connection connection) throws SQLException {
        return (int) count(connection, """
                select
                  (select count(*) from pg_proc p
                   join pg_namespace n on n.oid = p.pronamespace
                   where n.nspname = 'public'
                     and p.proname in ('similarity', 'db_a01_pg_trgm_match',
                                       'db_a01_hold_share_event', 'db_a01_fail_lead_activity'))
                  +
                  (select count(*) from pg_operator o
                   join pg_namespace n on n.oid = o.oprnamespace
                   where n.nspname = 'public' and o.oprname = '%'
                     and o.oprleft = 'text'::regtype and o.oprright = 'text'::regtype)
                  +
                  (select count(*) from pg_class c
                   join pg_namespace n on n.oid = c.relnamespace
                   where n.nspname = 'public'
                     and c.relname in ('db_a01_activity_failure', 'db_a01_event_gate_seq'))
                  +
                  (select count(*) from pg_trigger t
                   join pg_class c on c.oid = t.tgrelid
                   where c.relname in ('lead_share_events', 'lead_activities')
                     and t.tgname in ('trg_db_a01_hold_share_event',
                                      'trg_db_a01_fail_lead_activity'))
                """);
    }

    private static void requireIdentity(
            Connection connection,
            DbA01LeadTestTarget.Target target
    ) throws SQLException {
        require(DbA01LeadTestTarget.DATABASE.equals(
                scalar(connection, "select current_database()")), "fixture database identity");
        require(DbA01LeadTestTarget.MIGRATOR_ROLE.equals(
                scalar(connection, "select current_user")), "fixture migrator identity");
        require(DbA01LeadTestTarget.MARKER.equals(
                scalar(connection,
                        "select disposable_marker from it_control.run_identity")),
                "fixture control marker");
        require(target.databaseOid() == scalarLong(connection,
                "select oid from pg_database where datname=current_database()"),
                "fixture database OID");
    }

    private static long count(Connection connection, String sql) throws SQLException {
        return scalarLong(connection, sql);
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) throw refuse("fixture identity query returned no row");
            return rows.getString(1);
        }
    }

    private static long scalarLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) throw refuse("fixture count query returned no row");
            return rows.getLong(1);
        }
    }

    private static boolean scalarBoolean(Connection connection, String sql)
            throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) throw refuse("fixture privilege query returned no row");
            return rows.getBoolean(1);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw refuse(message);
    }

    private static IllegalStateException refuse(String message) {
        return new IllegalStateException("REFUSED DB-A01 fixture setup: " + message);
    }
}
