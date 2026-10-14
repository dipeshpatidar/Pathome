# Package 3B — governed intake-routing browser verification

Real Chromium, fresh contexts, empty local storage, the application's own sign-in form, and the live
Spring Boot backend on the designated disposable PostgreSQL database. Nothing is seeded and every
`/api/**` call reaches the real backend, so the 401/403/404 answers and the routing reads come from the
application and the database.

    P3B_BACKEND_URL=http://localhost:8080 \
    P3B_ADMIN_EMAIL=... P3B_ADMIN_PASSWORD=... \
    oe_alpha_EMAIL=... oe_alpha_PASSWORD=... \
    city_admin_EMAIL=... city_admin_PASSWORD=... \
    P3B_CITY_ID=1 \
    node run.mjs

The harness proves: the routing surface refuses an anonymous browser; the global Staff Admin reads a
truthful, uncached, correctly-shaped collection; an ordinary OE receives 403 on every routing entry
point while keeping its existing claimable-work read; a City Team Admin reads only its authorized
City and has an unauthorized City concealed as 404; and no new routing affordance is invented in the
existing tenant/Operations UI.

`tools/oe-integration-env/package3.sh verify-routing-browser` runs this harness with the guarded
environment. It requires a backend started from the current source (`run.sh stop-backend` then
`run.sh backend`).

**Approval dependency (not bypassed here).** The narrow listing-review authority extension is not
approved, so no genuine canonical published listing is fabricated and the fresh-publication →
intake → routing → Claim acceptance is **not claimed** by this harness.
