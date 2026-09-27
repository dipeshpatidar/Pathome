package com.indore.pathome.spaces.service.searchlearning;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.entity.SearchQueryEvent;
import com.indore.pathome.spaces.repository.ListingRepository;
import com.indore.pathome.spaces.repository.LocalityRepository;
import com.indore.pathome.spaces.service.CityRegistry;
import com.indore.pathome.spaces.service.RentalLocationResolver;
import com.indore.pathome.spaces.service.RentalSearchQuery;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.indore.pathome.spaces.service.searchlearning.AliasMutationCoordinator.normalize;
import static com.indore.pathome.spaces.service.searchlearning.AutonomousLearningProperties.POLICY_VERSION;

/** Bounded background work. Each event and candidate owns a separate transaction and failure boundary. */
@Service
public class AutonomousAliasEvaluator {
    private static final Logger log = LoggerFactory.getLogger(AutonomousAliasEvaluator.class);
    private final JdbcTemplate jdbc;
    private final AutonomousLearningProperties p;
    private final LocalityRepository localities;
    private final RentalLocationResolver deterministic;
    private final AliasMutationCoordinator mutations;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Autowired
    public AutonomousAliasEvaluator(JdbcTemplate jdbc, AutonomousLearningProperties p, LocalityRepository localities,
            ListingRepository listings, AliasMutationCoordinator mutations, PlatformTransactionManager tm) {
        this(jdbc, p, localities, listings, mutations, tm, Clock.systemDefaultZone());
    }
    public AutonomousAliasEvaluator(JdbcTemplate jdbc, AutonomousLearningProperties p, LocalityRepository localities,
            ListingRepository listings, AliasMutationCoordinator mutations, PlatformTransactionManager tm, Clock clock) {
        this.jdbc = jdbc; this.p = p; this.localities = localities; this.mutations = mutations;
        this.deterministic = new RentalLocationResolver(listings, null, localities);
        this.tx = new TransactionTemplate(tm); this.clock = clock;
        tx.setTimeout(30);
    }

    @Scheduled(fixedDelayString = "${search.learning.autonomous.evaluation-schedule:PT15M}",
            initialDelayString = "${search.learning.autonomous.initial-delay:PT2M}")
    public void runBatch() {
        if (!p.isEnabled()) return;
        try {
            p.validate();
            var ids = jdbc.queryForList("SELECT id FROM search_query_event WHERE autonomous_checked_at IS NULL ORDER BY id LIMIT ?",
                    Long.class, p.getEvaluationBatchSize());
            for (long id : ids) isolated("event", id, () -> observe(id));
            var candidates = jdbc.queryForList("SELECT id FROM search_alias_candidate WHERE policy_version=? "
                            + "ORDER BY evaluated_at NULLS FIRST,id LIMIT ?", Long.class, POLICY_VERSION, p.getEvaluationBatchSize());
            for (long id : candidates) isolated("candidate", id, () -> evaluate(id));
        } catch (RuntimeException ex) {
            log.warn("Autonomous alias batch failed; deterministic search unaffected ({})", ex.getClass().getSimpleName());
        }
    }
    private void isolated(String kind, long id, Runnable action) {
        try { action.run(); }
        catch (RuntimeException ex) { log.warn("Autonomous alias {} id={} failed ({})", kind, id, ex.getClass().getSimpleName()); }
    }
    public void observe(long id) {
        if (!p.isEnabled()) return;
        p.validate();
        tx.executeWithoutResult(status -> {
            var events = jdbc.query("SELECT * FROM search_query_event WHERE id=? AND autonomous_checked_at IS NULL FOR UPDATE SKIP LOCKED",
                    (rs, n) -> event(rs), id);
            if (events.isEmpty()) return;
            SearchQueryEvent e = events.get(0);
            if (eligibleEvent(e)) persistEvidence(id, e);
            jdbc.update("UPDATE search_query_event SET autonomous_checked_at=? WHERE id=?", now(), id);
        });
    }
    private boolean eligibleEvent(SearchQueryEvent e) {
        return "SUGGESTION_SELECTED".equals(e.getEventType()) && e.getSessionHash() != null && !e.getSessionHash().isBlank()
                && e.getLocationCandidate() != null && e.getLocationCandidate().length() <= 100
                && e.getResolvedLocality() != null && e.getResolvedCity() != null
                && CityRegistry.isCitySupported(e.getResolvedCity())
                && (e.getCityInput() == null || e.getCityInput().isBlank() || normalize(e.getCityInput()).equals(normalize(e.getResolvedCity())))
                && !e.getOccurredAt().isBefore(now().minus(p.getEvidenceWindow())) && !e.getOccurredAt().isAfter(now());
    }
    private void persistEvidence(long eventId, SearchQueryEvent e) {
        String term = RentalSearchQuery.normalizeLocation(e.getLocationCandidate());
        RentalSearchQuery parsed;
        try { parsed = RentalSearchQuery.parse(term); } catch (IllegalArgumentException invalid) { return; }
        if (term.length() < 2 || !term.equals(RentalSearchQuery.normalizeLocation(parsed.location())) || parsed.explicitCity() != null) return;
        String city = normalize(e.getResolvedCity());
        var target = localities.findByCityIgnoreCaseAndSectorNameIgnoreCase(city, e.getResolvedLocality());
        if (target.isEmpty() || normalize(target.get().getSectorName()).equals(term)) return;
        String canonical = target.get().getSectorName();
        var resolution = counterfactual(parsed, city);
        String deterministicTarget = city.equals(normalize(resolution.city())) ? resolution.locality() : null;
        // Zero-inventory exact canonical selections may be verified without inventing inventory success.
        if (deterministicTarget == null) deterministicTarget = deterministic.resolveCanonicalLocalityName(term, city);
        boolean agreement = normalize(canonical).equals(normalize(deterministicTarget));
        var strength = AliasEvidenceClassifier.classify(e, true, agreement);
        if (strength != AliasEvidenceClassifier.Strength.STRONG && strength != AliasEvidenceClassifier.Strength.MODERATE) return;
        mutations.lock(term, "LOCALITY");
        jdbc.update("INSERT INTO search_alias_evidence(event_id,alias_term,entity_type,city_key,target_value,session_hash,occurred_at,strength,deterministic_target) "
                        + "VALUES (?,?,'LOCALITY',?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                eventId, term, city, canonical, e.getSessionHash(), e.getOccurredAt(), strength.name(), deterministicTarget);
        // Reuse a case-insensitive existing candidate; historical manual decisions are never adopted.
        var existing = jdbc.queryForList("SELECT id FROM search_alias_candidate WHERE lower(btrim(candidate_term))=? "
                + "AND canonical_entity_type='LOCALITY' AND lower(btrim(canonical_city))=? AND lower(btrim(canonical_entity_value))=? ORDER BY id LIMIT 1",
                Long.class, term, city, normalize(canonical));
        if (existing.isEmpty()) {
            Long id = jdbc.queryForObject("INSERT INTO search_alias_candidate(candidate_term,canonical_entity_type,canonical_entity_value,canonical_city,"
                    + "status,lifecycle_state,policy_version,state_since,evidence_since,first_seen_at,last_seen_at) "
                    + "VALUES (?,'LOCALITY',?,?,'CANDIDATE','OBSERVED',?,?,?,?,?) RETURNING id", Long.class,
                    term, canonical, city, POLICY_VERSION, now(), e.getOccurredAt(), e.getOccurredAt(), e.getOccurredAt());
            audit(id, term, canonical, city, null, "OBSERVED", "CANDIDATE_CREATED", "AUTO", "[]", "{}");
        } else {
            int adopted = jdbc.update("UPDATE search_alias_candidate SET lifecycle_state='OBSERVED',policy_version=?,state_since=?,evidence_since=? "
                    + "WHERE id=? AND policy_version IS NULL AND lifecycle_state IS NULL AND status IN ('CANDIDATE','ELIGIBLE_FOR_REVIEW')",
                    POLICY_VERSION, now(), e.getOccurredAt(), existing.get(0));
            if (adopted > 0) audit(existing.get(0), term, canonical, city, null, "OBSERVED", "CANDIDATE_CREATED", "AUTO", "[]", "{}");
        }
    }

    private RentalLocationResolver.Resolution counterfactual(RentalSearchQuery query, String city) {
        var resolution = deterministic.resolveForDiscovery(query, city);
        if ("prefix".equals(resolution.method())) {
            var alternatives = deterministic.suggestions(query, city, 3);
            if (alternatives.stream().map(m -> normalize(m.city()) + "|" + normalize(m.locality())).distinct().count() > 1)
                return new RentalLocationResolver.Resolution(null, null, 0, "ambiguous");
        }
        return resolution;
    }

    public void evaluate(long id) {
        if (!p.isEnabled()) return;
        p.validate();
        tx.executeWithoutResult(status -> {
            mutations.lockCandidate(id);
            var rows = jdbc.query("SELECT * FROM search_alias_candidate WHERE id=? AND policy_version=? FOR UPDATE",
                    (rs, n) -> candidate(rs), id, POLICY_VERSION);
            if (rows.isEmpty()) return;
            Candidate c = rows.get(0);
            var aliases = jdbc.query("SELECT id,entity_value,status,disable_source,source_candidate_id,policy_version FROM search_alias "
                            + "WHERE lower(btrim(alias_term))=? AND entity_type=? AND (lower(btrim(coalesce(entity_city,'')))=? OR coalesce(entity_city,'')='')",
                    (rs,n) -> new ExistingAlias(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4),
                            rs.getObject(5,Long.class),rs.getString(6)), normalize(c.term), c.type, normalize(c.city));
            boolean locked = aliases.stream().anyMatch(a -> "MANUAL".equals(a.disableSource))
                    || List.of("REJECTED","DISABLED").contains(c.legacyStatus);
            boolean conflict = aliases.stream().anyMatch(a -> "ACTIVE".equals(a.status)
                    && (!Objects.equals(a.candidateId,c.id) || !POLICY_VERSION.equals(a.policyVersion) || !normalize(a.target).equals(normalize(c.target))));
            boolean valid = p.getEntityAllowlist().contains(c.type) && !normalize(c.city).isBlank()
                    && CityRegistry.isCitySupported(c.city)
                    && !jdbc.queryForList("SELECT id FROM localities WHERE lower(btrim(city))=? AND lower(btrim(sector_name))=? FOR SHARE",
                            Long.class,normalize(c.city),normalize(c.target)).isEmpty();
            // Exact/prefix canonical authority cannot be overridden; fuzzy disagreement blocks new promotion.
            boolean deterministicConflict = false;
            if (valid) {
                var query = RentalSearchQuery.parse(c.term);
                var resolved = counterfactual(query,normalize(c.city));
                deterministicConflict = resolved.locality()!=null && (!normalize(resolved.locality()).equals(normalize(c.target))
                        || !normalize(resolved.city()).equals(normalize(c.city))) || "ambiguous".equals(resolved.method());
                var canonical = localities.findByCityIgnoreCaseAndSectorNameIgnoreCase(c.city,c.term);
                deterministicConflict |= canonical.isPresent() && !normalize(canonical.get().getSectorName()).equals(normalize(c.target));
            }
            LocalDateTime n = now(), learningSince = later(c.evidenceSince,n.minus(p.getEvidenceWindow()));
            var learning = evidence(c,learningSince,n);
            var shadow = c.shadowSince == null ? AliasEvidenceSnapshot.Evidence.empty() : evidence(c,later(c.shadowSince,learningSince),n);
            var health = c.healthSince == null ? AliasEvidenceSnapshot.Evidence.empty() : evidence(c,later(c.healthSince,n.minus(p.getRecentHealthWindow())),n);
            var degraded = c.state == AliasLifecycle.DEGRADED ? evidence(c,later(c.stateSince,n.minus(p.getRecentHealthWindow())),n) : AliasEvidenceSnapshot.Evidence.empty();
            Timestamp first = jdbc.queryForObject("SELECT min(occurred_at) FROM search_alias_evidence WHERE alias_term=? AND entity_type=? "
                    + "AND city_key=? AND occurred_at>=? AND occurred_at<=?", Timestamp.class, normalize(c.term),c.type,normalize(c.city),learningSince,n);
            var snapshot = new AliasEvidenceSnapshot(c.state,c.type,valid,deterministicConflict,conflict,locked,
                    instant(n),first==null?null:instant(first.toLocalDateTime()),instant(c.stateSince),learning,shadow,health,degraded);
            AliasPolicyDecision decision = new AutonomousAliasPolicy(p).evaluate(snapshot);
            apply(c,decision,snapshot,aliases,n);
        });
    }

    private AliasEvidenceSnapshot.Evidence evidence(Candidate c, LocalDateTime since, LocalDateTime until) {
        // Cap ACROSS competing targets, not separately per target. Conflicts take precedence within each session.
        String sql = """
            WITH ranked AS (
                SELECT *, row_number() OVER (PARTITION BY session_hash ORDER BY
                    CASE WHEN lower(btrim(target_value))=? THEN 1 ELSE 0 END, occurred_at DESC,event_id DESC) AS contribution
                FROM search_alias_evidence WHERE alias_term=? AND entity_type=? AND city_key=? AND occurred_at>=? AND occurred_at<=?
            ), capped AS (SELECT * FROM ranked WHERE contribution<=?), sessions AS (
                SELECT count(*) n FROM capped GROUP BY session_hash
            ), bursts AS (SELECT count(*) n FROM capped GROUP BY floor(extract(epoch FROM occurred_at)/?))
            SELECT count(*) observations,
                count(*) FILTER (WHERE lower(btrim(target_value))=?) successes,
                count(DISTINCT session_hash) sessions,
                count(DISTINCT floor(extract(epoch FROM occurred_at)/?)) buckets,
                coalesce((SELECT max(n) FROM sessions),0) max_session,
                coalesce((SELECT max(n) FROM bursts),0) max_burst,
                count(*) FILTER (WHERE lower(btrim(target_value))=? AND lower(btrim(deterministic_target))=?) agreements
            FROM capped
            """;
        return jdbc.queryForObject(sql, (rs,n) -> {
            long total=rs.getLong("observations");
            return new AliasEvidenceSnapshot.Evidence(total,rs.getLong("successes"),rs.getLong("sessions"),rs.getLong("buckets"),
                    total==0?0:(double)rs.getLong("max_session")/total,total==0?0:(double)rs.getLong("max_burst")/total,rs.getLong("agreements"));
        }, normalize(c.target),normalize(c.term),c.type,normalize(c.city),since,until,p.getSessionContributionCap(),
                p.getBurstWindow().toSeconds(),normalize(c.target),p.getTimeBucket().toSeconds(),normalize(c.target),normalize(c.target));
    }

    private void apply(Candidate c, AliasPolicyDecision d, AliasEvidenceSnapshot s, List<ExistingAlias> aliases, LocalDateTime now) {
        if (d.action()==AliasPolicyDecision.Action.PAUSED) return;
        String facts=toJson(Map.of("snapshot",s,"confidenceLowerBound",d.confidenceLowerBound(),
                "targetDominance",s.learning().successRate(),"shadowAgreement",s.shadow().agreementRate())), reasons=toJson(d.reasons());
        jdbc.update("UPDATE search_alias_candidate SET evaluated_at=?,last_decision=?,evidence_count=?,unique_session_count=?,"
                + "successful_selection_count=?,selection_rate=? WHERE id=?",now,toJson(d),s.learning().observations(),
                s.learning().sessions(),s.learning().successes(),s.learning().successRate(),c.id);
        if (d.nextState()==c.state) return;
        if (d.action()==AliasPolicyDecision.Action.PROMOTE) {
            // Guard and DB uniqueness cover both repeated evaluation and competing candidates.
            var own=aliases.stream().filter(a -> Objects.equals(a.candidateId,c.id) && POLICY_VERSION.equals(a.policyVersion)).findFirst();
            if (own.isPresent()) jdbc.update("UPDATE search_alias SET status='ACTIVE',disabled_at=NULL,disabled_reason=NULL,disable_source=NULL,confidence=? WHERE id=?",
                    d.confidenceLowerBound(),own.get().id);
            else jdbc.update("INSERT INTO search_alias(alias_term,entity_type,entity_value,entity_city,confidence,source_candidate_id,status,policy_version) "
                    + "VALUES (?,?,?,?,?,?,'ACTIVE',?)",normalize(c.term),c.type,c.target,normalize(c.city),d.confidenceLowerBound(),c.id,POLICY_VERSION);
            jdbc.update("UPDATE search_alias_candidate SET promoted_at=?,promoted_by='AUTO',promotion_evidence=?,health_since=? WHERE id=?",now,facts,now,c.id);
            mutations.syncAfterCommit(c.term,c.type,c.city);
        }
        if (d.action()==AliasPolicyDecision.Action.AUTO_DISABLE || d.action()==AliasPolicyDecision.Action.BLOCKED_MANUAL_DISABLE) {
            jdbc.update("UPDATE search_alias SET status='DISABLED',disabled_at=?,disabled_reason=?,disable_source=? "
                            + "WHERE source_candidate_id=? AND policy_version=? AND status='ACTIVE'",
                    now,d.reasons().get(0).name(),d.action()==AliasPolicyDecision.Action.AUTO_DISABLE?"AUTO":"MANUAL",c.id,POLICY_VERSION);
            mutations.syncAfterCommit(c.term,c.type,c.city);
        }
        if (d.action()==AliasPolicyDecision.Action.RESET_FOR_RELEARNING) {
            jdbc.update("UPDATE search_alias_candidate SET evidence_since=?,shadow_since=NULL,health_since=NULL WHERE id=?",now,c.id);
        }
        if (d.action()==AliasPolicyDecision.Action.ENTER_SHADOW) jdbc.update("UPDATE search_alias_candidate SET shadow_since=? WHERE id=?",now,c.id);
        if (c.state==AliasLifecycle.SHADOW && d.nextState()==AliasLifecycle.EVIDENCE_BUILDING)
            jdbc.update("UPDATE search_alias_candidate SET shadow_since=NULL WHERE id=?",c.id);
        jdbc.update("UPDATE search_alias_candidate SET lifecycle_state=?,state_since=?,updated_at=? WHERE id=?",d.nextState().name(),now,now,c.id);
        String event=switch(d.action()) {
            case ENTER_SHADOW -> "ENTERED_SHADOW"; case PROMOTE -> "AUTO_PROMOTED";
            case DEGRADE -> "DEGRADED"; case RECOVER -> "RECOVERED"; case AUTO_DISABLE -> "AUTO_DISABLED";
            case BLOCKED_MANUAL_DISABLE -> "MANUAL_DISABLED"; default -> d.action().name();
        };
        audit(c.id,c.term,c.target,c.city,c.state.name(),d.nextState().name(),event,"AUTO",reasons,facts);
        log.info("Alias lifecycle candidateId={} entity={} city={} from={} to={} reasons={} policy={}",c.id,c.type,c.city,c.state,d.nextState(),d.reasons(),POLICY_VERSION);
    }
    private void audit(long id,String term,String target,String city,String from,String to,String event,String source,String reasons,String facts) {
        jdbc.update("INSERT INTO search_alias_audit(candidate_id,alias_term,entity_type,canonical_target,city_key,from_state,to_state,event,source,policy_version,reasons,facts,occurred_at) "
                + "VALUES (?,?,'LOCALITY',?,?,?,?,?,?,?,?,?,?)",id,term,target,city,from,to,event,source,POLICY_VERSION,reasons,facts,now());
    }
    private String toJson(Object value) {
        try { return json.writeValueAsString(value); } catch (JsonProcessingException ex) { throw new IllegalStateException("Cannot encode decision",ex); }
    }
    private LocalDateTime now() { return LocalDateTime.now(clock); }
    private static Instant instant(LocalDateTime t) { return t.toInstant(ZoneOffset.UTC); }
    private static LocalDateTime later(LocalDateTime a,LocalDateTime b) { return a==null || a.isBefore(b)?b:a; }
    private static LocalDateTime time(ResultSet rs,String name) throws SQLException {
        Timestamp t=rs.getTimestamp(name); return t==null?null:t.toLocalDateTime();
    }
    private Candidate candidate(ResultSet rs) throws SQLException {
        return new Candidate(rs.getLong("id"),rs.getString("candidate_term"),rs.getString("canonical_entity_type"),
                rs.getString("canonical_entity_value"),rs.getString("canonical_city"),AliasLifecycle.valueOf(rs.getString("lifecycle_state")),
                time(rs,"state_since"),time(rs,"evidence_since"),time(rs,"shadow_since"),time(rs,"health_since"),rs.getString("status"));
    }
    private SearchQueryEvent event(ResultSet rs) throws SQLException {
        SearchQueryEvent e=new SearchQueryEvent(); e.setOccurredAt(time(rs,"occurred_at")); e.setEventType(rs.getString("event_type"));
        e.setLocationCandidate(rs.getString("location_candidate")); e.setCityInput(rs.getString("city_input"));
        e.setResolvedCity(rs.getString("resolved_city")); e.setResolvedLocality(rs.getString("resolved_locality"));
        e.setSessionHash(rs.getString("session_hash")); e.setSelectedType(rs.getString("selected_type")); return e;
    }
    private record Candidate(long id,String term,String type,String target,String city,AliasLifecycle state,LocalDateTime stateSince,
            LocalDateTime evidenceSince,LocalDateTime shadowSince,LocalDateTime healthSince,String legacyStatus) {}
    private record ExistingAlias(long id,String target,String status,String disableSource,Long candidateId,String policyVersion) {}
}
