package com.indore.pathome.spaces.dto;

/**
 * Constrained outcome of an authorized lessor-contact read.
 *
 * <p>{@code CONTACT_UNAVAILABLE} is the fail-closed result: the request was authorized but no
 * trustworthy, usable lessor contact could be resolved for the property. It never carries a partial
 * contact value.</p>
 */
public enum OperationsLessorContactStatus {
    CONTACT_AVAILABLE,
    CONTACT_UNAVAILABLE
}
