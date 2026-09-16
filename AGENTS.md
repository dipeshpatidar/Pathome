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

