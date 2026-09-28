package com.indore.pathome.spaces.entity;

/**
 * Origin channel through which a LessorProfile was registered in Pathome.
 *
 * <p>Source provenance is strictly immutable: linking an internal profile to a User
 * records claimedAt, but does not overwrite the original sourceType.</p>
 */
public enum LessorSourceType {
    SELF_SERVICE,
    FIELD_TEAM,
    ADMIN,
    CRM,
    PARTNER,
    IMPORT
}
