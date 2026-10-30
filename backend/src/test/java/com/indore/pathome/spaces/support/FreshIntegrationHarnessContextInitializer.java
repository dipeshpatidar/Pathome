package com.indore.pathome.spaces.support;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.beans.factory.ListableBeanFactory;

/**
 * Refuses a browser test context unless the guarded runtime configuration is actually imported.
 *
 * <p>This runs before bean instantiation, so a missing import or explicit opt-in fails before any
 * datasource connection attempt.</p>
 */
public final class FreshIntegrationHarnessContextInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        validate(
                "true".equalsIgnoreCase(
                        System.getenv(FreshIntegrationTarget.TEST_ENABLEMENT_FLAG)),
                "true".equalsIgnoreCase(System.getenv("PATHOME_FRESH_BROWSER_HARNESS")),
                System.getenv(FreshIntegrationTarget.MANIFEST_FLAG));
        context.addBeanFactoryPostProcessor(beanFactory ->
                requireGuardedConfiguration(beanFactory));
    }

    static void validate(
            boolean explicitTestFlag,
            boolean browserOptIn,
            String manifest) {
        if (!explicitTestFlag) {
            throw new IllegalStateException(
                    "Fresh integration browser harness requires the explicit test flag");
        }
        if (!browserOptIn) {
            throw new IllegalStateException(
                    "Fresh integration browser harness requires its explicit opt-in");
        }
        if (manifest == null || manifest.isBlank()) {
            throw new IllegalStateException(
                    "Fresh integration browser harness requires the custody manifest");
        }
    }

    static void requireGuardedConfiguration(ListableBeanFactory beanFactory) {
        if (beanFactory.getBeanNamesForType(
                FreshIntegrationRuntimeConfiguration.class, false, false).length == 0) {
            throw new IllegalStateException(
                    "Fresh integration browser harness must import FreshIntegrationRuntimeConfiguration");
        }
    }
}
