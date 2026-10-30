package com.indore.pathome.spaces.support;

import com.cloudinary.Cloudinary;
import com.indore.pathome.spaces.service.CloudinaryService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.config.BeanPostProcessor;
import com.indore.pathome.spaces.repository.PropertyMediaAssetRepository;

@TestConfiguration(proxyBeanMethods = false)
public class OgV63RuntimeConfiguration {
    static final Map<String,String> REQUIRED = Map.ofEntries(
            Map.entry("spring.datasource.url", OgV63Target.URL),
            Map.entry("spring.datasource.username", OgV63Target.RUNTIME),
            Map.entry("spring.flyway.enabled", "false"),
            Map.entry("spring.jpa.hibernate.ddl-auto", "validate"),
            Map.entry("spring.jpa.properties.hibernate.default_schema", "public"),
            Map.entry("spring.task.scheduling.enabled", "false"),
            Map.entry("spring.sql.init.mode", "never"),
            Map.entry("APP_FIRST_ADMIN_PROVISIONING_ENABLED", "false"),
            Map.entry("server.address", "127.0.0.1"),
            Map.entry("server.forward-headers-strategy", "none"),
            Map.entry("spring.main.allow-bean-definition-overriding", "true"));

    static void validateSettings(java.util.function.Function<String,String> lookup) {
        REQUIRED.forEach((key,value) -> OgV63Target.require(value.equals(lookup.apply(key)), "unsafe Spring setting: " + key));
    }

    @Bean(name = "dataSource", destroyMethod = "close")
    DataSource dataSource() {
        var target = OgV63Target.requireTarget(OgV63Target.RUNTIME, true);
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(OgV63Target.URL); config.setUsername(target.role()); config.setPassword(target.password());
        config.setMaximumPoolSize(3); config.setMinimumIdle(0); config.setConnectionTimeout(4000);
        config.setValidationTimeout(3000); config.setInitializationFailTimeout(4000);
        config.setPoolName("og-v63-runtime"); config.setReadOnly(true);
        return new RunOwnedSchemaDataSource(new HikariDataSource(config), new FreshIntegrationTarget.ConnectionPolicy() {
            public void prepare(Connection connection) throws SQLException {
                target.prepare(connection); target.requireReceipt(connection);
            }
            public void beforeStatement(Connection connection) throws SQLException { target.verify(connection); }
        });
    }

    @Bean @Primary CloudinaryService ogV63ProviderDenial(Cloudinary cloudinary) {
        return new FreshIntegrationProviderGuard(cloudinary);
    }

    @Bean static BeanFactoryPostProcessor startupGuard(org.springframework.core.env.Environment environment) {
        validateSettings(environment::getProperty);
        return beanFactory -> {
            BeanDefinitionRegistry registry = (BeanDefinitionRegistry) beanFactory;
            for (String name : new String[]{FreshIntegrationRuntimeConfiguration.SCHEDULED_PROCESSOR,
                    "dataInitializer", "searchLearningStartupConfig"}) {
                if (registry.containsBeanDefinition(name)) registry.removeBeanDefinition(name);
            }
        };
    }

    @Bean static BeanPostProcessor mediaCheckpointDecorator() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof PropertyMediaAssetRepository repository) {
                    return OgV63MediaCheckpoint.decorate(repository);
                }
                return bean;
            }
        };
    }
}
