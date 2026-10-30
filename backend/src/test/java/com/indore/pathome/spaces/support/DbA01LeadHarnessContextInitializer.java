package com.indore.pathome.spaces.support;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/** Refuses a DB-A01 Lead Spring context before bean initialization unless its exact guard is imported. */
public final class DbA01LeadHarnessContextInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override
    public void initialize(ConfigurableApplicationContext context) {
        if (!"true".equals(System.getenv(DbA01LeadTestTarget.TEST_FLAG))) {
            throw new IllegalStateException("DB-A01 Lead tests require their explicit environment flag");
        }
        if (!DbA01LeadTestTarget.MANIFEST_PATH.equals(
                System.getenv(DbA01LeadTestTarget.MANIFEST_FLAG))) {
            throw new IllegalStateException("DB-A01 Lead tests require their exact child manifest");
        }
        context.addBeanFactoryPostProcessor(beanFactory -> {
            if (beanFactory.getBeanNamesForType(
                    DbA01LeadRuntimeConfiguration.class, false, false).length == 0) {
                throw new IllegalStateException(
                        "DB-A01 Lead tests require the guarded runtime configuration");
            }
        });
    }
}

