package com.indore.pathome.spaces.dto;

public record AssignGroundExecutiveCommand(
        Long expectedSessionVersion,
        Long groundExecutiveUserId) {}
