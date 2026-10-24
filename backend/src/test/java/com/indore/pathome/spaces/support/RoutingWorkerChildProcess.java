package com.indore.pathome.spaces.support;

import com.indore.pathome.spaces.PathomeSpacesApplication;
import com.indore.pathome.spaces.service.AutomaticVisitRoutingService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Map;

/**
 * Test-owned disposable worker process for C05 restart/rollback acceptance.
 *
 * <p>This process starts no web listener and does not run the scheduled poll before the explicit
 * task call. The test owns its PID and may terminate it only while the exact disposable target is
 * attested.
 */
public final class RoutingWorkerChildProcess {
    private RoutingWorkerChildProcess() {}

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Exactly one routing task ID is required");
            System.exit(2);
        }
        long taskId;
        try {
            taskId = Long.parseLong(args[0]);
            if (taskId <= 0) throw new NumberFormatException("non-positive");
        } catch (NumberFormatException invalid) {
            System.err.println("Invalid routing task ID");
            System.exit(2);
            return;
        }

        SpringApplication application = new SpringApplication(PathomeSpacesApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setDefaultProperties(Map.of(
                "spring.flyway.enabled", "false",
                "spring.jpa.hibernate.ddl-auto", "none",
                "spring.jpa.show-sql", "false",
                "pathome.automatic-routing.initial-delay-ms", "3600000",
                "pathome.automatic-routing.poll-delay-ms", "3600000",
                "pathome.visit.outbox.poll-delay-ms", "3600000",
                "pathome.visit.no-show-settlement-delay-ms", "3600000",
                "pathome.visit.entitlement-reservation-poll-delay-ms", "3600000",
                "pathome.visit.overrun-alert-poll-delay-ms", "3600000"));

        try (ConfigurableApplicationContext context = application.run()) {
            AutomaticVisitRoutingService routing = context.getBean(AutomaticVisitRoutingService.class);
            System.out.println("CHILD_READY");
            System.out.flush();
            routing.processTask(taskId);
            System.out.println("CHILD_COMPLETE");
            System.out.flush();
        } catch (RuntimeException failure) {
            System.err.println("CHILD_FAILED " + failure.getClass().getSimpleName());
            System.exit(3);
        }
    }
}
