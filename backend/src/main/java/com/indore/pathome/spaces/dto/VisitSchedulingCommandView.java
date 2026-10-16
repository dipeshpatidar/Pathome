package com.indore.pathome.spaces.dto;

import java.util.UUID;

/** NOT_RECORDED is an observation, never proof an in-flight HTTP command cannot still commit. */
public record VisitSchedulingCommandView(UUID commandId, String state, VisitSchedulingApprovalView approval) {}
