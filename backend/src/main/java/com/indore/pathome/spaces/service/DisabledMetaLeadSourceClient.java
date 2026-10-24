package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.exception.LeadDataProtectionUnavailableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Production default. It never contacts Graph and keeps source activation fail-closed until a
 * separately approved adapter is configured.
 */
@Component
public class DisabledMetaLeadSourceClient implements MetaLeadSourceClient {
    private final boolean enabled;

    public DisabledMetaLeadSourceClient(
            @Value("${pathome.crm.meta-source-enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public boolean isConfigured() {
        return false;
    }

    @Override
    public String fetchLeadPayload(String pageId, String leadgenId, String formId) {
        if (!enabled) {
            throw new LeadDataProtectionUnavailableException(
                    "Meta source extraction is not activated for this environment");
        }
        throw new LeadDataProtectionUnavailableException(
                "Meta source extraction has no approved live adapter for this environment");
    }
}
