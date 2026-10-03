package com.indore.pathome.spaces.dto;

import java.time.Instant;
import java.util.UUID;

public record TenantVisitConfirmationCommand(String action, Instant confirmedEtaAt, UUID operationId,
        Long expectedSessionVersion) {}
