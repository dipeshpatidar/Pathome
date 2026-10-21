# Executed Cohorts

| Command | Result |
| --- | --- |
| `npx tsc --noEmit` | PASS |
| `npm run build` | PASS; 2,047 modules; existing large-chunk advisory only |
| `node --test src/tests/uploaderRestoreTerminalDenial.test.mjs src/tests/propertyUploaderSessionIsolation.test.mjs src/tests/draftBulkDiscard.test.mjs src/tests/operationsWorkspaceSession.test.mjs src/tests/authSessionIsolation.test.mjs` | 75/75 PASS; 0 failures, errors, cancellations or skips |
| `node browser-verification/property-uploader-session/f4-callback-ownership.mjs` | 37/37 PASS |
| `node browser-verification/property-uploader-session/f4-late-overlap.mjs` | 57/57 PASS |
| `node browser-verification/property-uploader-session/run.mjs` | 80/80 PASS; 429,659 ms budget remained |
| `node browser-verification/property-uploader-session/storage-ownership.mjs` | 5/5 PASS |
| `node browser-verification/property-uploader-session/discard-generation-isolation.mjs` | 37/37 PASS |
| unchanged `mounted-cross-tab-stale401-probe.mjs` | 3/3 PASS; red baseline was 1 intended failure |
| unchanged `external-denial-restore-probe.mjs` | 6/6 PASS |
| unchanged `bulk-denial-probe.mjs` | 7/7 PASS |
| exact-source current-401-before-replacement control | 5/5 PASS; one expiry event; replacement dialog survives |

All cohesive browser runs completed within the approved ten-minute ceiling. The C1 probe ran at
1280×900; the 80-check session cohort includes 320, 375, 390, 414, 430, 768, 1024, 1280 and
1440px containment. Authenticated/authorized editor behavior is retained in the 17-assertion
React→Spring Boot→disposable PostgreSQL evidence from the preceding accepted integration pass and was
not relabeled as newly executed.
