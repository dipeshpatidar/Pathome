package com.indore.pathome.spaces.support;

import com.cloudinary.Cloudinary;
import com.indore.pathome.spaces.service.CloudinaryService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/** Runtime-only guarded configuration for the isolated A1-A7 HTTP and browser cohorts. */
@TestConfiguration(proxyBeanMethods = false)
public class A1A7RuntimeConfiguration {
    static final String SCHEDULED_PROCESSOR =
            "org.springframework.context.annotation.internalScheduledAnnotationProcessor";

    @Bean(name = "dataSource", destroyMethod = "close")
    @Primary
    DataSource dataSource() {
        A1A7IsolatedTarget.Target target =
                A1A7IsolatedTarget.requireFromEnvironment(A1A7IsolatedTarget.RUNTIME_ROLE);
        return pool("a1a7-runtime", target);
    }

    @Bean(name = "a1a7MigratorDataSource", destroyMethod = "close")
    DataSource migratorDataSource() {
        A1A7IsolatedTarget.Target target =
                A1A7IsolatedTarget.requireFromEnvironment(A1A7IsolatedTarget.MIGRATOR_ROLE);
        return pool("a1a7-migrator-fixture", target);
    }

    @Bean(name = "a1a7RunnerJdbc")
    JdbcTemplate runnerJdbc() {
        A1A7IsolatedTarget.Target target =
                A1A7IsolatedTarget.requireFromEnvironment(A1A7IsolatedTarget.RUNNER_ROLE);
        return new JdbcTemplate(pool("a1a7-runner", target));
    }

    @Bean(name = "a1a7MigratorJdbc")
    JdbcTemplate migratorJdbc(javax.sql.DataSource a1a7MigratorDataSource) {
        return new JdbcTemplate(a1a7MigratorDataSource);
    }

    @Bean(name = "jdbcTemplate")
    @Primary
    JdbcTemplate runtimeJdbc(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    @Primary
    CloudinaryService a1a7ProviderGuard(Cloudinary cloudinary) {
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
                        "A1-A7 tests require spring.task.scheduling.enabled=false");
            }
            if (beanFactory.containsBeanDefinition(SCHEDULED_PROCESSOR)) {
                ((BeanDefinitionRegistry) beanFactory).removeBeanDefinition(SCHEDULED_PROCESSOR);
            }
        };
    }

    private static DataSource pool(String name, A1A7IsolatedTarget.Target target) {
        HikariConfig config = new HikariConfig();
        config.setPoolName(name);
        config.setJdbcUrl(target.jdbcUrl());
        config.setUsername(target.role());
        config.setPassword(target.password());
        config.setSchema(target.schema());
        config.setConnectionTimeout(5_000);
        config.setValidationTimeout(5_000);
        config.setInitializationFailTimeout(5_000);
        config.setMaximumPoolSize(8);
        config.setMinimumIdle(0);
        config.setAutoCommit(true);
        config.setRegisterMbeans(false);
        return new HikariDataSource(config);
    }
}
