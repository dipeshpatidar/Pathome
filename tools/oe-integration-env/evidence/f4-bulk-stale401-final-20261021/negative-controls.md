# F4-BULK Stale-401 Negative Controls

Both controls ran in separate copies outside the shared repository:
`/tmp/pathome-f4-stale401-controls.UwSt0w/n1` and `/tmp/pathome-f4-stale401-controls.UwSt0w/n2`.
Each mutant compiled successfully before its discriminating production-module browser run.

## N1 — stale classification bypass

Original hook SHA-256:
`246914b09eb295a4b38123bd9af55c6c015b2fb8057e2dc872ed6f32adde5ad6`

Mutant hook SHA-256:
`df9e57decb1b6a764b92297b70bea2b58d26cd80b5044657dea4828482d34689`

Patch:

```diff
- const currentSessionOwnsExpiry = isUploaderSessionActive(session);
- const belongsToCurrentExpiry = currentSessionOwnsExpiry
-   || sameOperationsCredential(currentSessionExpirySessionRef.current, session);
- if (!belongsToCurrentExpiry) return 'cancelled';
- if (currentSessionOwnsExpiry) notifySessionExpired(session.token);
- return 'session-expired';
+ return operation.signal.reason === 'auth-changed'
+   ? 'cancelled'
+   : 'session-expired';
```

Build:

```text
npm run build
✓ 2047 modules transformed
✓ built
dist/assets/index-DymjZm2m.js
```

Observed failure:

```text
STATE {"dialogBefore":1,"result":{"outcome":"session-expired","isOwnerActive":false,
"canFinalize":false,"isAuthError":true},"dialog":1,"expiryEvents":[],
"token":"token-b","errors":[]}
PASS replacement confirmation survives
FAIL stale 401 is classified as cancelled
PASS stale result cannot finalize
PASS no stale expiry notification
```

The mutant reintroduced the prohibited `session-changed`→`session-expired` classification.
The dialog survived because the independent caller guard remained intact, while the stale result
wrongly regained expiry semantics. This is the named N1 classification invariant failure.

## N2 — unconditional completion bypass

Original hook SHA-256:
`246914b09eb295a4b38123bd9af55c6c015b2fb8057e2dc872ed6f32adde5ad6`

Mutant hook SHA-256:
`bae2c37d4f096d1c31b81b68a9bb3e3ebec6f166284b1c1b18c25ead8275ca28`

Original bar SHA-256:
`8bdda0b8be45931d7700373ac9c2c024d784e55960f17a43d2443c10fcaeb21c`

Mutant bar SHA-256:
`2a853db22a213e56f31e83eff1d52309edf19910ecef2fef50b6afecc352f90a`

Patch intent:

- restore `canFinalize: true` for single and bulk results;
- make the bulk owner predicate unconditional;
- remove the pre-mutation ownership/finalization checks;
- treat a cancelled stale completion as a dialog-closing terminal result.

Build:

```text
npm run build
✓ 2047 modules transformed
✓ built
dist/assets/index-V-kOfQVV.js
```

Observed failure:

```text
STATE {"dialogBefore":1,"result":{"outcome":"cancelled","isOwnerActive":false,
"canFinalize":true,"isAuthError":false},"dialog":0,"expiryEvents":[],
"token":"token-b","errors":[]}
FAIL replacement confirmation survives
PASS no page error
```

The replacement confirmation was closed by an old operation. This is the named N2 UI-ownership
failure.

## Restoration and green rerun

Both temporary source trees were restored to the exact shared hashes:

```text
bc97eda04053dc0da9454c4cb1cc15e0e069dfdbe7e09d64704126a398e5d7c4  usePropertyDraft.ts
8bdda0b8be45931d7700373ac9c2c024d784e55960f17a43d2443c10fcaeb21c  DraftManagementBar.tsx
```

N1 restored discriminator:

```text
PASS replacement confirmation survives
PASS stale 401 is classified as cancelled
PASS stale result cannot finalize
PASS no stale expiry notification
```

N2 restored discriminator:

```text
PASS replacement confirmation survives
PASS no page error
```

The shared repository was not used as a mutant target and remained unchanged during these controls.
