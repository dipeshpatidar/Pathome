package com.indore.pathome.spaces.support;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import javax.sql.DataSource;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class FreshIntegrationRuntimeGuardTest {

    @Test
    void everyPreflightFailureMakesZeroDatasourceAttempts() {
        AtomicInteger attempts = new AtomicInteger();
        java.util.function.Function<FreshIntegrationTarget.Target, DataSource> factory = target -> {
            attempts.incrementAndGet();
            return mock(DataSource.class);
        };

        Map<String, String> missingFlag = Map.of();
        assertThrows(FreshIntegrationTarget.RefusedException.class,
                () -> FreshIntegrationRuntimeConfiguration.guardedDataSource(
                        missingFlag, factory));

        Map<String, String> missingCustody = Map.of(
                FreshIntegrationTarget.TEST_ENABLEMENT_FLAG, "true");
        assertThrows(FreshIntegrationTarget.RefusedException.class,
                () -> FreshIntegrationRuntimeConfiguration.guardedDataSource(
                        missingCustody, factory));

        Map<String, String> incorrectEndpoint = Map.of(
                FreshIntegrationTarget.TEST_ENABLEMENT_FLAG, "true",
                FreshIntegrationTarget.MANIFEST_FLAG,
                "/private/tmp/pathome-it-backoffice-20261025-01/does-not-exist.json");
        assertThrows(FreshIntegrationTarget.RefusedException.class,
                () -> FreshIntegrationRuntimeConfiguration.guardedDataSource(
                        incorrectEndpoint, factory));

        assertThrows(FreshIntegrationTarget.RefusedException.class,
                () -> FreshIntegrationRuntimeConfiguration.guardedDataSource(
                        () -> {
                            throw new FreshIntegrationTarget.RefusedException(
                                    "stale migration receipt");
                        },
                        factory));
        assertEquals(0, attempts.get());
    }

    @Test
    void missingImportFailsBeforeAnyDatasourceCanBeCreated() {
        DefaultListableBeanFactory missing = new DefaultListableBeanFactory();
        assertThrows(IllegalStateException.class,
                () -> FreshIntegrationHarnessContextInitializer.requireGuardedConfiguration(missing));
        DefaultListableBeanFactory present = new DefaultListableBeanFactory();
        present.registerBeanDefinition(
                FreshIntegrationRuntimeConfiguration.class.getName(),
                new RootBeanDefinition(FreshIntegrationRuntimeConfiguration.class));
        FreshIntegrationHarnessContextInitializer.validate(
                true, true, "/custody.json");
        FreshIntegrationHarnessContextInitializer.requireGuardedConfiguration(present);
    }
}
