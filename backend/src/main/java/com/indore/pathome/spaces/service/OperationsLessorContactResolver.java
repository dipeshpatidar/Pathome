package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.OperationsLessorContactView;
import com.indore.pathome.spaces.entity.LessorProfile;
import com.indore.pathome.spaces.entity.LessorSourceType;

/**
 * Pure, fail-closed lessor-contact resolver for the A1a contact-read seam.
 *
 * <p>Contact is resolved exclusively through the approved canonical relationship
 * {@code Listing.lessorProfileId -> LessorProfile}. Legacy listing/User owner fields are never
 * consulted: {@code listingOwnerUserId} is used only to verify that a self-service link is
 * trustworthy, never as a contact source.</p>
 *
 * <p>Trust rules:</p>
 * <ul>
 *   <li>Self-service (listing has an owner User): {@code ownerUserId} must equal
 *       {@code profile.linkedUserId}. A null or mismatched link fails closed.</li>
 *   <li>Internally sourced (listing has no owner User): the profile must itself be internal
 *       (non-{@code SELF_SERVICE}) and unlinked. A self-service profile without a linked User, or an
 *       internally sourced profile that is already linked to some other User, fails closed.</li>
 * </ul>
 *
 * <p>A usable name and Indian mobile are both required; otherwise the result is
 * {@code CONTACT_UNAVAILABLE} with both contact fields null. The mobile is normalized through the
 * existing canonical helper so the disclosed value matches the rest of the lessor surface.</p>
 */
final class OperationsLessorContactResolver {

    private OperationsLessorContactResolver() {}

    static OperationsLessorContactView resolve(Long listingId, Long listingOwnerUserId, LessorProfile profile) {
        if (listingId == null || profile == null) {
            return OperationsLessorContactView.unavailable(listingId);
        }

        Long linkedUserId = profile.getLinkedUserId();
        if (listingOwnerUserId != null) {
            if (!listingOwnerUserId.equals(linkedUserId)) {
                return OperationsLessorContactView.unavailable(listingId);
            }
        } else {
            if (linkedUserId != null) {
                return OperationsLessorContactView.unavailable(listingId);
            }
            LessorSourceType sourceType = profile.getSourceType();
            if (sourceType == null || sourceType == LessorSourceType.SELF_SERVICE) {
                return OperationsLessorContactView.unavailable(listingId);
            }
        }

        String name = profile.getDisplayName();
        String mobile = profile.getMobileNumber();
        if (!LandlordContactService.isUsableName(name) || !LandlordContactService.isUsablePhone(mobile)) {
            return OperationsLessorContactView.unavailable(listingId);
        }
        return OperationsLessorContactView.available(listingId, name.trim(),
                LandlordContactService.normalizePhone(mobile));
    }
}
