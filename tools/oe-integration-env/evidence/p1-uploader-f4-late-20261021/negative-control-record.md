# F4-LATE negative-control provenance

Date: 21 October 2026, IST

## Red before correction

The independent Sol reproducer
`/Users/dipeshpatidar/.codex/visualizations/2026/10/21/01a15eb8-d7af-79c2-9373-552907ab97d9/overlap-denial-probe.log`
contains the retained original failure:

- `PASS save actually in flight before media denial`
- `FAIL late current-credential success must not restore Saved after terminal denial`
- `FAIL terminal permission feedback must persist`
- Final network trace includes a post-denial `GET /api/v1/admin/drafts`

The local focused discriminator reproduced the same failure before correction with exit code 1.

## F4-LATE epoch bypass

Isolated workspace: `/tmp/pathome-f4-negative.mRBLJe`

File: `frontend-web/src/hooks/usePropertyDraft.ts`

Original SHA-256:
`e31554dc1572907232fec0325d74776fdfb3b0abd73b3ac60485432889d3d4b2`

Mutant SHA-256:
`4145211b309d33123b55b2bd76147f768c946c8cafd81fbc72d9da5f31dbd42f`

Exact one-line mutation:

```diff
-          if (!isMountedRef.current || !operation.isActive()) return;
+          if (!isMountedRef.current || !isUploaderSessionActive(session)) return;
```

This bypasses only the autosave completion epoch check. The mutant built successfully. The intended BATCH assertions failed:

- `FAIL [batch] late save cannot restore Saved after terminal denial`
- `FAIL [batch] terminal permission feedback persists after the late response`

Raw output: `f4-late-negative-mutant-fail.log`.

The exact source was restored. Restored SHA-256 matched the production SHA-256
`e31554dc1572907232fec0325d74776fdfb3b0abd73b3ac60485432889d3d4b2`;
it rebuilt successfully and the same discriminator reported `checks=49 failures=0`.
Raw output: `f4-late-negative-restored-green.log`.

## N2 narrow recovery controls

Historical combined N2 evidence is retained at
`tools/oe-integration-env/evidence/p1-uploader-final-closure-20261021/N2-intended-failure.log`.
That run removed multiple evidence holds and failed first at the retry assertion, so it did not
distinguish dismiss from resolution.

Two new one-line controls were run against one isolated backend copy:

| Control | Exact removed line | Mutant SHA-256 | Intended failure |
| --- | --- | --- | --- |
| Dismiss only | `propertyAuthority.holdEvidenceForCurrentMutation(BackOfficePropertyAuthority.Action.FAILED_UPLOAD);` inside `dismiss` | `2b611b9f31f38e4e34ecd3171a40fba54ac8a3ad557fc739e52277bfe8e5d014` | `dismiss: supported revocation did not wait for recovery evidence` |
| Resolution only | `propertyAuthority.holdEvidenceForCurrentMutation(BackOfficePropertyAuthority.Action.UPLOADER_MEDIA);` inside `recordResolution` | `3a19b59f870a15fbb65519dc0b8c632e9afa9dcb136ea819d317ec44fe1ae388` | `resolution: supported revocation did not wait for recovery evidence` |

Raw outputs:

- `N2-dismiss-only-intended-failure.log`
- `N2-resolution-only-intended-failure.log`

The original `FailedUploadService.java` SHA-256
`e70fee62d58c8366501a5ef5d3ee18dfae66be4186a968d020babcb92f87c267`
was restored. The same PostgreSQL test then passed with zero failures, errors or skips:
`N2-restored-green.log`.

## Disposable target

Before and after the narrow PostgreSQL controls:

- database: `pathome_oe_test_integration`
- live sentinel count: `1`
- owned `p1_uploader%` schema count: `0`

No protected database, real user account or owner grant was selected or modified.
