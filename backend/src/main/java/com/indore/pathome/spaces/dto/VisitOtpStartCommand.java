package com.indore.pathome.spaces.dto;

import java.util.UUID;

public record VisitOtpStartCommand(Integer generation, String code, UUID operationId) {}
