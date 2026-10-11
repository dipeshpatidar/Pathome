package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.OperationsLessorContactStatus;
import com.indore.pathome.spaces.dto.OperationsLessorContactView;
import com.indore.pathome.spaces.entity.LessorProfile;
import com.indore.pathome.spaces.entity.LessorSourceType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Focused, container-free coverage for the canonical, fail-closed contact resolver.
 *
 * <p>Proves contact is resolved only through {@code Listing.lessorProfileId -> LessorProfile}, that
 * self-service and internally sourced provenance are both verified, and that a failed contact never
 * yields partial PII.</p>
 */
class OperationsLessorContactResolverTest {

    private static final Long LISTING = 123L;

    private static LessorProfile profile(Long id, Long linkedUserId, String name, String mobile,
            LessorSourceType sourceType) {
        return new LessorProfile(id, linkedUserId, name, mobile, null, sourceType);
    }

    @Test
    void selfServiceProfileLinkedToOwningUserIsAvailableAndNormalized() {
        long owner = 77L;
        LessorProfile profile = profile(5L, owner, "Asha Verma", "9876543210", LessorSourceType.SELF_SERVICE);

        OperationsLessorContactView view = OperationsLessorContactResolver.resolve(LISTING, owner, profile);

        assertEquals(OperationsLessorContactStatus.CONTACT_AVAILABLE, view.status());
        assertEquals(LISTING, view.listingId());
        assertEquals("Asha Verma", view.name());
        assertEquals("+91 9876543210", view.mobile(), "the canonical helper's normalized form is reused");
    }

    @Test
    void internallySourcedUnlinkedProfileIsAvailableWithoutInventingAUser() {
        LessorProfile profile = profile(9L, null, "Field Sourced Lessor", "+91 87654 32109",
                LessorSourceType.FIELD_TEAM);

        OperationsLessorContactView view = OperationsLessorContactResolver.resolve(LISTING, null, profile);

        assertEquals(OperationsLessorContactStatus.CONTACT_AVAILABLE, view.status());
        assertEquals("Field Sourced Lessor", view.name());
        assertEquals("+91 8765432109", view.mobile());
    }

    @Test
    void selfServiceProfileWithoutLinkedUserFailsClosed() {
        LessorProfile profile = profile(9L, null, "Suspicious Lessor", "9876543210",
                LessorSourceType.SELF_SERVICE);

        OperationsLessorContactView view = OperationsLessorContactResolver.resolve(LISTING, null, profile);

        assertUnavailable(view);
    }

    @Test
    void internallySourcedProfileAlreadyLinkedToAnotherUserFailsClosed() {
        LessorProfile profile = profile(9L, 500L, "Field Sourced Lessor", "9876543210",
                LessorSourceType.FIELD_TEAM);

        OperationsLessorContactView view = OperationsLessorContactResolver.resolve(LISTING, null, profile);

        assertUnavailable(view);
    }

    @Test
    void missingProfileAssociationOrRowFailsClosed() {
        assertUnavailable(OperationsLessorContactResolver.resolve(LISTING, 77L, null));
        assertUnavailable(OperationsLessorContactResolver.resolve(null, 77L, null));
    }

    @Test
    void listingOwnerUserDisagreeingWithLinkedUserFailsClosed() {
        LessorProfile profile = profile(5L, 77L, "Asha Verma", "9876543210", LessorSourceType.SELF_SERVICE);

        assertUnavailable(OperationsLessorContactResolver.resolve(LISTING, 78L, profile));
        // A non-null listing owner with an unlinked profile is also a mismatch.
        assertUnavailable(OperationsLessorContactResolver.resolve(LISTING, 78L,
                profile(6L, null, "Asha Verma", "9876543210", LessorSourceType.FIELD_TEAM)));
    }

    @Test
    void invalidPhoneOrMissingNameProducesNoPartialPii() {
        long owner = 77L;
        assertUnavailable(OperationsLessorContactResolver.resolve(LISTING, owner,
                profile(5L, owner, "Asha Verma", "1234", LessorSourceType.SELF_SERVICE)));
        assertUnavailable(OperationsLessorContactResolver.resolve(LISTING, owner,
                profile(5L, owner, "Asha Verma", null, LessorSourceType.SELF_SERVICE)));
        assertUnavailable(OperationsLessorContactResolver.resolve(LISTING, owner,
                profile(5L, owner, null, "9876543210", LessorSourceType.SELF_SERVICE)));
        assertUnavailable(OperationsLessorContactResolver.resolve(LISTING, owner,
                profile(5L, owner, "  ", "9876543210", LessorSourceType.SELF_SERVICE)));
    }

    @Test
    void legacyPlaceholderNameIsRejected() {
        long owner = 77L;
        assertUnavailable(OperationsLessorContactResolver.resolve(LISTING, owner,
                profile(5L, owner, "Lessor 98765", "9876543210", LessorSourceType.SELF_SERVICE)));
    }

    @Test
    void resolutionReadsCurrentProfileContactAndNeverMutatesIt() {
        long owner = 77L;
        LessorProfile profile = profile(5L, owner, "Asha Verma", "9876543210", LessorSourceType.SELF_SERVICE);

        OperationsLessorContactView first = OperationsLessorContactResolver.resolve(LISTING, owner, profile);
        assertEquals("+91 9876543210", first.mobile());

        // The canonical profile changes its number; the resolver must reflect the current value.
        profile.setMobileNumber("9000000001");
        OperationsLessorContactView second = OperationsLessorContactResolver.resolve(LISTING, owner, profile);
        assertEquals("+91 9000000001", second.mobile());

        // No contact or provenance field is ever mutated by a read.
        assertEquals("Asha Verma", profile.getDisplayName());
        assertEquals("9000000001", profile.getMobileNumber());
        assertEquals(owner, profile.getLinkedUserId());
        assertEquals(LessorSourceType.SELF_SERVICE, profile.getSourceType());
    }

    private static void assertUnavailable(OperationsLessorContactView view) {
        assertEquals(OperationsLessorContactStatus.CONTACT_UNAVAILABLE, view.status());
        assertNull(view.name(), "a failed contact must never leak a partial name");
        assertNull(view.mobile(), "a failed contact must never leak a partial mobile");
    }
}
