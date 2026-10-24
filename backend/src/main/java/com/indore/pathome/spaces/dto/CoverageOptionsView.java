package com.indore.pathome.spaces.dto;

import java.util.List;

public record CoverageOptionsView(
        CoverageOptionView city,
        List<CoverageOptionView> localities,
        List<CoverageOptionView> teams) {
}
