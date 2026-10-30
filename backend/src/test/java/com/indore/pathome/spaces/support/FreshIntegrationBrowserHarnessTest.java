package com.indore.pathome.spaces.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import com.indore.pathome.spaces.config.DataInitializer;
import com.indore.pathome.spaces.service.CloudinaryService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test-only server lifetime for the bounded genuine-backend Chromium batch.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "server.port=8080",
        "spring.task.scheduling.enabled=false",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.show-sql=false",
        "APP_FIRST_ADMIN_PROVISIONING_ENABLED=false",
        "app.jwt.secret=pathome-browser-v60-jwt-secret-32-bytes",
        "pathome.visit.otp.hmac-secret=pathome-browser-v60-otp-secret",
        "cloudinary.api-key=browser-test-key",
        "cloudinary.api-secret=browser-test-secret",
        "pathome.visit.outbox.poll-delay-ms=3600000",
        "pathome.visit.no-show-settlement-delay-ms=3600000",
        "pathome.visit.entitlement-reservation-poll-delay-ms=3600000",
        "pathome.visit.overrun-alert-poll-delay-ms=3600000"
})
@Import(FreshIntegrationRuntimeConfiguration.class)
@ContextConfiguration(initializers = FreshIntegrationHarnessContextInitializer.class)
@EnabledIfEnvironmentVariable(named = "PATHOME_FRESH_BROWSER_HARNESS", matches = "true")
class FreshIntegrationBrowserHarnessTest {
    @Autowired private ApplicationContext context;
    @Autowired private CloudinaryService provider;
    @Autowired private DataInitializer initializer;

    @Test
    void guardedContextHasNoSchedulerProviderOrFirstAdminBootstrap() {
        assertTrue(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class).isEmpty(),
                "scheduled annotation processor must be absent from the live context");
        TaskScheduler scheduler = context.getBeanProvider(TaskScheduler.class).getIfAvailable();
        if (scheduler instanceof ThreadPoolTaskScheduler threadPool) {
            assertTrue(threadPool.getScheduledThreadPoolExecutor().getQueue().isEmpty(),
                    "no scheduled worker may remain queued");
        }
        assertThrows(IllegalStateException.class,
                () -> provider.findExistingResourceByUploadRequestId("guarded-send", false));
        assertTrue(Boolean.FALSE.equals(
                org.springframework.test.util.ReflectionTestUtils.getField(
                        initializer, "firstAdminProvisioningEnabled")),
                "first-Admin provisioning must be disabled");
    }

    @Test
    void remainsAvailableUntilBrowserBatchSignalsCompletion() throws Exception {
        String configuredStopFile = System.getenv("PATHOME_BROWSER_HARNESS_STOP_FILE");
        assertTrue(configuredStopFile != null && !configuredStopFile.isBlank(),
                "browser harness stop file must be explicit");
        Path stopFile = Path.of(configuredStopFile).toAbsolutePath().normalize();
        int maxSeconds = Integer.parseInt(
                System.getenv().getOrDefault("PATHOME_BROWSER_HARNESS_MAX_SECONDS", "600"));
        Instant deadline = Instant.now().plus(Duration.ofSeconds(maxSeconds));
        while (!Files.exists(stopFile) && Instant.now().isBefore(deadline)) {
            Thread.sleep(500);
        }
        assertTrue(Files.exists(stopFile), "browser batch did not signal completion before timeout");
    }
}
