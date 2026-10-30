package com.indore.pathome.spaces.support;

import com.cloudinary.Cloudinary;
import com.indore.pathome.spaces.service.CloudinaryService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/** Runtime-only datasource for the isolated DB-A01 Lead database. */
@TestConfiguration(proxyBeanMethods = false)
public class DbA01LeadRuntimeConfiguration {
    static final String SCHEDULED_PROCESSOR =
            "org.springframework.context.annotation.internalScheduledAnnotationProcessor";

    @Bean(name = "dataSource", destroyMethod = "close")
    @Primary
    DataSource dataSource() {
        DbA01LeadTestTarget.Target target = DbA01LeadTestTarget.requireFromEnvironment();
        return pool("db-a01-lead-runtime", target.runtimeUrl(), target.runtimeRole(),
                target.runtimePassword());
    }

    @Bean(name = "leadFixtureDataSource", destroyMethod = "close")
    DataSource leadFixtureDataSource() {
        DbA01LeadTestTarget.Target target = DbA01LeadTestTarget.requireFromEnvironment();
        return pool("db-a01-lead-fixture", target.migratorUrl(), target.migratorRole(),
                target.migratorPassword());
    }

    @Bean(name = "leadFixtureJdbc")
    JdbcTemplate leadFixtureJdbc(
            @Qualifier("leadFixtureDataSource") DataSource fixtureDataSource) {
        return new JdbcTemplate(fixtureDataSource);
    }

    @Bean(name = "runtimeJdbc")
    @Primary
    JdbcTemplate runtimeJdbc(@Qualifier("dataSource") DataSource runtimeDataSource) {
        return new JdbcTemplate(runtimeDataSource);
    }

    @Bean
    @Primary
    CloudinaryService dbA01LeadProviderGuard(Cloudinary cloudinary) {
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
                        "DB-A01 Lead tests require spring.task.scheduling.enabled=false");
            }
            if (beanFactory.containsBeanDefinition(SCHEDULED_PROCESSOR)) {
                ((BeanDefinitionRegistry) beanFactory).removeBeanDefinition(SCHEDULED_PROCESSOR);
            }
        };
    }

    private static DataSource pool(String name, String url, String user, String password) {
        HikariConfig config = new HikariConfig();
        config.setPoolName(name);
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setSchema("public");
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
