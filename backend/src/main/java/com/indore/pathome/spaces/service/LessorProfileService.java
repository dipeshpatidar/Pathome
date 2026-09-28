package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.LessorProfile;
import com.indore.pathome.spaces.entity.LessorSourceType;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.repository.LessorProfileRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Service managing canonical LessorProfile lifecycle and source-of-truth contact.
 *
 * <p>Preserves strict separation between authenticated security actor (User)
 * and supply-side business entity (LessorProfile). One self-service User resolves
 * to exactly one LessorProfile, which can own many properties.</p>
 */
@Service
public class LessorProfileService {

    private final LessorProfileRepository lessorProfiles;

    public LessorProfileService(LessorProfileRepository lessorProfiles) {
        this.lessorProfiles = lessorProfiles;
    }

    /**
     * Resolves the canonical LessorProfile for a self-service User, initializing from User contact if needed.
     * Subsequent contact updates mutate LessorProfile exclusively, stopping continuous two-way sync.
     */
    @Transactional
    public LessorProfile getOrCreateProfileForUser(User user) {
        if (user == null || user.getId() == null) {
            throw new IllegalArgumentException("User and user ID must not be null");
        }

        Optional<LessorProfile> existing = lessorProfiles.findByLinkedUserId(user.getId());
        if (existing.isPresent()) {
            return existing.get();
        }

        String displayName = LandlordContactService.isUsableName(user.getFullName())
                ? user.getFullName().trim()
                : (user.getFullName() != null && !user.getFullName().isBlank() ? user.getFullName().trim() : "");

        String mobileNumber = LandlordContactService.isUsablePhone(user.getPhoneNumber())
                ? LandlordContactService.normalizePhone(user.getPhoneNumber())
                : null;

        LessorProfile profile = new LessorProfile();
        profile.setLinkedUserId(user.getId());
        profile.setDisplayName(displayName);
        profile.setMobileNumber(mobileNumber);
        profile.setEmail(user.getEmail());
        profile.setSourceType(LessorSourceType.SELF_SERVICE);
        profile.setCreatedAt(LocalDateTime.now());
        profile.setUpdatedAt(LocalDateTime.now());

        try {
            return lessorProfiles.saveAndFlush(profile);
        } catch (DataIntegrityViolationException ex) {
            // Concurrent creation race condition: re-query the winner
            return lessorProfiles.findByLinkedUserId(user.getId())
                    .orElseThrow(() -> ex);
        }
    }

    /**
     * Authoritatively updates lessor contact on the LessorProfile.
     */
    @Transactional
    public LessorProfile updateProfileContact(Long userId, String cleanName, String cleanPhone) {
        if (userId == null) {
            throw new IllegalArgumentException("User ID must not be null");
        }
        LessorProfile profile = lessorProfiles.findByLinkedUserId(userId)
                .orElseThrow(() -> new EntityNotFoundException("Lessor profile not found for user: " + userId));

        profile.setDisplayName(cleanName);
        profile.setMobileNumber(cleanPhone);
        profile.setUpdatedAt(LocalDateTime.now());
        return lessorProfiles.saveAndFlush(profile);
    }

    /**
     * Creates an internally sourced LessorProfile without a login User (FIELD_TEAM, CRM, ADMIN, PARTNER, IMPORT).
     */
    @Transactional
    public LessorProfile createInternalProfile(String displayName, String mobileNumber, String email,
                                               LessorSourceType sourceType, String sourceReference, Long createdByUserId) {
        if (sourceType == null || sourceType == LessorSourceType.SELF_SERVICE) {
            throw new IllegalArgumentException("Internal profiles must use a non-self-service source type");
        }
        String cleanName = LandlordContactService.sanitizeName(displayName);
        String cleanPhone = (mobileNumber != null && !mobileNumber.isBlank())
                ? LandlordContactService.normalizePhone(mobileNumber)
                : null;

        LessorProfile profile = new LessorProfile();
        profile.setLinkedUserId(null);
        profile.setDisplayName(cleanName);
        profile.setMobileNumber(cleanPhone);
        profile.setEmail(email != null && !email.isBlank() ? email.trim() : null);
        profile.setSourceType(sourceType);
        profile.setSourceReference(sourceReference != null && !sourceReference.isBlank() ? sourceReference.trim() : null);
        profile.setCreatedByUserId(createdByUserId);
        profile.setCreatedAt(LocalDateTime.now());
        profile.setUpdatedAt(LocalDateTime.now());

        return lessorProfiles.saveAndFlush(profile);
    }

    /**
     * Authoritatively links an internally sourced profile to a verified User account.
     * Preserves original sourceType provenance (e.g. FIELD_TEAM remains FIELD_TEAM).
     */
    @Transactional
    public LessorProfile linkUserToProfile(Long profileId, User user) {
        if (profileId == null || user == null || user.getId() == null) {
            throw new IllegalArgumentException("Profile ID and valid User must be provided");
        }
        LessorProfile profile = lessorProfiles.findById(profileId)
                .orElseThrow(() -> new EntityNotFoundException("Lessor profile not found: " + profileId));

        if (profile.getLinkedUserId() != null) {
            if (profile.getLinkedUserId().equals(user.getId())) {
                return profile; // Idempotent
            }
            throw new IllegalStateException("Lessor profile is already linked to another account");
        }

        lessorProfiles.findByLinkedUserId(user.getId()).ifPresent(existing -> {
            if (!existing.getId().equals(profileId)) {
                throw new IllegalStateException("User already has an associated lessor profile");
            }
        });

        profile.setLinkedUserId(user.getId());
        profile.setClaimedAt(LocalDateTime.now());
        profile.setUpdatedAt(LocalDateTime.now());
        // Note: sourceType provenance is strictly preserved
        return lessorProfiles.saveAndFlush(profile);
    }

    @Transactional(readOnly = true)
    public Optional<LessorProfile> getProfileForUser(Long userId) {
        if (userId == null) return Optional.empty();
        return lessorProfiles.findByLinkedUserId(userId);
    }

    @Transactional(readOnly = true)
    public Optional<LessorProfile> getProfileById(Long profileId) {
        if (profileId == null) return Optional.empty();
        return lessorProfiles.findById(profileId);
    }
}
