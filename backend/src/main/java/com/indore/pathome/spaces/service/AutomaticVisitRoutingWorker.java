package com.indore.pathome.spaces.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class AutomaticVisitRoutingWorker {
    private static final Logger log = LoggerFactory.getLogger(AutomaticVisitRoutingWorker.class);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AutomaticRoutingControlService control;
    private final AutomaticVisitRoutingService routing;

    public AutomaticVisitRoutingWorker(AutomaticRoutingControlService control,
                                       AutomaticVisitRoutingService routing) {
        this.control = control;
        this.routing = routing;
    }

    @Scheduled(
            fixedDelayString = "${pathome.automatic-routing.poll-delay-ms:3000}",
            initialDelayString = "${pathome.automatic-routing.initial-delay-ms:5000}")
    public void poll() {
        if (!running.compareAndSet(false, true)) return;
        try {
            if (!control.isProcessingEnabled()) return;
            List<Long> due = routing.dueTaskIds(AutomaticVisitRoutingService.DEFAULT_BATCH_SIZE);
            for (Long taskId : due) {
                try {
                    routing.processTask(taskId);
                } catch (RuntimeException failure) {
                    log.warn("Automatic visit routing task {} failed: {}", taskId,
                            failure.getClass().getSimpleName());
                    try {
                        routing.recordTechnicalFailure(taskId, classify(failure));
                    } catch (RuntimeException diagnosticFailure) {
                        // The original due row is still durable; a later poll can recover it.
                        log.warn("Could not record routing retry for task {}: {}", taskId,
                                diagnosticFailure.getClass().getSimpleName());
                    }
                }
            }
        } finally {
            running.set(false);
        }
    }

    private static String classify(RuntimeException failure) {
        String name = failure.getClass().getSimpleName().toLowerCase();
        if (name.contains("timeout") || name.contains("unavailable") || name.contains("lock")) {
            return "ROUTING_LOCK_OR_TIMEOUT";
        }
        return "ROUTING_TECHNICAL_FAILURE";
    }
}
