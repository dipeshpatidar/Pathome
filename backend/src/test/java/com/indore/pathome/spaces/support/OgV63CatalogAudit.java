package com.indore.pathome.spaces.support;

/** Pure catalog containment predicates with a deliberately small extension allowlist. */
final class OgV63CatalogAudit {
    private static final String READ_ONLY_DRAFT_COVER_ASSERTION =
            "pathome_assert_active_lessor_draft_cover_limit";
    record FunctionFact(
            String schema,
            String name,
            String owner,
            boolean securityDefiner,
            boolean extensionOwned,
            boolean triggerFunction,
            boolean publicExecute) {}

    private OgV63CatalogAudit() {}

    static void requireContained(FunctionFact function) {
        if (function.extensionOwned()) {
            OgV63Target.require(
                    "public".equals(function.schema())
                            || ("pg_catalog".equals(function.schema())
                            && "plpgsql".equals(function.name())),
                    "unexpected extension-owned function namespace");
            return;
        }
        OgV63Target.require("public".equals(function.schema())
                        && OgV63Target.MIGRATOR.equals(function.owner())
                        && !function.securityDefiner()
                        && (function.triggerFunction()
                        || READ_ONLY_DRAFT_COVER_ASSERTION.equals(function.name())),
                "application function ownership/security mode differs");
        OgV63Target.require(function.publicExecute(),
                "application function is not a contained trigger routine");
    }

    static void requireDependency(String fromSchema, String toSchema) {
        OgV63Target.require("public".equals(fromSchema) && "public".equals(toSchema),
                "foreign namespace dependency");
    }
}
