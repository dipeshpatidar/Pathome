# PATHOME — P1 LEAD QUEUE CORRECTION & GENUINE VERIFICATION HANDOFF

Date: 22 October 2026, IST

**Decision: the approved Lead-queue correction PASSES its complete queue-focused matrix and is ready for one focused independent Sol review. The broader P1 handoff is NOT READY for D-B acceptance because required adjacent case R05 exposed a separate production defect in duplicate-candidate lookup. That source is unchanged because the approved boundary permits only the eight queue guard edits.**

## 1. Repository baseline and preservation

- Repository root: `/Users/dipeshpatidar/Documents/Pathome-tenant-ui-v0`.
- Branch: `feature/phase2a-oe-ui-integration`.
- HEAD: `a943775e07a153087d0b38c315db20e18b30aec5`.
- Initial worktree: 25 tracked modifications and 68 untracked files, preserving the uncommitted D-B/V53 implementation and evidence.
- Initial index: empty.
- Final branch and HEAD are unchanged. No stage, commit, push, fetch, merge, branch switch, reset, stash, rebase or history rewrite occurred.
- The initial production fingerprint `LeadRoutingQueueRepository.java` was `db3c8f409c824555293657fdb45a3cbddc2c627ae27614ab315411b6c4cfb1d9`.
- The initial test fingerprint `LeadHttpPostgresTest.java` was `3ee78028da34b74954758ab76493ec85ae2f419b014eb9fbf5df2187afb88f57`.
- Unchanged D-B source fingerprints remain `LeadService.java` `9e868d8cd666a2ae598f9c2d5a3237e11b011e996da7ae5caf766a0fc41174ac`, `LeadContract.java` `e4a65e6815e31f114961b60b0abfc259c1f82e5f70f64fc7351b49ce448d3aa6`, `LeadShareService.java` `6b09ee22a8a45d0f6b54120a65a74ff3ea9b7017e6f019a31fcd6adaef43d7c1`, `LeadShortlistService.java` `cfed9cce8422f77dd7c5aa58d1d270c9100b50d884b1e1e850927b5cf0843321`, and V53 `15b856ad6fe8d5f2c999617f255b546b30a457d7ac2a9270bcca7db739d640d3`.

## 2. Production correction

Exactly eight lines changed in `findVisibleLeadPage`:

```hql
and (cast(:cityId as long) is null or lead.supportedCity.id = :cityId)
and (cast(:teamId as long) is null or lead.operatingTeam.id = :teamId)
and (cast(:status as string) is null or lead.status = :status)
and (:ownerMe = false or lead.owner.id = :actorId)
and (cast(:followUpBefore as timestamp) is null or lead.followUpAt < :followUpBefore)
```

The same four nullable guards were changed once in SELECT and once in COUNT. Nothing else in production changed.

- Final repository SHA-256: `eb0ac7d7852c779e795cdc56275e49b24170affdef6f512842b817029d8df2a7`.
- Final queue test SHA-256: `aca70daa13d985d8c03e1359e8f00232dcc5fe3d8e503abbcbbc5a3027236816`.
- Final controller test SHA-256: `4dcf854d53e1d40f8ff303792d6125a2afc42c2f57e30a278b569aa176bb2def`.
- Reversing exactly those eight guards in an external copy reproduced the initial source byte hash `db3c8f409c824555293657fdb45a3cbddc2c627ae27614ab315411b6c4cfb1d9`.

## 3. Preserved contract

- Authenticated `User.id`, active `EmployeeProfile`, live effective grants and canonical City/Team relations remain authoritative.
- Coordinator radius remains exact owner + effective Team authority. Same-Team peers do not inherit owner visibility.
- Supervisor scope, City-intake scope, City-routing scope and global Staff Admin diagnostics remain unchanged.
- `owner=me` remains a narrowing filter, never an authorization source.
- SQL scope still occurs before pagination and COUNT. Ordering remains `createdAt DESC, id DESC`; maximum page size remains 100.
- Summary masking, generic `Lead not found`, no-store responses, API/DTO/entity/schema contracts, enum mapping, `Instant` values and strict `<` follow-up comparison are unchanged.
- `findDuplicateCandidates` production source is unchanged. Its separate runtime failure is documented below.

## 4. PostgreSQL safety and execution

Fresh proof was taken before the positive cohort and each isolated mutant cohort.

- Host/port: loopback `127.0.0.1:5432`.
- Database: `pathome_oe_test_integration`.
- Authenticated role: `pathome`.
- PostgreSQL: 14.14.
- Sentinel: exactly one `DISPOSABLE` row.
- Run identity: `20261013T124820-98553`.
- Each run created one UUID-owned `p4_crm_http_test_*` schema, applied the supported legacy baseline → V52 → V53, started Spring/Hibernate, and dropped only that schema.
- Final owned-schema count: `0`.
- Sentinel remained exactly `1`.
- `pathome_db`, the development reset path, provider activation and real customer/WhatsApp/Meta traffic were never accessed.

Actual generated SQL contained:

```sql
cast(? as bigint) is null or ...supported_city_id=?
cast(? as bigint) is null or ...operating_team_id=?
cast(? as text) is null or ...status=?
cast(? as timestamp(6)) is null or ...follow_up_at<?
```

SELECT and actual `select count(lrq1_0.id)` statements were both captured and executed against PostgreSQL. No SQLState `42P18` occurred in any required queue case.

Raw evidence: `tools/oe-integration-env/evidence/p1-lead-queue-correction-20261022/`.

## 5. Queue verification result

Final shared-source class:

```text
LeadHttpPostgresTest: 18 tests, 17 PASS, 1 FAIL, 0 errors, 0 skipped
```

The only failure is R05, outside the eight-line production delta.

External corrected-source N02:

```text
17 tests, 17 PASS, 0 failures, 0 errors, 0 skipped
```

Focused non-database regression cohort:

```text
LeadControllerTest          5/5 PASS
LeadAccessServiceTest       6/6 PASS
LeadServiceTest             1/1 PASS
LeadContractTest            7/7 PASS
Total                      19/19 PASS
```

### Q01–Q10 — nullable filters and input

| Case | Result | Evidence |
|---|---|---|
| Q01 all filters omitted | PASS | Owner page and peer zero-count both return 200. |
| Q02 one filter independently | PASS | City-only, Team-only, status-only and follow-up-only masks restrict only their intended set. |
| Q03 omit one from all supplied | PASS | Covered by the 16-mask matrix. |
| Q04 all supplied, owner omitted/`me` | PASS | Exact owner intersection; peer remains empty. |
| Q05 all 16 presence masks | PASS | Every mask returned the independently computed IDs/total; representative full pages forced COUNT. |
| Q06 City/Team combinations | PASS | City-only, Team-only, consistent pair, mismatched pair and nonexistent IDs remain truthful. |
| Q07 all eight stored statuses | PASS | NEW, QUALIFYING, QUALIFIED, CLOSED, ASSIGNED, UNMAPPED, CONTACTED and STALE read back correctly; lowercase and blank behavior preserved. |
| Q08 invalid syntax/ranges | PASS | Zero, negative, fractional, malformed/overflow IDs, invalid status/time/page, size 0/101 all return 400/no-store. |
| Q09 bigint above 32-bit | PASS | Matching City and Team IDs `3000000000`/`3000000001` are visible; N03 proves an integer mutant fails. |
| Q10 blank/owner parsing | PASS | Blank optional values clear filters; `owner=me` narrows; another owner string does not grant actor selection. |

### S01–S12 — scope and privacy

| Case | Result | Evidence |
|---|---|---|
| S01 exact owner | PASS | Exact owned ID/count only. |
| S02 same-Team peer | PASS | Peer queue is empty; foreign detail/activity are generic 404; peer leader is concealed. |
| S03 owner in unauthorized Team | PASS | Ownership without effective Team membership remains invisible. |
| S04 City-intake City boundary | PASS | City A sees only City A unassigned rows; explicit City B returns zero. |
| S05 supervisor Team boundary | PASS | Supervisor sees authorized Team leads only and retains no owner contact/edit privilege. |
| S06 OPS_INTAKE/CITY_TEAM_ADMIN | PASS | Both retain approved unassigned City scope, including null Team/owner cases. |
| S07 global versus role label | PASS | STAFF_ADMIN sees the authorized diagnostic rows; a label-only actor receives 403. |
| S08 revocation/expiry/deactivation | PASS | Fresh requests lose revoked/deactivated scope; a different valid actor remains authorized. |
| S09 combined grants | PASS | Correct intake/coordinator/supervisor union; excluded Team remains absent and filters still intersect. |
| S10 ownership transfer | PASS | Old owner loses the reassigned row; new owner sees it on a fresh list/count. |
| S11 privacy | PASS | Queue summaries are masked; denied detail/activity bodies and errors contain no synthetic full phone or `42P18`. |
| S12 actor switching/auth | PASS | User A → User B → User A and unauthenticated boundaries remain isolated; no scoped result reuse. |

### P01–P05 — pagination and forced COUNT

| Case | Result | Evidence |
|---|---|---|
| P01 zero/one/multiple | PASS | Independently known totals, including zero pages for empty results. |
| P02 201 tied rows | PASS | IDs remain stable via `createdAt DESC, id DESC`; size 1, 2, 100, later full page and terminal page verified. |
| P03 forced COUNT | PASS | Full first and later pages emitted actual PostgreSQL COUNT; terminal page was also exercised. |
| P04 SELECT/COUNT equivalence | PASS | Excluded same-Team and foreign-Team rows do not alter owner totals; N07b produces a wrong count. |
| P05 fresh ownership data | PASS | Reassignment changes subsequent owner/peer totals before offset/limit. |

### T01–T05 — time and mapped values

| Case | Result | Evidence |
|---|---|---|
| T01 null/non-null follow-up | PASS | Null rows remain visible only when cutoff is omitted. |
| T02 strict microsecond boundary | PASS | Persisted `.123455Z`, `.123456Z`, `.123457Z`; only strictly before is returned. |
| T03 offset/session timezone | PASS | UTC and `+05:30` are identical; service reads under UTC and Asia/Kolkata session settings return the same IDs. |
| T04 persisted precision | PASS | Assertions derive from actual PostgreSQL `timestamptz` readback. |
| T05 mapped enum | PASS | Reuses Q07/Q08; no enum or status migration. |

### R01–R05 — recovery and integration

| Case | Result | Evidence |
|---|---|---|
| R01 original reproductions | PASS | Owner/peer queue now return genuine 200 with exact scoped data; repeated GETs create no Lead/activity/receipt/source mutation. |
| R02 response contract | PASS | Success, 400, 401 and concealed responses preserve no-store; unexpected service failure is not converted to an empty page. |
| R03 revocation recovery | PASS | Revoked actor loses access; independent actor remains successful. |
| R04 detail/activity preservation | PASS | Current owner detail remains 200; same-Team peer detail/activity remain generic 404. |
| R05 duplicate-candidate lookup | **FAIL** | Endpoint returns 500 before candidate SQL; see section 7. |

## 6. Negative controls

| Control | Result | Intended discriminator |
|---|---|---|
| N01 original untyped source | RETAINED RED | Existing genuine evidence: 2 failures, SQLState `42P18`, parameter `$15`; baseline source hash matched, so not rerun. |
| N02 external corrected source | GREEN | 17/17 queue and retained D-B methods pass with forced SELECT/COUNT. |
| N03 City guard `integer` | KILLED | Matching ID above 2³¹−1 fails with PostgreSQL `integer out of range`; report `TEST-N03-LeadHttpPostgresTest.xml`. |
| N04 coordinator owner removed | KILLED | Peer-owned row/count becomes visible; expected `0`, actual `1`. |
| N05 City-intake membership removed | KILLED | Foreign-City unassigned row becomes visible; expected `2`, actual `3`. |
| N06a coordinator Team removed | KILLED | Actor-owned Lead in a foreign Team becomes visible; expected `0`, actual `1`. |
| N06b supervisor Team removed | KILLED | Supervisor's unfiltered set expands to a foreign Team; expected `1`, actual `2`. |
| N07a SELECT typed / COUNT untyped | KILLED | Full-page request reproduces exact COUNT `42P18` and HTTP 500. |
| N07b COUNT owner predicate removed | KILLED | Correct SELECT remains 201; COUNT returns 202. |

Every valid mutant started against PostgreSQL and failed a named assertion. Invalid compilation or fixture failures were not counted. Mutants exist only under `/private/tmp/pathome-p1-lead-queue-controls.cvCxEw`; shared production source was not mutated.

## 7. New confirmed defect and required approval boundary

**R05 — P1 duplicate-candidate lookup failure**

- Current behavior: `POST /api/v1/operations/leads/duplicate-candidates` returns HTTP 500 before candidate query execution.
- Evidence: PostgreSQL driver reports `Can't infer the SQL type to use for an instance of java.time.Instant` while `LeadService.duplicateCandidates` passes `now.minusSeconds(3600)` to `JdbcTemplate`.
- Impact: duplicate lookup is unavailable to authorized staff. The main Lead queue remains functional.
- Root cause: JDBC-adjacent `Instant` binding in the lookup-event rate check; this is separate from nullable HQL guards.
- Smallest compliant remedy: keep `DatabaseClock` and business time unchanged; convert only the two JDBC values at the persistence boundary with `java.sql.Timestamp.from(now.minusSeconds(3600))` and `java.sql.Timestamp.from(now)` in `LeadService.duplicateCandidates`, then verify the count query, insert and candidate query together.
- This requires explicit approval because it changes `LeadService.java`, outside the authorized eight-query-line production boundary.
- No broader `findDuplicateCandidates` rewrite, API/schema change, cache or permission change is warranted.

## 8. Commands and evidence

Positive queue command:

```bash
PATHOME_VISIT_OPERATIONS_POSTGRES_TEST=true \
PATHOME_OE_TEST_ALLOW_DISPOSABLE=true \
PATHOME_OE_TEST_DB=pathome_oe_test_integration \
PATHOME_OE_TEST_DB_HOST=127.0.0.1 \
PATHOME_OE_TEST_DB_PORT=5432 \
SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5432/pathome_oe_test_integration \
SPRING_DATASOURCE_USERNAME=pathome \
SPRING_DATASOURCE_PASSWORD= \
mvn -q -f backend/pom.xml -Dtest=LeadHttpPostgresTest test
```

Focused non-database command:

```bash
mvn -q -f backend/pom.xml \
  -Dtest=LeadControllerTest,LeadAccessServiceTest,LeadServiceTest,LeadContractTest test
```

Build and hygiene:

```bash
mvn -q -f backend/pom.xml -DskipTests test-compile
git diff --no-ext-diff --check -- . ':(exclude)chat_gpt_changes'
git diff --no-ext-diff --check -- chat_gpt_changes
git diff --cached --no-ext-diff --check
```

All exited 0. The split worktree check was used because a full combined `git diff --check` process hung under two pre-existing macOS Git-status processes; the two disjoint checks cover the complete tracked diff.

Raw XML files are retained under `tools/oe-integration-env/evidence/p1-lead-queue-correction-20261022/`, including shared, N02, all seven mutant reports and the four focused regression reports.

## 9. Retained D-B evidence and remaining obligations

Retained accepted-scope evidence remains unchanged:

- Legacy baseline → V52 → V53 migration and startup, encrypted consent, masked reveal/audit, idempotent capture/receipt, current-public shortlist/share preparation, truthful clipboard events and signed/reordered/concurrent Meta quarantine were previously executed at the genuine boundary.
- These results are retained, not relabeled as rerun in this package.

Still not closed by queue success:

- **G01** concurrent revocation/deactivation lock race — NOT TESTED at required two-connection boundary.
- **G02** source lease/restart recovery — NOT TESTED.
- **G03** legacy/new fingerprint mixed cutover — NOT TESTED beyond migration compatibility.
- **G04** DIFFERENT/NONE consent through genuine HTTP — NOT TESTED.
- **G05** destination revision and ownership-transfer withdrawal — NOT TESTED.
- **G06** property unpublish/changed-price race — NOT TESTED.
- **G07** shortlist 0/1/20/21 bounds — NOT TESTED at full genuine boundary.
- **G08** lost share-history acknowledgement — NOT TESTED.
- **G09** transaction rollback/fault injection — NOT TESTED.
- **G10** full cross-role/session/UI isolation — server queue subset PASS; connected UI/account-switch evidence remains NOT TESTED.

**A green queue is not D-B acceptance.**

## 10. Canonical tracker and deadline assessment

Both exact canonical workbooks were read without save:

- Cross-Role SHA-256: `0b9ed98530ad65fb6ce3ffd36e53d89db7335678f3c1475777ce39c94c4c76da`.
- Visit Session SHA-256: `384d41c33de864b894b950c4f89a0e6617c072677becce717c166a472bd5a114`.

Current preserved tracker facts:

- DEP-029 In Progress; DEP-030 Partial; DEP-031 Planned; DEP-032 In Progress; DEP-033 implemented on its existing Tenant baseline; DEP-034 In Progress; DEP-039 Partial; DEP-043/044/047/053 In Progress.
- OE #11 remains D-A backend only with connected routing open; OE #12 remains D-B WIP / D-C not built; original #14 date filtering remains OPEN.
- BC05 bounded closure and C26 OPEN remain preserved.
- Approval gates retain the bounded uploader acceptance, D1–D6 approval, D-P separation and the owner-approved one-click prefilled WhatsApp UX as implementation pending.
- Web V1 Delivery Gates keep Admin/CRM, physical device/security, full Web and native work open.

Future tracker synchronization should append, after independent review: queue correction implemented; 17 queue-focused methods pass; R05 duplicate-candidate lookup remains a separate P1; D-B still lacks G01–G10 and is not independently accepted. Do not overwrite historical approvals.

Deadlines remain:

- **31 October 2026 — Complete Web V1: HIGH RISK.**
- **15 November 2026 — Native Tenant and GE apps: AT RISK.**

## 11. Performance and security

- The new filters remain bound parameters; no SQL interpolation or new index is introduced.
- Indexed columns and comparison operands are unchanged. SQL scope still precedes limit/offset and COUNT.
- No cache was added; fresh requests reload current actor authority.
- No cross-Team/foreign-City row or count leak was observed in the corrected queue.
- No full contact, note, consent destination or source payload appeared in masked queue or concealed error responses.
- Query-plan/load testing was not run; production performance certification remains NOT TESTED.

## 12. Scope, Git, database and next boundary

- Production scope was limited to the approved eight lines in `LeadRoutingQueueRepository.findVisibleLeadPage`.
- Test scope was limited to `LeadHttpPostgresTest` and one focused controller failure-propagation assertion.
- Evidence was added under the established verification directory; the implementation log is append-only.
- No schema, migration, DTO, API, permission, role, service/business behavior, cache or browser/UI change was made.
- No tracker workbook, protected database, provider, account, customer or deployment was touched.
- No stage, commit, push, merge, PR, deployment or automatic follow-on implementation occurred.

**Next gates:**

1. Route the corrected queue and the R05 FAIL to one focused GPT-6.1 Sol High independent review.
2. Obtain separate approval before correcting the R05 `Instant` JDBC binding.
3. Preserve G01–G10 as open until their required evidence exists.
4. Do not treat this handoff as D-B, Web V1, provider or release acceptance.

**STOP.**
