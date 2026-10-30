-- Approved Backoffice capabilities are additive CITY-scoped duties.
-- V54 remains immutable; replace only the two constraints that omitted these values.

ALTER TABLE staff_access_grants
    DROP CONSTRAINT ck_staff_grants_capability,
    DROP CONSTRAINT ck_staff_grants_capability_scope;

ALTER TABLE staff_access_grants
    ADD CONSTRAINT ck_staff_grants_capability CHECK (capability IN (
        'STAFF_ADMIN', 'CITY_TEAM_ADMIN', 'OPS_INTAKE', 'OPS_COORDINATE',
        'OPS_SUPERVISE', 'OPS_ROUTING',
        'PROPERTY_EDIT_PUBLISH', 'PROPERTY_AUDIT_READ', 'PROPERTY_LIFECYCLE'
    )),
    ADD CONSTRAINT ck_staff_grants_capability_scope CHECK (
        (capability = 'STAFF_ADMIN' AND scope_type = 'GLOBAL')
        OR (capability IN (
            'CITY_TEAM_ADMIN', 'OPS_INTAKE', 'OPS_ROUTING',
            'PROPERTY_EDIT_PUBLISH', 'PROPERTY_AUDIT_READ', 'PROPERTY_LIFECYCLE'
        ) AND scope_type = 'CITY')
        OR (capability IN ('OPS_COORDINATE', 'OPS_SUPERVISE') AND scope_type = 'TEAM')
    );
