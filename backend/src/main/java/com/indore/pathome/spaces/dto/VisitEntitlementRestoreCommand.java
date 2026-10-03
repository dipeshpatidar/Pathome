package com.indore.pathome.spaces.dto;

import java.util.UUID;

public record VisitEntitlementRestoreCommand(Long expectedSessionVersion, String reasonCode, UUID operationId) {}
