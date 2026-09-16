# Backend deployment requirements

The backend does not contain fallback credentials. Supply these values through the deployment secret manager or process environment:

- `JWT_SECRET`: at least 32 bytes; rotate any value previously stored in source control.
- `CLOUDINARY_CLOUD_NAME`
- `CLOUDINARY_API_KEY`
- `CLOUDINARY_API_SECRET`: rotate any value previously stored in source control.
- Database and OAuth credentials referenced by `application.yml`.
- `PATHOME_MODEL_SIGNING_KEY`: at least 32 bytes when registering or evaluating local parser models.

## First Flyway deployment to an existing database

Existing Pathome databases created before Flyway do not have a schema-history table. For the first upgraded startup only, set:

```text
PATHOME_DATABASE_ALLOW_FLYWAY_BASELINE=true
```

The application verifies that it is connected to PostgreSQL and that the expected legacy property tables exist before creating the baseline. Remove the flag after the first successful migration. New databases and already-migrated databases must leave it unset.

Migration `V2__reconcile_property_publish_constraints.sql` makes coordinates optional, reconciles property/media enum checks, and safely adds parser-learning ownership metadata to an existing schema.
