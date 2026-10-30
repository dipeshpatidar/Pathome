package com.indore.pathome.spaces.support;

import com.indore.pathome.spaces.PathomeSpacesApplication;
import com.indore.pathome.spaces.service.CloudinaryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** Genuine startup-to-shutdown observation on the exact V63 runtime target. */
@EnabledIfEnvironmentVariable(named = "PATHOME_OG_V63_CONNECTED", matches = "true")
class OgV63LifecycleIntegrationTest {
    private static final String[] STARTUP_ARGUMENTS = {
            "--spring.datasource.url=" + OgV63Target.URL,
            "--spring.datasource.username=" + OgV63Target.RUNTIME,
            "--spring.flyway.enabled=false",
            "--spring.jpa.hibernate.ddl-auto=validate",
            "--spring.jpa.properties.hibernate.default_schema=public",
            "--spring.task.scheduling.enabled=false",
            "--spring.sql.init.mode=never",
            "--spring.main.allow-bean-definition-overriding=true",
            "--spring.jpa.show-sql=false",
            "--APP_FIRST_ADMIN_PROVISIONING_ENABLED=false",
            "--server.address=127.0.0.1",
            "--server.forward-headers-strategy=none",
            "--app.jwt.secret=pathome-og-v63-connected-jwt-secret-32-bytes",
            "--pathome.visit.otp.hmac-secret=pathome-og-v63-connected-otp-secret-32-bytes",
            "--pathome.crm.contact-encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
            "--pathome.crm.contact-encryption-key-id=og-v63-primary",
            "--pathome.crm.public-origin=https://pathome.test",
            "--pathome.crm.meta-source-enabled=false",
            "--pathome.crm.phone-verification-simulator-enabled=true",
            "--pathome.crm.secure-cookies=false",
            "--pathome.crm.phone-delivery-poll-delay-ms=3600000",
            "--pathome.visit.outbox.poll-delay-ms=3600000",
            "--pathome.visit.no-show-settlement-delay-ms=3600000",
            "--pathome.visit.entitlement-reservation-poll-delay-ms=3600000",
            "--pathome.visit.overrun-alert-poll-delay-ms=3600000",
            "--search.learning.autonomous.enabled=false",
            "--cloudinary.cloud-name=hbgzetjy",
            "--cloudinary.api-key=og-v63-test-key",
            "--cloudinary.api-secret=og-v63-test-secret"
    };

    @Test
    void startupAndShutdownNeitherScheduleUnauthorizedWorkNorMutateOperationalRows() {
        Map<String, String> before = OgV63SideEffectObserver.snapshot();
        SpringApplication application =
                new SpringApplication(PathomeSpacesApplication.class, OgV63RuntimeConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        try (ConfigurableApplicationContext context = application.run(STARTUP_ARGUMENTS)) {
            OgV63RuntimeConfiguration.validateSettings(context.getEnvironment()::getProperty);
            assertFalse(context.containsBeanDefinition(
                    FreshIntegrationRuntimeConfiguration.SCHEDULED_PROCESSOR));
            assertFalse(context.containsBeanDefinition("dataInitializer"));
            assertFalse(context.containsBeanDefinition("searchLearningStartupConfig"));
            assertInstanceOf(FreshIntegrationProviderGuard.class,
                    context.getBean(CloudinaryService.class));
            assertEquals(List.of(), OgV63SideEffectObserver.differences(
                    before, OgV63SideEffectObserver.snapshot()),
                    "application startup changed operational rows");
        }
        assertEquals(List.of(), OgV63SideEffectObserver.differences(
                before, OgV63SideEffectObserver.snapshot()),
                "application shutdown changed operational rows");
    }
}
