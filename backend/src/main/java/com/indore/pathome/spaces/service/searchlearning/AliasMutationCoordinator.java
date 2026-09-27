package com.indore.pathome.spaces.service.searchlearning;

import com.indore.pathome.spaces.service.SearchLearningService;
import java.util.Locale;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL transaction locks serialize ALL admin/autonomous writes for a normalized term/type,
 * including global aliases. Hash collisions only serialize unrelated work; they never weaken locking. */
@Component
public class AliasMutationCoordinator {
    private final JdbcTemplate jdbc;
    private final ObjectProvider<SearchLearningService> cache;
    private final TransactionTemplate fresh;
    private final Clock clock;
    @Autowired
    public AliasMutationCoordinator(JdbcTemplate jdbc, ObjectProvider<SearchLearningService> cache, PlatformTransactionManager tm) {
        this(jdbc, cache, tm, Clock.systemDefaultZone());
    }
    public AliasMutationCoordinator(JdbcTemplate jdbc, ObjectProvider<SearchLearningService> cache, PlatformTransactionManager tm, Clock clock) {
        this.jdbc = jdbc; this.cache = cache; this.clock = clock;
        fresh = new TransactionTemplate(tm);
        fresh.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public void lock(String term, String type) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Alias mutation requires transaction");
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> { }, normalize(term) + "|" + type);
    }
    public void lockCandidate(long id) {
        jdbc.query("SELECT candidate_term,canonical_entity_type FROM search_alias_candidate WHERE id=?",
                rs -> { if (rs.next()) lock(rs.getString(1), rs.getString(2)); return null; }, id);
    }
    public void lockAlias(long id) {
        jdbc.query("SELECT alias_term,entity_type FROM search_alias WHERE id=?",
                rs -> { if (rs.next()) lock(rs.getString(1), rs.getString(2)); return null; }, id);
    }
    public void syncAfterCommit(String term, String type, String city) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                // Re-read committed truth under the SAME lock. An older callback cannot resurrect a later disable.
                try {
                    fresh.executeWithoutResult(s -> {
                        lock(term, type);
                        SearchLearningService service = cache.getObject();
                        service.evictScopedCacheEntry(term, city);
                        jdbc.query("SELECT entity_value FROM search_alias WHERE lower(btrim(alias_term))=? AND entity_type=? "
                                        + "AND lower(btrim(coalesce(entity_city,'')))=? AND status='ACTIVE' ORDER BY id",
                                rs -> { if (rs.next()) service.installCacheEntry(term, city, rs.getString(1)); return null; },
                                normalize(term), type, normalize(city));
                    });
                } catch (RuntimeException failure) {
                    // A stale positive entry is worse than deterministic fallback.
                    cache.getObject().evictScopedCacheEntry(term, city);
                    org.slf4j.LoggerFactory.getLogger(AliasMutationCoordinator.class).warn("Alias cache reconciliation failed; entry evicted");
                }
            }
        });
    }
    public void manualDisable(long aliasId) {
        jdbc.update("UPDATE search_alias SET disable_source='MANUAL' WHERE id=?", aliasId);
        jdbc.update("UPDATE search_alias_candidate c SET lifecycle_state='MANUALLY_DISABLED',state_since=now() "
                + "FROM search_alias a WHERE a.id=? AND lower(btrim(c.candidate_term))=lower(btrim(a.alias_term)) "
                + "AND c.canonical_entity_type=a.entity_type AND (coalesce(a.entity_city,'')='' "
                + "OR lower(btrim(c.canonical_city))=lower(btrim(a.entity_city)))", aliasId);
        jdbc.update("INSERT INTO search_alias_audit(candidate_id,alias_term,entity_type,canonical_target,city_key,to_state,event,source,policy_version,reasons,facts) "
                + "SELECT source_candidate_id,alias_term,entity_type,entity_value,coalesce(entity_city,''),'MANUALLY_DISABLED',"
                + "'MANUAL_DISABLED','MANUAL','AUTONOMOUS_V2','MANUAL_DISABLE_LOCK','{}' FROM search_alias WHERE id=?", aliasId);
    }
    public void manualApproval(long candidateId, long aliasId) {
        jdbc.update("UPDATE search_alias SET disable_source=NULL,policy_version=NULL WHERE id=?", aliasId);
        jdbc.update("UPDATE search_alias_candidate SET lifecycle_state=NULL,policy_version=NULL WHERE id=?", candidateId);
    }
    public void manualReject(long candidateId) {
        jdbc.query("SELECT id,alias_term,entity_type,entity_city FROM search_alias WHERE source_candidate_id=?", rs -> {
            while (rs.next()) {
                long id=rs.getLong(1);
                jdbc.update("UPDATE search_alias SET status='DISABLED',disabled_at=now() WHERE id=?",id);
                manualDisable(id);
                syncAfterCommit(rs.getString(2),rs.getString(3),rs.getString(4));
            }
            return null;
        },candidateId);
        jdbc.update("UPDATE search_alias_candidate SET lifecycle_state='MANUALLY_DISABLED',state_since=now() WHERE id=?", candidateId);
    }

    /** Explicit admin reset requires fresh evidence and shadow; it never activates an alias. */
    public void reset(long candidateId) {
        lockCandidate(candidateId);
        int updated=jdbc.update("UPDATE search_alias_candidate SET status='CANDIDATE',lifecycle_state='EVIDENCE_BUILDING',"
                + "policy_version='AUTONOMOUS_V2',evidence_since=?,state_since=?,shadow_since=NULL,health_since=NULL,evaluated_at=NULL "
                + "WHERE id=? AND lifecycle_state IN ('MANUALLY_DISABLED','AUTO_DISABLED')",LocalDateTime.now(clock),LocalDateTime.now(clock),candidateId);
        if (updated!=1) throw new IllegalStateException("Only disabled candidates can be reset");
        jdbc.update("UPDATE search_alias SET disable_source=NULL,policy_version='AUTONOMOUS_V2' WHERE source_candidate_id=? AND status='DISABLED'",candidateId);
        jdbc.update("INSERT INTO search_alias_audit(candidate_id,alias_term,entity_type,canonical_target,city_key,to_state,event,source,policy_version,reasons,facts) "
                + "SELECT id,candidate_term,canonical_entity_type,canonical_entity_value,coalesce(canonical_city,''),'EVIDENCE_BUILDING',"
                + "'RESET_FOR_RELEARNING','MANUAL','AUTONOMOUS_V2','FRESH_EVIDENCE_REQUIRED','{}' FROM search_alias_candidate WHERE id=?",candidateId);
    }

    public void refreshCache() {
        // Include disabled scopes so manual refresh cannot resurrect a stale previously-active row.
        jdbc.query("SELECT DISTINCT alias_term,entity_type,entity_city FROM search_alias WHERE entity_type='LOCALITY'",rs -> {
            while(rs.next()) {
                String term=rs.getString(1),type=rs.getString(2),city=rs.getString(3);
                fresh.executeWithoutResult(s -> { lock(term,type); syncAfterCommit(term,type,city); });
            }
            return null;
        });
    }
    public static String normalize(String s) { return s == null ? "" : s.strip().toLowerCase(Locale.ROOT); }
}
