package com.indore.pathome.spaces.support;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.cloudinary.Cloudinary;
import com.indore.pathome.spaces.service.CloudinaryService;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import javax.sql.DataSource;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Runtime-only, explicitly imported test configuration for the future Gate I HTTP campaign.
 *
 * <p>It never migrates, creates a schema, drops an object, or falls back to a default datasource.
 * The scheduled annotation processor is removed before ordinary beans are initialized; long polling
 * delays are deliberately not treated as isolation.</p>
 */
@TestConfiguration(proxyBeanMethods = false)
public class FreshIntegrationRuntimeConfiguration {
    static final String SCHEDULED_PROCESSOR =
            "org.springframework.context.annotation.internalScheduledAnnotationProcessor";

    @Bean(name = "dataSource", destroyMethod = "close")
    DataSource freshIntegrationDataSource() {
        return guardedDataSource(this::dataSource);
    }

    static DataSource guardedDataSource(
            java.util.Map<String, String> environment,
            Function<FreshIntegrationTarget.Target, DataSource> connectionFactory) {
        return guardedDataSource(() -> {
            FreshIntegrationTarget.Target target = FreshIntegrationTarget.requireFromEnvironment(
                    FreshIntegrationTarget.Stage.INTEGRATION,
                    FreshIntegrationTarget.Cohort.BUSINESS,
                    FreshIntegrationTarget.RUNTIME_ROLE,
                    environment);
            FreshIntegrationTarget.requireMigrationReceipt(target);
            return target;
        }, connectionFactory);
    }

    static DataSource guardedDataSource(
            Function<FreshIntegrationTarget.Target, DataSource> connectionFactory) {
        return guardedDataSource(System.getenv(), connectionFactory);
    }

    static DataSource guardedDataSource(
            Supplier<FreshIntegrationTarget.Target> preflight,
            Function<FreshIntegrationTarget.Target, DataSource> connectionFactory) {
        FreshIntegrationTarget.Target target = preflight.get();
        return connectionFactory.apply(target);
    }

    private DataSource dataSource(FreshIntegrationTarget.Target target) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("fresh-integration-runtime");
        config.setJdbcUrl(target.jdbcUrl());
        config.setUsername(target.role());
        config.setPassword(target.password());
        config.setSchema("public");
        config.setConnectionTimeout(5_000);
        config.setValidationTimeout(5_000);
        config.setInitializationFailTimeout(5_000);
        config.setMaximumPoolSize(8);
        config.setMinimumIdle(0);
        config.setAutoCommit(true);
        config.setReadOnly(false);
        config.setRegisterMbeans(false);
        HikariDataSource guardedDelegate = new HikariDataSource(config);
        return FreshIntegrationTarget.address(guardedDelegate, target);
    }

    @Bean
    @Primary
    CloudinaryService freshIntegrationCloudinaryGuard(Cloudinary cloudinary) {
        return new FreshIntegrationProviderGuard(cloudinary);
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    static BeanFactoryPostProcessor disableScheduledWorkers(
            org.springframework.core.env.Environment environment) {
        return beanFactory -> {
            if (!"false".equalsIgnoreCase(
                    environment.getProperty("spring.task.scheduling.enabled", "false"))) {
                throw new IllegalStateException(
                        "Fresh integration tests require spring.task.scheduling.enabled=false");
            }
            if (beanFactory.containsBeanDefinition(SCHEDULED_PROCESSOR)) {
                ((BeanDefinitionRegistry) beanFactory).removeBeanDefinition(SCHEDULED_PROCESSOR);
            }
        };
    }
}
