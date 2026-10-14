# Genuine journey — controlled browser verification

Real Chromium, fresh contexts with empty local storage, the application's own sign-in form, and the live
Spring Boot backend on the designated disposable PostgreSQL database. Static assets are served in-process
at a synthetic origin (`http://oe.local`) and every `/api/**` call is proxied verbatim to the backend, so
the capability decision, the queue reads and the 401/403/409/204 answers all come from the real
application and the real database.

The rows under test are the ones `package4.sh journey` produced from a real lessor submission that a real
staff administrator published: no browser fixture is seeded.

    bash tools/oe-integration-env/package4.sh journey          # produces the journey state file
    npm --prefix frontend-web run build
    bash tools/oe-integration-env/package4.sh journey-browser

The harness proves: no operational surface exists for a visitor; the genuine Operations Executive is
offered the workspace entry from a live capability read and reaches it on the dedicated `/operations`
destination; the genuinely routed Request is claimable work in the workspace; the Claim travels as exactly
one command answered by a real 204; My work shows the newly owned Request and nothing else; the owned
workspace has no horizontal overflow and keeps working at 390px; and neither a plain tenant nor the staff
review administrator is offered any operational surface — the new review authority grants no Operations
workspace entry.

It requires `P4_STATE_FILE` (written by `package4.sh journey`) and an already-built `frontend-web/dist`.
