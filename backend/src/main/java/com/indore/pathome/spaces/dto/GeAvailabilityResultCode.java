package com.indore.pathome.spaces.dto;

/**
 * The only coarse Ground Executive outcome codes an Operations Executive may see from the
 * restricted availability view. Private shift, leave, reservation, travel and commitment blockers
 * are deliberately collapsed into {@link #NO_FEASIBLE_OPTION_FOUND}.
 */
public enum GeAvailabilityResultCode {
    FEASIBLE_OPTION_FOUND,
    COVERAGE_NOT_SUITABLE,
    NO_FEASIBLE_OPTION_FOUND,
    PLAN_NOT_READY,
    EVALUATION_INCOMPLETE
}
