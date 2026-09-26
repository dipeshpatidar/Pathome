# AGENTS Guidelines for Pathome Spaces

## High-Performance & Database Guidelines

When developing features for Pathome Spaces (Backend Spring Boot & Frontend Web/Mobile):

1. **$O(1)$ Space and Time Complexity**:
   - Always prioritize sub-millisecond execution times.
   - Use `ConcurrentHashMap` for L1 in-memory caches to prevent repetitive DB reads in HTTP handlers.
   - Never call `findAll()` in request handlers.

2. **Pre-Compiled RegEx Patterns**:
   - All regular expressions must be declared as `private static final Pattern` constants.
   - Avoid creating `Pattern` instances inside methods or loops.

3. **Database-Driven Entities**:
   - Cities, localities, sectors, properties, and fees must be stored and managed in the PostgreSQL database.
   - Newly extracted entities must auto-persist to PostgreSQL with proper composite database indexes (`@Index`).

4. **Mandatory JUnit 5 Testing**:
   - All parser logic, services, and exception handlers must be covered by JUnit 5 tests.
   - Verify changes with `mvn test`, `mvn clean compile`, and `npm run build`.

5. **Centralized Global Exception Handling**:
   - All HTTP REST controllers must route unhandled exceptions through `@RestControllerAdvice` (`GlobalExceptionHandler.java`).
   - Standardized structured JSON response: `{ timestamp, status, error, message, path }`.

6. **Strict Meaningful UI Copywriting (Zero Garbage & Developer Notes)**:
   - Every single user-facing text string, header, label, badge, tooltip, button CTA, and notification message across the frontend (Web/Mobile) MUST be clean, production-ready, customer-centric, and meaningful.
   - Never include developer notes, layout indicators, debug tags (e.g. no "(100% Full Width)", no "(Extracted in Real-Time)"), placeholder junk, or arbitrary filler text.

7. **Mandatory AI Change Log**:
   - Before completing every substantive task, append a dated entry to the appropriate repository-root change log file (`antigravity_changes` for Antigravity, `chat_gpt_changes` for ChatGPT/Cursor). Never overwrite or remove previous entries.
   - Each entry must use the current India date and time, state why the work was requested, list the exact files or areas changed, summarize the outcome, record relevant verification, and note commit/push status when applicable.
   - Log investigations and fixes alike so another AI can safely continue the work. Do not record credentials, tokens, connection strings, or other secrets; describe sensitive configuration only at a high level.

8. **Responsive-First Frontend Development — Mandatory**:
   - Whenever ANY frontend page or component is created or modified, responsive behavior across small mobile (320px–375px), normal mobile (390px–414px), tablet (768px), laptop (1024px), desktop (1280px), and wide desktop (1440px+) is part of the **Definition of Done**, even when not explicitly requested.
   - Frontend work must NOT be considered complete if it only works on desktop.
   - For every frontend task, verify:
     - No unintended horizontal page overflow (`scrollWidth <= innerWidth`).
     - No clipped content, overlapping controls, or inaccessible actions.
     - Long text wraps/truncates intentionally; controls remain reachable and touch targets >= 44px where applicable.
     - iOS Safari input behavior is respected: editable controls (inputs/textareas/selects) must have an effective font size of at least 16px to prevent unwanted automatic page zoom.
     - Browser pinch-zoom and accessibility must NEVER be disabled (`user-scalable=no` / `maximum-scale=1` are prohibited).
     - Dialogs/modals must fit the viewport with scrollable content regions and always-reachable action footers.
     - Drawers/menus must manage background page scrolling correctly (lock background scroll without losing position).
     - Mobile safe areas (`env(safe-area-inset-top)`, `env(safe-area-inset-bottom)`) are respected where applicable.
     - Loading, error, empty, and success states must be fully responsive.
   - Prefer modern CSS Grid, Flexbox, `clamp()`, `min()`, `max()`, `min-width: 0`, and aspect ratios over arbitrary fixed dimensions or device-specific hacks.

9. **Approved UI Preservation — Mandatory**:
   - Once an existing UI/page/component has been approved or committed as the accepted design, new features must NOT redesign, restyle, reorganize, resize, or materially move existing UI elements unless the user explicitly requests a UI change.
   - When implementing new functionality:
     1. Preserve the existing approved visual hierarchy and layout.
     2. Integrate new controls with the smallest reasonable visual footprint.
     3. Reuse existing design patterns/components where possible.
     4. Do not introduce new full-width sections, toolbars, cards, toggles, navigation structures, spacing systems, colors, typography, or layout changes merely to accommodate a feature.
     5. Functional changes must not silently become visual redesigns.
     6. If a requirement genuinely cannot be implemented without changing the approved UI, STOP and report the required UI change before making it.
     7. Before completing frontend work, compare affected UI against the last committed approved implementation and explicitly report any intentional visual differences.
     8. Existing responsive behavior must remain intact.
   - This rule works together with the existing RESPONSIVE-FIRST rule.

10. **APPROVAL-FIRST CHANGE CONTROL — Mandatory**:
    - The existing committed/approved implementation is the default source of truth.
    - Unless the user explicitly requests a specific change, DO NOT make that change.
    - This applies to:
      - UI
      - UX
      - layout
      - styling
      - navigation
      - buttons
      - toggles
      - labels
      - workflows
      - functional behavior
      - business logic
      - architecture
      - API contracts
      - database behavior
      - validation
      - defaults
      - naming
      - dependencies
      - infrastructure behavior
      - feature scope
    - Rules:
      1. Do not add "helpful" features that were not requested.
      2. Do not redesign or reorganize existing approved UI while implementing a backend/functional feature.
      3. Do not add buttons, toggles, tabs, selectors, cards, banners, toolbars, dialogs, workflow steps, or navigation merely because they make the implementation easier.
      4. Do not change an existing workflow to match a new internal architecture.
      5. Internal implementation details must adapt to the approved user experience, not the other way around.
      6. If you believe an unrequested change is necessary:
         STOP.
         Report:
         - what change you think is required
         - why
         - alternatives
         - impact
         Then ask for explicit user approval BEFORE implementing it.
      7. Do not interpret silence as approval.
      8. Do not bundle optional improvements into requested work.
      9. Bug fixes must fix the bug with the smallest reasonable behavioral and visual footprint.
      10. Before modifying an existing frontend screen, compare against the last committed approved implementation.
      11. Before finalizing work, explicitly identify every intentional user-visible change.
      12. If a user-visible change was not explicitly requested or approved, revert it.
    - This rule is STRICTER than general "improve UX" or "best practice" instructions.
    - Existing RESPONSIVE-FIRST and APPROVED-UI-PRESERVATION rules remain in force. Do not weaken existing AGENTS.md rules.

11. **Targeted Browser Automation — Mandatory**:
    - Run browser automation only when a large or critical UI change needs a focused behavioral or visual check; do not launch it by default for small styling changes or routine regressions.
    - Inspect code and run focused tests first. State the precise question a qualifying browser check answers, keep it targeted, and limit it to ten minutes.
    - Automated tests and builds do not prove visual quality. When a browser check is not warranted or practical, provide concise manual visual verification steps instead.
