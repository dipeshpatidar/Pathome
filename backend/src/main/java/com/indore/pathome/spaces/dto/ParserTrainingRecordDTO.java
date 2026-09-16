package com.indore.pathome.spaces.dto;

import java.util.List;

public record ParserTrainingRecordDTO(
        String exampleId,
        String datasetPartition,
        String inputSource,
        String text,
        String parserVersion,
        List<ParserTrainingSpanDTO> spans) {
}
