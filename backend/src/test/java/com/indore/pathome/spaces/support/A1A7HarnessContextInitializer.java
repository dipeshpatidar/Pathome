package com.indore.pathome.spaces.support;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/** Prepares the isolated scenario schema before the guarded application context opens. */
public final class A1A7HarnessContextInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        if (!"true".equalsIgnoreCase(System.getenv(A1A7IsolatedTarget.TEST_FLAG))) {
            throw new IllegalStateException("A1-A7 test flag is absent");
        }
        A1A7IsolatedTarget.Target migrator =
                A1A7IsolatedTarget.requireFromEnvironment(A1A7IsolatedTarget.MIGRATOR_ROLE);
        A1A7SchemaHarness.migrate(migrator, 63);
        A1A7SchemaHarness.verifyComplete(migrator);
        context.addBeanFactoryPostProcessor(beanFactory -> {
            if (beanFactory.getBeanNamesForType(
                    A1A7RuntimeConfiguration.class, false, false).length == 0) {
                throw new IllegalStateException(
                        "A1-A7 tests must import the guarded runtime configuration");
            }
        });
    }
}
