# Package 3A governed provisioning - genuine OE admission

Runs the **real built app** (`frontend-web/dist`) in Chromium against a **live Spring Boot backend on
PostgreSQL**, with a brand-new browser context per scenario and **no seeded authentication state**.
The sign-in and registration forms are the app's own; every `/api/**` call is forwarded verbatim to
`http://localhost:8080`, so the admission decision, the protected read and the 401/403 answers are
decided by the real application and the real database.

## Running it

```bash
bash tools/oe-integration-env/run.sh backend        # disposable database only
bash tools/oe-integration-env/package3.sh provision
bash tools/oe-integration-env/package3.sh verify-browser
```

`verify-browser` reads the ephemeral identities written by `provision` (a `KEY=VALUE` file under
`$PATHOME_OE_TEST_RUN_DIR/package3/`), so no credential is printed, committed or seeded into the
browser.

## Scenarios

1. **Authorised OE.** Empty storage to real sign-in: the Operations entry appears only after a live
   `/staff/me/capabilities` read that carries the signed-in token and contains
   `OPS_COORDINATE@TEAM(alpha)`; the destination is entered deliberately; `/operations` renders and
   reads protected work with the same credential; the truthful empty queue is shown; logging out
   clears the credential and withdraws the entry.
2. **Another Team's OE.** Admitted on its own grant, and its capability truth does not contain the
   other Team.
3. **Negative controls.** A brand-new account registered through the real form, active staff without
   a grant, and a City Team Admin are never offered Operations and never issue a protected read.

## Boundaries

Claimable Team work requires Team routing, which belongs to Package 3B: the admitted read is asserted
to return a truthful **empty** page rather than fabricated work. Cross-Team *work* isolation is
therefore asserted at the capability-truth level, and the same-Team claim path stays blocked on 3B.
Physical devices, touch input and the `hidden` visibility branch are not exercised here.
