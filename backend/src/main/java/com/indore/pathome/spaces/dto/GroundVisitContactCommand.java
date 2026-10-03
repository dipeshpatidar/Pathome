package com.indore.pathome.spaces.dto;

import java.time.Instant;
import java.util.UUID;

public record GroundVisitContactCommand(String outcome, Instant tenantEtaAt, String reasonCode, UUID operationId) {}
