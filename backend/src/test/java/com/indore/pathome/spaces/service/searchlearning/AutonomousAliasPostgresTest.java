package com.indore.pathome.spaces.service.searchlearning;

import com.indore.pathome.spaces.entity.Locality;
import com.indore.pathome.spaces.entity.SearchAlias;
import com.indore.pathome.spaces.repository.*;
import com.indore.pathome.spaces.service.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real PostgreSQL transactions, locks, schema migration and cache; no admin approval in autonomous fixtures. */
@EnabledIfEnvironmentVariable(named="PATHOME_V2_TEST_URL", matches=".+")
class AutonomousAliasPostgresTest {
    JdbcTemplate jdbc, root;
    AutonomousLearningProperties p;
    AutonomousAliasEvaluator evaluator;
    AliasMutationCoordinator mutations;
    SearchLearningService cache;
    SearchAliasRepository aliases;
    SearchAliasCandidateRepository candidates;
    LocalityRepository localities;
    ListingRepository listings;
    DataSourceTransactionManager tm;
    MutableClock clock;
    String schema;
    Flyway flyway;
    @BeforeEach void setup() throws Exception {
        String url=System.getenv("PATHOME_V2_TEST_URL");
        if (!url.contains("_test")) throw new IllegalStateException("Use a dedicated _test database");
        String user=System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME",System.getProperty("user.name"));
        String password=System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD","");
        root=new JdbcTemplate(new DriverManagerDataSource(url,user,password));
        schema="v2_"+UUID.randomUUID().toString().replace("-","");
        root.execute("CREATE SCHEMA "+schema);
        var ds=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,user,password);
        jdbc=new JdbcTemplate(ds); tm=new DataSourceTransactionManager(ds);
        jdbc.execute("CREATE TABLE localities(id BIGSERIAL PRIMARY KEY,city VARCHAR(120),sector_name VARCHAR(120),created_at TIMESTAMP,UNIQUE(city,sector_name))");
        Path migration=Files.createTempDirectory("pathome-v2-migrations");
        for (String name:List.of("V15__search_learning_tables.sql","V16__supported_cities_and_localities.sql","V17__autonomous_search_alias_learning.sql")) {
            try(var in=new ClassPathResource("db/migration/"+name).getInputStream()) { Files.copy(in,migration.resolve(name)); }
        }
        flyway=Flyway.configure().dataSource(ds).defaultSchema(schema).locations("filesystem:"+migration)
                .baselineOnMigrate(true).baselineVersion("14").load();
        assertEquals(3,flyway.migrate().migrationsExecuted);
        try(var files=Files.list(migration)) { for(Path file:files.toList()) file.toFile().deleteOnExit(); }
        migration.toFile().deleteOnExit();
        p=new AutonomousLearningProperties(); clock=new MutableClock(Instant.parse("2026-09-27T12:00:00Z"));
        aliases=mock(SearchAliasRepository.class); candidates=mock(SearchAliasCandidateRepository.class);
        cache=spy(new SearchLearningService(mock(SearchQueryEventRepository.class),candidates,aliases));
        ObjectProvider<SearchLearningService> provider=mock(ObjectProvider.class); when(provider.getObject()).thenReturn(cache);
        mutations=new AliasMutationCoordinator(jdbc,provider,tm,clock);
        ReflectionTestUtils.setField(cache,"mutations",mutations);
        localities=mock(LocalityRepository.class); listings=mock(ListingRepository.class);
        when(localities.findByCityIgnoreCaseAndSectorNameIgnoreCase(anyString(),anyString())).thenAnswer(inv -> {
            List<Locality> rows=jdbc.query("SELECT * FROM localities WHERE lower(city)=lower(?) AND lower(sector_name)=lower(?)",
                    (rs,n)->new Locality(rs.getString("city"),rs.getString("sector_name"),null,null,null),inv.getArgument(0),inv.getArgument(1));
            return rows.stream().findFirst();
        });
        when(listings.findPublicRentalLocalitySuggestions(anyString(),anyString(),anyString(),anyString(),any(),any(),anyString(),any()))
                .thenAnswer(inv -> "vijay nagar".equals(inv.getArgument(6))?List.of(row("Vijay Nagar","Indore",1.0)):List.of());
        when(listings.findPublicRentalFuzzyLocalities(anyString(),anyString(),anyString(),anyString(),any(),any(),anyString(),anyDouble(),any()))
                .thenAnswer(inv -> List.of(row("Vijay Nagar","Indore",.8)));
        evaluator=new AutonomousAliasEvaluator(jdbc,p,localities,listings,mutations,tm,clock);
        when(aliases.findById(anyLong())).thenAnswer(inv -> jdbc.query("SELECT * FROM search_alias WHERE id=?",(rs,n)->{
            SearchAlias a=new SearchAlias(); ReflectionTestUtils.setField(a,"id",rs.getLong("id"));a.setAliasTerm(rs.getString("alias_term"));
            a.setEntityType(rs.getString("entity_type"));a.setEntityValue(rs.getString("entity_value"));a.setEntityCity(rs.getString("entity_city"));
            a.setStatus(rs.getString("status"));a.setSourceCandidateId(rs.getObject("source_candidate_id",Long.class));return a;
        },(Long)inv.getArgument(0)).stream().findFirst());
        when(aliases.save(any(SearchAlias.class))).thenAnswer(inv->{
            SearchAlias a=inv.getArgument(0);jdbc.update("UPDATE search_alias SET status=?,disabled_at=?,disabled_reason=? WHERE id=?",
                    a.getStatus(),a.getDisabledAt(),a.getDisabledReason(),a.getId());return a;
        });
    }
    @AfterEach void cleanup() { if(root!=null && schema!=null) root.execute("DROP SCHEMA "+schema+" CASCADE"); }
    ListingRepository.LocalitySuggestionRow row(String target,String city,double confidence) {
        var r=mock(ListingRepository.LocalitySuggestionRow.class);when(r.getLocality()).thenReturn(target);when(r.getCity()).thenReturn(city);
        when(r.getSimilarity()).thenReturn(confidence);when(r.getResultCount()).thenReturn(1L);return r;
    }
    long raw(String target,String city,String session,Instant time,String selectedType) {
        return jdbc.queryForObject("INSERT INTO search_query_event(occurred_at,event_type,location_candidate,resolved_locality,resolved_city,city_input,selected_type,session_hash) "
                + "VALUES (?,'SUGGESTION_SELECTED','vijaynagr',?,?,?,?,?) RETURNING id",Long.class,
                LocalDateTime.ofInstant(time,ZoneOffset.UTC),target,city,city,selectedType,SearchLearningService.hashSessionId(session));
    }
    void events(int count,String target,String city,String prefix,Instant start) {
        for(int i=0;i<count;i++) evaluator.observe(raw(target,city,prefix+i,start.plusSeconds(i*7200L),"LOCALITY"));
    }
    long candidate() { return jdbc.queryForObject("SELECT id FROM search_alias_candidate WHERE candidate_term='vijaynagr' AND canonical_entity_value='Vijay Nagar' AND canonical_city='indore'",Long.class); }
    String state(long id) { return jdbc.queryForObject("SELECT lifecycle_state FROM search_alias_candidate WHERE id=?",String.class,id); }
    long alias() { return jdbc.queryForObject("SELECT id FROM search_alias WHERE alias_term='vijaynagr'",Long.class); }
    void qualifiedShadow() {
        events(30,"Vijay Nagar","Indore","learn",clock.instant().minus(Duration.ofDays(4)));
        evaluator.evaluate(candidate()); assertEquals("SHADOW",state(candidate()));
        clock.advance(Duration.ofDays(4));
        events(30,"Vijay Nagar","Indore","shadow",clock.instant().minus(Duration.ofDays(3)));
    }
    void active() { qualifiedShadow(); evaluator.evaluate(candidate());assertEquals("ACTIVE",state(candidate())); }

    @Test void controlledLifecycleWithoutAdminApprovalThenDegradationDisableAndFallback() {
        long first=raw("Vijay Nagar","Indore","one",clock.instant().minus(Duration.ofDays(4)),"LOCALITY");
        evaluator.observe(first); long id=candidate(); assertEquals("OBSERVED",state(id));
        evaluator.evaluate(id);assertEquals("EVIDENCE_BUILDING",state(id));
        events(29,"Vijay Nagar","Indore","learn",clock.instant().minus(Duration.ofDays(4)).plusSeconds(7200));
        evaluator.evaluate(id);assertEquals("SHADOW",state(id));
        var resolver=new RentalLocationResolver(listings,cache,localities);
        assertNull(cache.resolveLocalityAlias("vijaynagr","indore"));
        assertEquals("trigram",resolver.resolveForDiscovery(RentalSearchQuery.parse("vijaynagr"),"indore").method());
        evaluator.evaluate(id);assertEquals("SHADOW",state(id));
        clock.advance(Duration.ofDays(4));events(30,"Vijay Nagar","Indore","shadow",clock.instant().minus(Duration.ofDays(3)));
        evaluator.evaluate(id);assertEquals("ACTIVE",state(id));
        assertEquals("Vijay Nagar",cache.resolveLocalityAlias("vijaynagr","indore"));
        assertNull(cache.resolveLocalityAlias("vijaynagr","pune"));assertNull(cache.resolveLocalityAlias("vijaynagr",""));
        assertEquals("alias",resolver.resolveForDiscovery(RentalSearchQuery.parse("vijaynagr"),"indore").method());
        verify(cache,never()).approveCandidate(anyLong(),any());
        clock.advance(Duration.ofDays(3));events(20,"Nanda Nagar","Indore","bad",clock.instant().minus(Duration.ofDays(2)));
        evaluator.evaluate(id);assertEquals("DEGRADED",state(id));
        evaluator.evaluate(id);assertEquals("DEGRADED",state(id));
        clock.advance(Duration.ofDays(3));events(20,"Nanda Nagar","Indore","worse",clock.instant().minus(Duration.ofDays(2)));
        evaluator.evaluate(id);assertEquals("AUTO_DISABLED",state(id));
        assertNull(cache.resolveLocalityAlias("vijaynagr","indore"));
        assertEquals("trigram",resolver.resolveForDiscovery(RentalSearchQuery.parse("vijaynagr"),"indore").method());
        var transitions=jdbc.queryForList("SELECT event FROM search_alias_audit WHERE candidate_id=? ORDER BY id",String.class,id);
        assertTrue(transitions.containsAll(List.of("CANDIDATE_CREATED","ENTERED_SHADOW","AUTO_PROMOTED","DEGRADED","AUTO_DISABLED")));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM search_alias_audit WHERE policy_version<>'AUTONOMOUS_V2'",Integer.class));
        evaluator.evaluate(id);assertEquals("AUTO_DISABLED",state(id));
        clock.advance(Duration.ofDays(8));evaluator.evaluate(id);assertEquals("EVIDENCE_BUILDING",state(id));
        evaluator.evaluate(id);assertEquals("EVIDENCE_BUILDING",state(id));assertNull(cache.resolveLocalityAlias("vijaynagr","indore"));
    }
    @Test void sameSessionHundredEventsAreCappedWhileIndependentSessionsQualify() {
        for(int i=0;i<100;i++) evaluator.observe(raw("Vijay Nagar","Indore","same",clock.instant().minusSeconds(7200L*(100-i)),"LOCALITY"));
        evaluator.evaluate(candidate());assertEquals("EVIDENCE_BUILDING",state(candidate()));
        String decision=jdbc.queryForObject("SELECT last_decision FROM search_alias_candidate WHERE id=?",String.class,candidate());
        assertTrue(decision.contains("INSUFFICIENT_UNIQUE_SESSIONS"));
        assertEquals(1,jdbc.queryForObject("SELECT evidence_count FROM search_alias_candidate WHERE id=?",Integer.class,candidate()),
                "The SQL aggregation itself must cap 100 same-session events to one effective observation");
        events(100,"Vijay Nagar","Indore","independent",clock.instant().minus(Duration.ofDays(10)));
        evaluator.evaluate(candidate());assertEquals("SHADOW",state(candidate()));
    }
    @Test void ageAndBurstBlockHundredIndependentEvents() {
        events(100,"Vijay Nagar","Indore","young",clock.instant().minusSeconds(100*7200L));
        jdbc.update("UPDATE search_alias_evidence SET occurred_at=?",LocalDateTime.ofInstant(clock.instant().minusSeconds(30),ZoneOffset.UTC));
        evaluator.evaluate(candidate());assertEquals("EVIDENCE_BUILDING",state(candidate()));
        String decision=jdbc.queryForObject("SELECT last_decision FROM search_alias_candidate WHERE id=?",String.class,candidate());
        assertTrue(decision.contains("CANDIDATE_TOO_NEW"));assertTrue(decision.contains("BURST_CONCENTRATION"));
    }
    @Test void competingTargetsAndCitiesAreIsolated() {
        events(30,"Vijay Nagar","Indore","good",clock.instant().minus(Duration.ofDays(4)));
        events(30,"Nanda Nagar","Indore","compete",clock.instant().minus(Duration.ofDays(4)));
        evaluator.evaluate(candidate());assertEquals("EVIDENCE_BUILDING",state(candidate()));
        assertTrue(jdbc.queryForObject("SELECT last_decision FROM search_alias_candidate WHERE id=?",String.class,candidate()).contains("COMPETING_TARGETS"));
        evaluator.observe(raw("Vijay Nagar","Pune","wrong-city",clock.instant().minusSeconds(1),"ENTITY_MATCH"));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM search_alias_evidence WHERE city_key='pune'",Integer.class));
    }
    @Test void poorShadowAgreementNeverActivatesAndDoesNotChangeProduction() {
        qualifiedShadow();jdbc.update("UPDATE search_alias_evidence SET deterministic_target='Nanda Nagar' WHERE occurred_at >= (SELECT shadow_since FROM search_alias_candidate WHERE id=?)",candidate());
        evaluator.evaluate(candidate());assertEquals("SHADOW",state(candidate()));assertNull(cache.resolveLocalityAlias("vijaynagr","indore"));
    }
    @Test void manualDisableWinsAndExplicitResetRequiresFreshEvidence() {
        active();long id=candidate();long aliasId=alias();
        jdbc.update("UPDATE search_alias SET policy_version=NULL WHERE id=?",aliasId); // Previously manually owned alias
        new TransactionTemplate(tm).executeWithoutResult(s -> cache.disableAlias(aliasId,"Emergency review"));
        assertEquals("MANUALLY_DISABLED",state(id));assertNull(cache.resolveLocalityAlias("vijaynagr","indore"));
        clock.advance(Duration.ofDays(20));evaluator.evaluate(id);assertEquals("MANUALLY_DISABLED",state(id));
        new TransactionTemplate(tm).executeWithoutResult(s -> cache.resetAutonomousCandidate(id));
        assertEquals("AUTONOMOUS_V2",jdbc.queryForObject("SELECT policy_version FROM search_alias WHERE id=?",String.class,aliasId));
        evaluator.evaluate(id);assertEquals("EVIDENCE_BUILDING",state(id));assertNull(cache.resolveLocalityAlias("vijaynagr","indore"));
    }
    @Test void killSwitchPausesTelemetryProcessingPromotionAndDisableButSearchWorks() {
        qualifiedShadow();p.setEnabled(false);evaluator.evaluate(candidate());assertEquals("SHADOW",state(candidate()));
        long e=raw("Vijay Nagar","Indore","paused",clock.instant(),"LOCALITY");evaluator.runBatch();evaluator.observe(e);
        assertNull(jdbc.queryForObject("SELECT autonomous_checked_at FROM search_query_event WHERE id=?",java.sql.Timestamp.class,e));
        p.setEnabled(true);evaluator.evaluate(candidate());assertEquals("ACTIVE",state(candidate()));
        p.setEnabled(false);jdbc.update("DELETE FROM localities WHERE sector_name='Vijay Nagar'");evaluator.evaluate(candidate());
        assertEquals("ACTIVE",state(candidate()));assertEquals("Vijay Nagar",cache.resolveLocalityAlias("vijaynagr","indore"));
        assertNotNull(new RentalLocationResolver(listings,cache,localities).resolveForDiscovery(RentalSearchQuery.parse("vijaynagr"),"indore"));
    }
    @Test void simultaneousEvaluatorsPromoteExactlyOnce() throws Exception {
        qualifiedShadow();long id=candidate();ExecutorService pool=Executors.newFixedThreadPool(2);
        try { var results=pool.invokeAll(List.of(()->{evaluator.evaluate(id);return true;},()->{evaluator.evaluate(id);return true;}));
            for(var result:results) assertEquals(true,result.get(10,TimeUnit.SECONDS));
        } finally {pool.shutdownNow();}
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM search_alias",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM search_alias_audit WHERE event='AUTO_PROMOTED'",Integer.class));
        evaluator.evaluate(id);assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM search_alias",Integer.class));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update(
                "INSERT INTO search_alias(alias_term,entity_type,entity_value,entity_city,policy_version) "
                + "VALUES ('VIJAYNAGR','LOCALITY','Vijay Nagar','Indore','AUTONOMOUS_V2')"));
    }
    @Test void promotionRacingManualDisableCannotResurrectCache() throws Exception {
        qualifiedShadow();long id=candidate();
        jdbc.update("INSERT INTO search_alias(alias_term,entity_type,entity_value,entity_city,source_candidate_id,status,policy_version,disable_source) "
                + "VALUES ('vijaynagr','LOCALITY','Vijay Nagar','indore',?,'DISABLED','AUTONOMOUS_V2','AUTO')",id);
        long aliasId=alias();ExecutorService pool=Executors.newFixedThreadPool(2);
        try { var results=pool.invokeAll(List.of(()->{evaluator.evaluate(id);return true;},()->{
                new TransactionTemplate(tm).executeWithoutResult(s -> cache.disableAlias(aliasId,"Race test"));return true;}));
            for(var result:results) assertEquals(true,result.get(10,TimeUnit.SECONDS));
        } finally {pool.shutdownNow();}
        evaluator.evaluate(id);cache.refreshAliasCache();assertEquals("MANUALLY_DISABLED",state(id));assertNull(cache.resolveLocalityAlias("vijaynagr","indore"));
    }
    @Test void rollbackNeverPublishesCacheAndMigrationIsRepeatable() {
        assertEquals(0,flyway.migrate().migrationsExecuted);flyway.validate();
        var tx=new TransactionTemplate(tm);
        tx.executeWithoutResult(s->{mutations.lock("vijaynagr","LOCALITY");
            jdbc.update("INSERT INTO search_alias(alias_term,entity_type,entity_value,entity_city) VALUES ('vijaynagr','LOCALITY','Vijay Nagar','indore')");
            mutations.syncAfterCommit("vijaynagr","LOCALITY","indore");s.setRollbackOnly();});
        assertNull(cache.resolveLocalityAlias("vijaynagr","indore"));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM search_alias",Integer.class));
    }
    @Test void repeatedObservationIsIdempotentAndBadCandidateDoesNotAbortBatch() {
        long e=raw("Vijay Nagar","Indore","one",clock.instant().minus(Duration.ofDays(4)),"LOCALITY");
        evaluator.observe(e);evaluator.observe(e);assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM search_alias_evidence",Integer.class));
        long id=candidate();jdbc.update("UPDATE search_alias_candidate SET candidate_term='invalid | query' WHERE id=?",id);
        events(30,"Vijay Nagar","Indore","good",clock.instant().minus(Duration.ofDays(4)));
        evaluator.runBatch();long good=candidate();assertEquals("SHADOW",state(good));
    }
    @Test void missingSessionsNeverBecomeIndependentEvidence() {
        for(int i=0;i<100;i++) evaluator.observe(raw("Vijay Nagar","Indore",null,clock.instant().minusSeconds(10),"LOCALITY"));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM search_alias_evidence",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM search_alias_candidate",Integer.class));
    }
    @Test void shadowCounterfactualExcludesProductionLearnedAliases() {
        cache.installCacheEntry("vijaynagr","indore","Nanda Nagar");
        long event=raw("Vijay Nagar","Indore","independent",clock.instant().minusSeconds(1),"LOCALITY");
        evaluator.observe(event);
        assertEquals("Vijay Nagar",jdbc.queryForObject("SELECT deterministic_target FROM search_alias_evidence WHERE event_id=?",String.class,event));
        assertEquals("Nanda Nagar",cache.resolveLocalityAlias("vijaynagr","indore"),"Observation has zero cache effect");
    }
    @Test void migrationPreservesHistoricalRowsAndBackfillsManualVeto() throws Exception {
        String history=schema+"_old";
        root.execute("CREATE SCHEMA "+history);
        var ds=new DriverManagerDataSource(System.getenv("PATHOME_V2_TEST_URL")+"?currentSchema="+history,
                System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME",System.getProperty("user.name")),
                System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD",""));
        var old=new JdbcTemplate(ds);
        try {
            old.execute("CREATE TABLE localities(id BIGSERIAL PRIMARY KEY,city VARCHAR(120),sector_name VARCHAR(120),created_at TIMESTAMP,UNIQUE(city,sector_name))");
            String[] locations=Arrays.stream(flyway.getConfiguration().getLocations()).map(org.flywaydb.core.api.Location::getDescriptor).toArray(String[]::new);
            var v16=Flyway.configure().dataSource(ds).defaultSchema(history).locations(locations).baselineOnMigrate(true).baselineVersion("14").target("16").load();
            v16.migrate();
            old.update("INSERT INTO search_alias(alias_term,entity_type,entity_value,entity_city,status) VALUES "
                    + "('vij','LOCALITY','Vijay Nagar','indore','ACTIVE'),('Vij','LOCALITY','Vijay Nagar','Indore','ACTIVE'),"
                    + "('manual','LOCALITY','Vijay Nagar','indore','DISABLED')");
            var v17=Flyway.configure().dataSource(ds).defaultSchema(history).locations(locations).load();
            assertEquals(1,v17.migrate().migrationsExecuted);v17.validate();
            assertEquals(3,old.queryForObject("SELECT count(*) FROM search_alias",Integer.class));
            assertEquals(2,old.queryForObject("SELECT count(*) FROM search_alias WHERE status='ACTIVE' AND policy_version IS NULL",Integer.class));
            assertEquals("MANUAL",old.queryForObject("SELECT disable_source FROM search_alias WHERE alias_term='manual'",String.class));
            assertEquals(0,v17.migrate().migrationsExecuted);
        } finally {root.execute("DROP SCHEMA "+history+" CASCADE");}
    }
    static class MutableClock extends Clock {
        Instant current;MutableClock(Instant i){current=i;}void advance(Duration d){current=current.plus(d);}
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return current;}
    }
}
