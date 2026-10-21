package com.indore.pathome.spaces.entity;

public enum LeadStatus {
    NEW,
    QUALIFYING,
    QUALIFIED,
    CLOSED,

    // Retained only so historical rows remain readable. New code must not create these lifecycle states.
    ASSIGNED,
    UNMAPPED,
    CONTACTED,
    STALE
}
