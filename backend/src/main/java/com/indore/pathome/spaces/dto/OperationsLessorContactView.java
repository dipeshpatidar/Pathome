package com.indore.pathome.spaces.dto;

/**
 * Minimal, least-privilege lessor contact contract for a single authorized operational root.
 *
 * <p>Exactly four fields. No email, address, User/profile identifier, source or internal remark is
 * ever exposed. When the contact cannot be trusted or validated, {@code name} and {@code mobile} are
 * both {@code null} with {@link OperationsLessorContactStatus#CONTACT_UNAVAILABLE}; a partial
 * contact is never returned.</p>
 */
public record OperationsLessorContactView(
        Long listingId,
        String name,
        String mobile,
        OperationsLessorContactStatus status) {

    public static OperationsLessorContactView available(Long listingId, String name, String mobile) {
        return new OperationsLessorContactView(listingId, name, mobile,
                OperationsLessorContactStatus.CONTACT_AVAILABLE);
    }

    public static OperationsLessorContactView unavailable(Long listingId) {
        return new OperationsLessorContactView(listingId, null, null,
                OperationsLessorContactStatus.CONTACT_UNAVAILABLE);
    }
}
