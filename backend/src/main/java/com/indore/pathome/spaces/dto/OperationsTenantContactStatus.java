package com.indore.pathome.spaces.dto;

/**
 * Constrained outcome of an authorized tenant-contact read.
 *
 * <p>{@code CONTACT_UNAVAILABLE} is the fail-closed result: the caller is authorized to coordinate
 * the visit, but the tenant's account has no usable name and mobile. It never carries a partial
 * contact value.</p>
 */
public enum OperationsTenantContactStatus {
    CONTACT_AVAILABLE,
    CONTACT_UNAVAILABLE
}
