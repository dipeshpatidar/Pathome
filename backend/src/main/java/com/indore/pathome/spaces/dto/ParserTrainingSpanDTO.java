package com.indore.pathome.spaces.dto;

public record ParserTrainingSpanDTO(
        String field,
        int start,
        int end,
        String text,
        boolean corrected) {
}
