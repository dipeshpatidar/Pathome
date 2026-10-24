# PATHOME C05 FINAL Q22/Q24 EVIDENCE CLOSURE

Prepared: 2026-10-22 13:26 IST

Execution status: Q22, Q24 and final database cleanup verified by the affected test-only evidence cohort. Independent Sol acceptance of this narrow evidence remains pending.

## 1. Final verdicts

- Q22: **VERIFIED**
- Q24: **VERIFIED**
- Final PostgreSQL cleanup: **VERIFIED**
- C05 production implementation: **UNCHANGED**
- R05 and Lead Queue/S08: source fingerprints and previously accepted evidence preserved
- D-B overall: **NOT INDEPENDENTLY ACCEPTED**
- Git, trackers and providers: preserved

This closure does not reopen the accepted C05 architecture review, Q01-Q21, Q23, Q25 or Q26. It closes only the two missing discriminating evidence conditions and the post-run database receipt.

## 2. Architecture and test design

No production source, schema, migration, queue behavior, frontend, provider or configuration was changed.

Q22 uses the real HTTP request and the real `duplicateCandidates` transaction. A disposable-schema `AFTER INSERT` trigger runs only after the lookup event row exists but before its transaction commits:

- Two actors acquire test-only advisory locks in opposite order. PostgreSQL detects a genuine deadlock cycle and chooses one request as victim.
- A second hook acquires one advisory lock and sleeps. The test reads the exact backend PID from `pg_locks` and calls `pg_cancel_backend` on that PID only.
- A third hook crosses the already-configured 10-second transaction-local statement timeout.

Every hook is created in the UUID-named test schema and dropped by the test. No test object or backend cancellation remains after the suite.

Q24 sends the authenticated lookup through a loopback test-only TCP proxy. The proxy forwards the request to the real application, receives the successful server response after commit, records that status, and closes the client socket without forwarding any response byte. The client therefore observes EOF instead of a success acknowledgement. The test then independently reads the durable lookup event before issuing a fresh retry.

## 3. Q22 server-originated failure evidence

Final backend PID values and raw SQLSTATE evidence are recorded in the retained Surefire XML:

| Failure | Server provenance | HTTP disposition | Transaction evidence | Recovery |
| --- | --- | --- | --- | --- |
| Real deadlock | `40P01`, `deadlock detected`; PostgreSQL reported the two backend PIDs and the advisory lock cycle | Victim returned sanitized 500 | Victim event count 0; winner event count 1; advisory locks released | Victim retry returned 200 and committed one event |
| Real operator cancellation | `57014`, `canceling statement due to user request` after targeted `pg_cancel_backend` | Sanitized 500 | Inserted event count 0; lock and transaction rolled back | Fresh retry returned 200 and committed one event |
| Real statement timeout | `57014`, `canceling statement due to statement timeout` at the configured 10-second bound | Sanitized no-store 503 | Inserted event count 0; transaction rolled back | Fresh retry returned 200 and committed one event |

The cancellation message does not contain timeout wording. The statement-timeout message does, which exercises the existing SQLSTATE-led classifier distinction. The previously accepted real lock-timeout evidence remains preserved and was not relabelled.

The previously accepted deferred-commit trigger scenario also remains covered: the commit-time trigger produced a sanitized 500, left zero durable lookup events and allowed a fresh retry to commit one event.

Discriminator: the test requires a positive PostgreSQL backend PID, the server message itself, actual advisory lock ownership, rollback of an already inserted row, and a successful fresh request. A synthetic Java exception cannot satisfy those assertions.

## 4. Q24 lost acknowledgement evidence

Final retained output:

```text
Q24_LOST_ACK server_status=HTTP/1.1 200 client_bytes=-1 committed_after_loss=1 committed_after_retry=2
```

The first server transaction committed and emitted a successful masked response, but the controlled client transport received no response byte. Before retry, the test read one durable `DUPLICATE_CANDIDATE` event for the authenticated actor. A separate fresh request then returned 200 with `Cache-Control: no-store`, created a second event, and retained current non-idempotent semantics.

The assertions also verify:

- The full contact number was absent from both the committed server response and retry body.
- The expected candidate remained the sole visible candidate.
- Both durable events belonged to the same actor and carried the duplicate-candidate purpose.
- An unrelated actor had zero events.
- Visit request, session, entitlement-account and notification-outbox counts were unchanged.

Discriminator: two normally acknowledged HTTP successes fail because the client must read EOF, while a failure before server commit fails because the proxy must capture a 200 and the database must already contain one event before retry.

## 5. Final database receipt

Direct read-only receipt captured at **2026-10-22 13:25:26.196274 IST**, after the final Surefire report completed at 13:25:17 IST:

```text
database|pathome_oe_test_integration|pathome|127.0.0.1/32|5432|14.14 (Homebrew)
sentinel|DISPOSABLE|pathome_oe_test_integration|1.0.0|20261013T124820-98553|2026-10-13 12:48:20.737699 IST
sentinel_count|1
p4_schema_count|0
c05_function_count|0
c05_trigger_count|0
active_non_test_sessions|0
```

The exact receipt is retained in `FINAL_POST_RUN_CLEANUP.txt`.

## 6. Verification commands and result

Compilation:

```bash
mvn -q -f backend/pom.xml -DskipTests test-compile
```

Result: PASS, exit 0.

Final affected cohort:

```bash
PATHOME_VISIT_OPERATIONS_POSTGRES_TEST=true \
PATHOME_OE_TEST_ALLOW_DISPOSABLE=true \
PATHOME_OE_TEST_DB=pathome_oe_test_integration \
PATHOME_OE_TEST_DB_HOST=127.0.0.1 \
PATHOME_OE_TEST_DB_PORT=5432 \
SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5432/pathome_oe_test_integration \
SPRING_DATASOURCE_USERNAME=pathome \
SPRING_DATASOURCE_PASSWORD='' \
mvn -q -f backend/pom.xml \
  -Dtest='LeadHttpPostgresTest#c05RealPostgresDeadlockCancellationAndTimeoutAbortTheLookupTransaction+c05LostHttpAcknowledgementLeavesDurableEvidenceBeforeNonIdempotentRetry' \
  test
```

Result: PASS. Tests run 2; failures 0; errors 0; skipped 0; time 21.255 seconds.

Raw Surefire XML:

- `tools/oe-integration-env/evidence/c05-final-q22-q24-closure-20261022/TEST-final-LeadHttpPostgresTest-Q22-Q24.xml`
- SHA-256 `78ab7c36c8e17df93f47dee847cbc748595a100c148baf418d886b7cccdd1d78`

## 7. Source and Git preservation

Test source:

- `backend/src/test/java/com/indore/pathome/spaces/service/LeadHttpPostgresTest.java`
- SHA-256 `8cfde9205567c6ec529f8528d83efd452679a28d1d9d0f72af7098eecb33ccef`

Production fingerprints exactly match the prior accepted C05 manifest:

```text
6d0d0df3a9345759ec94d74f7c954e86d068af0438d8b5b33dfe37e9e08a7bed  LeadService.java
700ec9de5134bfcfec383ce9d1973ae545706c5162abb5987cf5718f494a4b17  OperationalSecurityGuards.java
93cd49e6b93fcf525f6191d6f3a579539350f5b2a0f6844262528efde2e80af8  GlobalExceptionHandler.java
eb0ac7d7852c779e795cdc56275e49b24170affdef6f512842b817029d8df2a7  LeadRoutingQueueRepository.java
15b856ad6fe8d5f2c999617f255b546b30a457d7ac2a9270bcca7db739d640d3  V53
```

The branch remains `feature/phase2a-oe-ui-integration` at `a943775e07a153087d0b38c315db20e18b30aec5`. The index is empty. The pre-task porcelain manifest SHA-256 was `b2bcbc57b84c6d099a76c1102f31270de22ce4c88aa381a5c787001f3ce64c37`; the post-task value is `d46b7efa1a18dfb58c377e61eb40b8cc91bed100126631b3069297bc125eba26`. The only manifest additions are the report and evidence directory (26 tracked plus 61 untracked before; 26 plus 63 after). No Git mutation or provider action was performed.

## 8. Tracker assessment

Both canonical 22 October workbooks were read with read-only extraction and were not saved:

- Cross-Role SHA-256: `0b9ed98530ad65fb6ce3ffd36e53d89db7335678f3c1475777ce39c94c4c76da`
- Visit Session SHA-256: `384d41c33de864b894b950c4f89a0e6617c072677becce717c166a472bd5a114`

Confirmed provenance:

- Cross-Role `OE 20Q Closure` question #12 is worksheet row 17.
- Cross-Role row 17 still records D-B as WIP and D-C as not built.
- Visit `Web V1 Delivery Gates` still records D-B WIP, D-C missing and no full Web V1 release.
- Full Web V1 remains 31 October 2026, HIGH RISK and not release approved.
- Native Tenant/GE remains 15 November 2026, AT RISK.

No tracker or canonical status was edited.

## 9. Remaining limitations

- Q22 verifies the real server-abort and transaction-boundary behavior through disposable test-only database hooks. It does not measure production lock contention, load or PostgreSQL query plans.
- Q24 verifies a deliberate local HTTP transport acknowledgement loss after durable commit. It does not emulate a physical-device network transition.
- D-B overall remains **NOT INDEPENDENTLY ACCEPTED**. G01-G10, connected UI/device, staging/release, provider delivery and unrelated product gates remain outside this closure.
- No Sol review was launched, per the stop boundary.

## 10. Required next action

Run one narrow, evidence-only Sol verification of Q22, Q24 and the post-run cleanup receipt. Do not repeat the C05 production architecture review or the previously accepted 26-case cohort.
