package com.indore.pathome.spaces.dto;

public record RoutingTaskRecheckCommand(Long expectedTaskVersion, String reasonCode) {
}
