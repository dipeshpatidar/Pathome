package com.indore.pathome.spaces.config;

import com.indore.pathome.spaces.service.SearchLearningService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Warms the in-memory search alias cache after the application context is fully started.
 *
 * <p>Runs after Flyway migrations and JPA repository initialization are complete so that
 * the alias table is guaranteed to exist before the first cache load.</p>
 */
@Component
public class SearchLearningStartupConfig {

    private static final Logger log = LoggerFactory.getLogger(SearchLearningStartupConfig.class);

    private final SearchLearningService searchLearningService;

    public SearchLearningStartupConfig(SearchLearningService searchLearningService) {
        this.searchLearningService = searchLearningService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        try {
            searchLearningService.refreshAliasCache();
        } catch (Exception ex) {
            // Non-critical; search still works without the alias cache warm-up
            log.warn("Search alias cache warm-up failed at startup (non-critical): {}", ex.getMessage());
        }
    }
}
