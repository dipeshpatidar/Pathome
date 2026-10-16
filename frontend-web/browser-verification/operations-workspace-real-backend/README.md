# OE workspace real-backend acceptance

`run.mjs` now delegates to `tools/oe-integration-env/phase2a/browser.mjs`. It no longer seeds authenticated
storage, invents shell 403 responses, substitutes cache headers or uses old hand-routed fixtures.
Do not invoke it against an arbitrary development backend.

Use the guarded entry point after the current disposable API suite passes all 82 entries:

```bash
PATHOME_OE_TEST_PHASE2A_LIVE=true bash tools/oe-integration-env/phase2a.sh browser
```

See [the authoritative prerequisites](../../../tools/oe-integration-env/PHASE2A.md).
Twelve separately counted browser scenarios operate the actual compiled React app against a bound
disposable Spring Boot backend. The proxy streams genuine responses. Explicit labeled transport
controls hold an old-account response and drop one genuine scheduling acknowledgement to test
uncertainty/reconciliation; they manufacture no HTTP response. Reports contain no bearer tokens,
passwords or contact payloads.

The original correction was code-only. Owner-approved B09 live execution and current results are
recorded in `tools/oe-integration-env/B09_RESULTS.md`; historical evidence is preserved separately.
