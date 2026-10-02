package com.indore.pathome.spaces.dto;

public record RemoveVisitSessionItemCommand(
        Long expectedSessionVersion,
        String reason) {}
