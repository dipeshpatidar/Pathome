package com.indore.pathome.spaces.dto;

import java.util.UUID;

public record GroundVisitMoreTimeCommand(Integer additionalMinutes, UUID operationId) {}
