package com.indore.pathome.spaces.dto;

import java.util.List;

public record GroundExecutiveCoverageCommand(Long expectedVersion, List<GroundExecutiveCoverageEntry> coverage) {}
