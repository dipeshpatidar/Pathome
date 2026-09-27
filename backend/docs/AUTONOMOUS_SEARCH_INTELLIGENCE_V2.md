# Autonomous Search Intelligence V2

Policy version: `AUTONOMOUS_V2`. Backend-only; deterministic search and its resolution priority are unchanged. No LLM, embeddings, ranking model, new canonical entities, or distributed cache.

## Baseline inspected

Checkpoint `ab7f2753e84890a68de65e19d61078a612a19c9f` has V15 telemetry, candidate, and alias tables. V16 seeds supported-city canonical localities. Actual legacy review thresholds are **10 events, 3 sessions, and 0.70 selection rate**. The hardcoded automatic-promotion flag is false. Scheduled aggregation formerly counted fuzzy/alias impressions in its denominator, accepted selections broadly, and counted missing session hashes as distinct `anon-<event id>` sessions. Its ingestion dedup cap is three events per session/term/hour; that check is not transactionally race-proof.

Legacy candidates progress from CANDIDATE to ELIGIBLE_FOR_REVIEW; an admin approves/rejects them. Approval creates an ACTIVE alias, and disable removes it from the in-process cache. Locality resolution order remains exact/prefix → active alias → fuzzy. Cache lookup is city-scoped first, then a legacy global fallback. V2 never creates global aliases.

The two legacy scheduled annotations are removed. Explicit legacy admin aggregation/review methods remain available, but exclude V2-owned candidates. V2 has its own bounded scheduler, using the same production alias table and cache. The old hardcoded flag describes only the retained legacy method, not V2's configurable autonomous policy.

## Evidence and lifecycle

`OBSERVED → EVIDENCE_BUILDING → SHADOW → ACTIVE → DEGRADED → AUTO_DISABLED → EVIDENCE_BUILDING`.

A candidate whose gates already pass at its first evaluation can enter SHADOW directly from OBSERVED. ACTIVE includes ongoing monitoring; MONITORED is not a contradictory separate flag. `MANUALLY_DISABLED` is a persistent veto. Existing `status` remains for legacy administrative compatibility; `lifecycle_state` is authoritative for V2. Losing initial gates during shadow resets to evidence building and clears shadow validation.

Every eligible raw event is processed once in a separate transaction. Only a `SUGGESTION_SELECTED` with a nonempty privacy-safe session hash, supported nonblank city, matching supplied city context, safe location-only parser output, and an existing city/locality target can enter the evidence ledger. Exact canonical spellings are not learned as redundant aliases. Stored client fuzzy scores, resolution-method claims, result counts and zero-result flags are not trusted evidence.

- **Strong:** canonical `ENTITY_MATCH`, `LOCALITY`, or inventory `SEARCH_QUERY` selections.
- **Moderate:** `QUERY_INTENT` selections only when the independently recomputed deterministic target agrees.
- **Weak:** a `QUERY_INTENT` selection without independent agreement; classified but not persisted as training evidence.
- **Ignored:** impressions, `SEARCH_EXECUTED` alone, `SEARCH_ANYWAY`, `UNSUPPORTED_CITY`, missing sessions, invalid targets, city mismatch and arbitrary zero-result reports.

The canonical locality schema has no active/deleted flag. Validity therefore means a current PostgreSQL row in the requested supported city; evaluation holds a shared row lock through the decision transaction. No locality or city is created. The existing CityRegistry controls supported-city membership; V2 does not register cities.

## Confidence and mandatory gates

Use the lower endpoint of the **two-sided 95% Wilson score interval**, with z = 1.959963984540054:

`lower = (p + z²/(2n) - z*sqrt(p*(1-p)/n + z²/(4n²))) / (1 + z²/n)`.

Here n is capped, scoped observations and p is target selections/n. Zero observations return zero. Invalid inputs fail closed. Supported confidence levels are 0.90, 0.95 and 0.99, with explicit normal quantiles and no new dependency. Default required lower bound: **0.85**.

Reference values: 3/3 = 0.4385029682; 30/30 = 0.8864866068; 300/300 = 0.9873570288; 15/30 = 0.3315412564. Thus small perfect samples do not establish trust. There is no weighted master score.

All initial gates must pass again at promotion: enabled/valid policy; LOCALITY allowlist; existing target and correct supported city; no deterministic conflict/ambiguity; no conflicting active alias; no manual veto; at least 30 effective observations; at least 30 independent session hashes; at least 48 hours of evidence age; at least four distinct six-hour buckets; target dominance at least 0.95; Wilson lower bound at least 0.85; maximum per-session share at most 0.10; largest one-hour bucket share at most 0.35.

Shadow additionally requires at least 30 fresh observations **and 30 sessions**, agreement at least 0.95, Wilson lower bound of shadow agreement at least 0.85, four time buckets, session-share and burst gates. Every decision returns explicit enum reason codes; latest decision is inspectable on the candidate.

## Caps, context and shadow

Effective contribution is capped at one observation per `(alias, entity, city, session)` within each evaluated window. PostgreSQL `row_number` performs the cap even if ingestion dedup races or direct inserts bypass ingestion. Competing targets share that cap; negative/competing selections take precedence within a session. Missing sessions never receive synthetic identities. Dominance and confidence use competing canonical selections in the denominator.

Learning windows are scoped by normalized alias/type/city. No Indore evidence can qualify a Pune or global alias. Largest fixed-hour bucket share, minimum age, independent-session minimum and distinct six-hour buckets jointly prevent rapid concentrated promotion.

Shadow evaluates **new selection events after shadow entry**, asynchronously, using `RentalLocationResolver` constructed with a null learning service. The deterministic resolver is therefore unable to read learned aliases for validation. Ambiguous prefixes also fail closed. SHADOW never inserts an active alias or changes cache/search results. It measures counterfactual interpretation agreement, not result quality or an online A/B experiment.

## Recent health, hysteresis and manual control

The validated evidence ledger and audit history retain historical evidence. Promotion uses a bounded 30-day learning window. Health uses a separate **seven-day window starting no earlier than activation**, so old successes cannot mask new deterioration. Capped competing selections are observable negatives; ordinary nonselection and zero results are not invented failures.

At least 20 recent observations and 20 sessions are required before ordinary health transitions. An ACTIVE alias falls to DEGRADED below 0.80 target selection rate and remains usable while degraded. Recovery requires at least 0.90. Auto-disable requires overall recent rate below 0.60, at least 24 hours degraded, and at least 20 new sessions since degradation whose rate is also below 0.60. Repeated evaluation of unchanged old bad evidence is insufficient. Invalid canonical data, deterministic contradiction or active-alias conflict bypass ordinary hysteresis and disable immediately for correctness.

AUTO_DISABLED remains disabled for seven days. After that it resets its evidence start and clears health/shadow history for decision purposes; all new promotion and shadow gates must pass again. Old ledger rows remain for auditing but cannot reactivate it.

Manual disable persists `disable_source=MANUAL`, locks matching candidates, and evicts the cache. A global manual disable blocks scoped autonomous promotion as well. Automatic evaluation never clears that veto. Existing explicit admin approval remains manual management. The admin-only `POST /api/v1/admin/search-learning/candidates/{id}/reset` accepts disabled candidates and authorizes fresh autonomous evidence/shadow collection; it does not activate an alias. Reset can transfer a disabled manually owned source alias into V2 ownership for safe later reuse. A separate unrelated/global veto is not silently cleared.

## Transactions, cache and failures

Each candidate evaluation holds a PostgreSQL transaction advisory lock keyed by normalized term/type (across city scopes to include global conflicts), then locks the candidate row. Manual approve/reject/disable/reset take the same lock. Existing V15 unique constraints and a normalized partial unique index on V2 aliases provide database-level duplicate protection. Event processing uses row locks with SKIP LOCKED and a unique evidence event ID. Repeated processing/evaluation is safe.

Cache updates occur after commit. The callback opens a fresh transaction, reacquires the same lock, rereads committed alias state, evicts that exact scoped entry and installs it only if ACTIVE. A delayed promotion callback cannot resurrect a later disable. Rollbacks never publish cache changes. Read/reconciliation failures evict the affected entry so deterministic/fuzzy fallback remains available. Manual cache refresh also rereads scopes under this coordination. The L1 remains a ConcurrentHashMap; no alternative V2 resolver is introduced.

Telemetry capture retains existing async exception isolation. The V2 evaluator is never called from search/autocomplete. Every event and candidate has an independent transaction and error boundary; batch exceptions are caught without affecting search. Invalid policy configuration fails closed. Each evaluation transaction has a 30-second infrastructure timeout.

`enabled=false` pauses autonomous event processing and every autonomous lifecycle mutation, including degradation and disable. Existing telemetry ingestion, deterministic search and cached safe aliases continue. Manual administrative actions remain available. Configuration uses normal Spring binding; changing environment/YAML requires application configuration reload/restart, not an invented live toggle.

## Complete configuration

All keys below have prefix `search.learning.autonomous.`. Defaults live in `AutonomousLearningProperties`; schedule placeholders use matching values. Application YAML exposes `enabled` via `SEARCH_LEARNING_AUTONOMOUS_ENABLED`.

- `enabled`: true
- `entity-allowlist`: LOCALITY (only supported autonomous entity)
- `minimum-evidence`: 30
- `minimum-unique-sessions`: 30
- `minimum-candidate-age`: PT48H
- `minimum-time-buckets`: 4
- `session-contribution-cap`: 1
- `evidence-window`: P30D
- `time-bucket`: PT6H
- `burst-window`: PT1H
- `minimum-target-dominance`: 0.95
- `confidence-level`: 0.95 (supported: 0.90, 0.95, 0.99)
- `minimum-confidence-lower-bound`: 0.85
- `minimum-shadow-evaluations`: 30 (also requires this many shadow sessions)
- `minimum-shadow-agreement`: 0.95
- `maximum-session-share`: 0.10
- `burst-threshold`: 0.35
- `minimum-health-evidence`: 20 (also requires this many health sessions)
- `recent-health-window`: P7D
- `degradation-threshold`: 0.80
- `disable-threshold`: 0.60
- `recovery-threshold`: 0.90
- `degradation-cooldown`: PT24H
- `reactivation-cooldown`: P7D
- `evaluation-batch-size`: 100 (each run processes up to 100 events and 100 candidates)
- `evaluation-schedule`: PT15M
- `initial-delay`: PT2M

Candidate scheduling orders by oldest evaluation timestamp, nulls first, then ID. Event processing scans unchecked IDs. There are no expensive request-path aggregations. Background SQL sorts capped windows and is not constant-time; its cost depends on observations within the configured window. No sub-millisecond claim is made for database work. Alias cache lookups retain expected O(1) complexity.

## V17 schema and audit

V15 and V16 are unchanged. V17 adds nullable candidate columns: `lifecycle_state`, `policy_version`, `state_since`, `evidence_since`, `shadow_since`, `health_since`, `evaluated_at`, `last_decision`; nullable alias columns `policy_version`, `disable_source`; and nullable event column `autonomous_checked_at`. The lifecycle CHECK allows only the seven explicit V2 states (or null for legacy rows). Existing disabled aliases are conservatively backfilled to MANUAL.

`search_alias_evidence` has event_id PK/FK to telemetry (cascade on telemetry deletion), alias_term, entity_type, city_key, target_value, session_hash, occurred_at, strength, deterministic_target and policy_version (default AUTONOMOUS_V2). CHECKs require LOCALITY, a nonempty city and STRONG/MODERATE strength. It stores no IP, raw User-Agent, contact/account identity or device fingerprint.

`search_alias_audit` has BIGSERIAL id, nullable candidate_id FK (SET NULL), alias_term, entity_type, canonical_target, city_key, from_state, to_state, event, source, policy_version, reasons, facts and occurred_at (default now). Automatic transitions persist capped learning/shadow/health facts, explicit dominance, confidence lower bound, shadow agreement and reason codes; administrative reset/disable record source MANUAL. Audit history is append-only through these services.

Indexes:

- `idx_sqe_autonomous_pending`: unchecked event IDs, bounded ingestion scan.
- `idx_sae_scope_window`: alias/type/city/time/session, window selection and session capping.
- `idx_sac_autonomous_evaluation`: evaluated_at NULLS FIRST/id for V2 rows, bounded scheduler selection.
- `uq_sa_autonomous_scope`: normalized term/type/city uniqueness for V2-owned aliases; historical case variants are preserved.
- `idx_sa_normalized_scope`: normalized alias/type/city lookup for mutation and conflict checks.
- `idx_sac_normalized_scope`: normalized candidate/type/city lookup and manual veto propagation.
- `idx_saa_candidate_time`: candidate audit chronology.

## Verification and limitations

Real PostgreSQL tests run only when PATHOME_V2_TEST_URL names a dedicated `_test` database. Each test creates and removes a private schema. Tests exercise actual SQL aggregation, Flyway, transactions, advisory/row locks, audit and real cache/resolver classes; listing/locality repository fixtures supply deterministic inventory and DB-backed canonical lookup. They do not claim a full deployed HTTP end-to-end proof.

Anonymous session hashes are not proof of distinct people: coordinated session rotation/Sybil attacks remain possible. Feedback has no server-issued signed suggestion receipt; canonical validation, conservative diversity gates and independent shadow agreement reduce but do not eliminate fabricated selections. Counterfactual shadow can only learn variants supported by independently available deterministic evidence; low-traffic/zero-inventory cases may never qualify. This is intentional conservatism.

Recent negatives require actual competing selections for the same normalized alias/city. The frontend does not provide a reliable linked correction or result-success signal, so no such signal is fabricated. Quiet aliases cannot be behaviorally assessed without evidence. Canonical deletion is detected by background reevaluation, not an immediate cross-service invalidation hook.

Cache invalidation is immediate on the node executing a mutation. Other application nodes can retain stale cache entries until refresh/restart; distributed invalidation is explicitly out of scope and requires approval before multi-node autonomous rollout. Timestamp columns preserve the existing local timestamp convention; application nodes must use a consistent timezone. Persistent failed events/candidates can consume bounded batch slots until the underlying failure is resolved. Evidence retention/partitioning is not added.

## Final verification — 27 September 2026

Sequential complete backend suite: **526 tests, 0 failures, 0 errors, 0 skipped; PASS**. `mvn clean compile`: PASS. Tests used an isolated PostgreSQL database containing only a schema clone/migration metadata plus controlled fixtures; development business data was not copied or migrated. No frontend tests/build or browser automation were run, as requested.

Suite counts (categories overlap where a lifecycle test verifies several properties):

- Pure policy: 26; statistics: 12; evidence taxonomy: 8.
- V2 real-PostgreSQL tests: 14. Within these, four dedicated anti-poisoning/context tests cover SQL session caps, missing sessions, bursts/age and competing targets/city isolation; three exercise shadow/fallback/cache independence; one covers the complete autonomous lifecycle; two cover concurrent evaluator/promotion-disable races; four cover committed activation/disable/rollback cache effects; one covers the kill switch; two specifically cover Flyway idempotency/historical-data preservation. All 14 fixtures also apply V15→V16→V17.
- Existing parser/resolver/controller regression suites: 70 (11 parser, 7 city resolution, 3 priority, 49 PropertyController tests; the controller suite includes nonsearch tests).
- Legacy search-learning service: 19; admin controller: 10; existing database lifecycle: 3.
- Remaining backend suites are included in the complete 526-test result.

The controlled fixture uses `vijaynagr`, Indore, Vijay Nagar. It asserts OBSERVED → EVIDENCE_BUILDING → SHADOW, fuzzy production behavior while shadowed, autonomous ACTIVE with the existing resolver reporting `alias`, and zero calls to `approveCandidate`. Twenty fresh competing selections cause DEGRADED; twenty additional independent negatives after the cooldown cause AUTO_DISABLED, cache eviction and the resolver returning `trigram` again. Separate tests prove manual disable veto and explicit fresh-evidence reset, including reuse of a previously manually owned alias.

An intermediate rerun was invalidated by accidentally overlapping Maven clean compilation with test execution, causing missing-class errors. This was a test-orchestration failure, not a V2 behavioral failure. Clean compilation completed successfully; the subsequent sequential full suite is the authoritative 526-test result. Earlier fixture issues and the V2 reset clock/ownership defects were fixed before that final run.

### Twenty-call baseline investigation

The baseline production method was already `getSearchSuggestions(String q, String city, String selectedCity, int limit)`. The production PropertyController file is byte-identical to checkpoint `ab7f2753e84890a68de65e19d61078a612a19c9f`; V2 did not change this signature or production search behavior.

A temporary archive of the exact baseline backend was compiled with `mvn test-compile`. It failed with the same 20 argument-count errors. Therefore those calls did **not** compile from that exact baseline in a clean test compilation. There is no evidence supporting an earlier clean pass; stale compiled artifacts or tests not being compiled could explain an earlier reported pass, but that history is not established.

Only `backend/src/test/java/com/indore/pathome/spaces/controller/PropertyControllerTest.java` required signature repairs: lines 1077, 1089, 1099, 1166, 1175, 1191, 1222, 1229, 1245, 1259, 1278, 1298, 1315, 1323, 1354, 1379, 1403, 1440, 1483, 1498. Each call adds `null` for the existing optional selectedCity parameter, preserving its query/city/limit and assertions. These changes remain necessary for compiling the requested regression suite. No production semantics were changed to accommodate tests.
