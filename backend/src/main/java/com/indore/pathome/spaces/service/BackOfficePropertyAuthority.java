package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.Listing;
import com.indore.pathome.spaces.entity.PropertyUploadDraft;
import com.indore.pathome.spaces.repository.EmployeeProfileRepository;
import com.indore.pathome.spaces.repository.ListingRepository;
import com.indore.pathome.spaces.repository.PropertyUploadDraftRepository;
import com.indore.pathome.spaces.repository.StaffAccessGrantRepository;
import com.indore.pathome.spaces.repository.UserRepository;
import com.indore.pathome.spaces.security.PathomeAuthenticationDetails;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

/**
 * Action-specific admission for the existing back-office property uploader and its legacy review
 * operations. This is an adapter over established persisted authority facts, not a new permission
 * engine: current role, active EmployeeProfile, effective STAFF_ADMIN@GLOBAL, and existing record
 * ownership remain authoritative.
 */
@Service
public class BackOfficePropertyAuthority {

    public enum Action {
        SINGLE_PARSE(true, true),
        SINGLE_CREATE(true, true),
        BATCH_PARSE(true, true),
        BATCH_CREATE(true, true),
        OWN_DRAFT(true, true),
        UPLOADER_MEDIA(true, true),
        MANUAL_PROPERTY(false, true),
        FAILED_UPLOAD(false, true),
        LEGACY_REVIEW(false, true);

        private final boolean subAdminAllowed;
        private final boolean adminRequiresGlobalGrant;

        Action(boolean subAdminAllowed, boolean adminRequiresGlobalGrant) {
            this.subAdminAllowed = subAdminAllowed;
            this.adminRequiresGlobalGrant = adminRequiresGlobalGrant;
        }
    }

    /**
     * A request-bound authority verdict. It is deliberately package-internal: callers may pass it back
     * for a recheck, but cannot construct one to assert authority.
     */
    public record Actor(Authentication credential, Long userId, String adminKey, String persistedRole) {
        public Actor {
            Objects.requireNonNull(credential, "credential must not be null");
            if (userId == null || userId <= 0 || adminKey == null || adminKey.isBlank()
                    || persistedRole == null || persistedRole.isBlank()) {
                throw new IllegalArgumentException("An authority verdict requires a resolved User identity");
            }
        }
    }

    private static final String ROLE_ADMIN = "ROLE_ADMIN";
    private static final String ROLE_SUB_ADMIN = "ROLE_SUB_ADMIN";

    private final UserRepository users;
    private final EmployeeProfileRepository profiles;
    private final StaffAccessGrantRepository grants;
    private final PropertyUploadDraftRepository drafts;
    private final ListingRepository listings;
    private final TransactionTemplate transactionTemplate;

    public BackOfficePropertyAuthority(UserRepository users, EmployeeProfileRepository profiles,
                                       StaffAccessGrantRepository grants,
                                       PropertyUploadDraftRepository drafts,
                                       ListingRepository listings,
                                       PlatformTransactionManager transactionManager) {
        this.users = Objects.requireNonNull(users);
        this.profiles = Objects.requireNonNull(profiles);
        this.grants = Objects.requireNonNull(grants);
        this.drafts = Objects.requireNonNull(drafts);
        this.listings = Objects.requireNonNull(listings);
        this.transactionTemplate = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    }

    /** Entry admission from the request credential. It never consults JWT role claims as live facts. */
    public Actor require(Action action) {
        return decide(action, currentCredential());
    }

    /** Entry admission bound to the exact credential the controller received. */
    public Actor require(Action action, Authentication authentication) {
        return decide(action, requireCurrentCredential(authentication));
    }

    /** Verifies the caller cannot substitute another legacy owner key for its own persisted identity. */
    public Actor requireDraft(Action action, String suppliedAdminKey) {
        Actor actor = require(action);
        if (!actor.adminKey().equals(normalizeAdminKey(suppliedAdminKey))) {
            throw new AccessDeniedException("Authenticated authority does not match the draft owner");
        }
        return actor;
    }

    /**
     * Sub-admins may touch media only when the listing carries durable provenance to one of their own
     * existing drafts. Legacy Admins retain existing live authority over administrative media.
     */
    public Actor requireUploaderMedia(Listing listing) {
        Actor actor = require(Action.UPLOADER_MEDIA);
        verifyUploaderMediaRelationship(actor, listing);
        return actor;
    }

    private void verifyUploaderMediaRelationship(Actor actor, Listing listing) {
        if (ROLE_ADMIN.equals(actor.persistedRole())) {
            return;
        }
        String draftId = baseDraftId(listing == null ? null : listing.getOriginDraftId());
        PropertyUploadDraft draft = draftId == null ? null : drafts.findByDraftId(draftId).orElse(null);
        if (draft == null || !actor.adminKey().equals(normalizeAdminKey(draft.getAdminId()))) {
            throw new AccessDeniedException("The property is not associated with an authorized draft");
        }
    }

    public Actor requireUploaderMediaById(Long listingId) {
        if (listingId == null || listingId <= 0) {
            throw new AccessDeniedException("A property relationship is required for this media action");
        }
        Listing listing = listings.findById(listingId)
                .orElseThrow(() -> new AccessDeniedException("The property relationship is unavailable"));
        return requireUploaderMedia(listing);
    }

    /**
     * Runs one short database-only listing mutation with live evidence held before the listing row lock.
     * Provider transfers and parser work must remain outside this method.
     */
    public <T> T withListingMutation(Action action, Long listingId, Function<Listing, T> mutation) {
        if (listingId == null || listingId <= 0) {
            throw new AccessDeniedException("A property relationship is required for this action");
        }
        return transactionTemplate.execute(status -> {
            Actor initial = require(action);
            holdEvidence(action, initial.userId(), initial.persistedRole());
            listings.lockForSchedulingById(listingId)
                    .orElseThrow(() -> new AccessDeniedException("The property relationship is unavailable"));
            Actor current = decide(action, initial.credential());
            Listing listing = listings.findById(listingId)
                    .orElseThrow(() -> new AccessDeniedException("The property relationship is unavailable"));
            if (action == Action.UPLOADER_MEDIA) {
                verifyUploaderMediaRelationship(current, listing);
            }
            return mutation.apply(listing);
        });
    }

    /** Holds short-lived evidence around a database-only side effect without a listing row lock. */
    public void withMutationEvidence(Action action, Runnable mutation) {
        transactionTemplate.execute(status -> {
            Actor initial = require(action);
            holdEvidence(action, initial.userId(), initial.persistedRole());
            decide(action, initial.credential());
            mutation.run();
            return null;
        });
    }

    /**
     * Admission for a mutation that commits in the current transaction. Profile, grant and User rows
     * are held in the established profile -> grant -> User order before fresh scalar role/email reads.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Actor requireForMutation(Action action, String draftId) {
        Actor initial = require(action);
        holdEvidence(action, initial.userId(), initial.persistedRole());
        Actor current = decide(action, initial.credential());
        if (draftId != null) {
            verifyDraftRelationship(current, draftId);
        }
        return current;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Actor requireDraftForMutation(Action action, String draftId) {
        Actor current = requireForMutation(action, null);
        verifyDraftRelationship(current, draftId);
        return current;
    }

    /** Rechecks the same credential after a later resource lock and before the mutation is flushed. */
    public void revalidate(Actor actor, Action action, String draftId) {
        if (actor == null) {
            throw new AccessDeniedException("Current back-office property authority is required");
        }
        Authentication current = requireCurrentCredential(actor.credential());
        Actor redecided = decide(action, current);
        if (!actor.userId().equals(redecided.userId())) {
            throw new AccessDeniedException("Current back-office property authority is required");
        }
        verifyDraftRelationship(redecided, draftId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void holdEvidenceForCurrentMutation(Action action) {
        Actor current = require(action);
        holdEvidence(action, current.userId(), current.persistedRole());
        decide(action, current.credential());
    }

    private void verifyDraftRelationship(Actor actor, String draftId) {
        if (draftId == null || draftId.isBlank()) {
            return;
        }
        PropertyUploadDraft draft = drafts.findByDraftId(draftId.trim()).orElse(null);
        if (draft == null || !actor.adminKey().equals(normalizeAdminKey(draft.getAdminId()))) {
            throw new AccessDeniedException("Authenticated authority does not own the referenced draft");
        }
    }

    private void holdEvidence(Action action, Long userId, String persistedRole) {
        profiles.findSharedByUserId(userId)
                .filter(profile -> profile.isStaffActive())
                .orElseThrow(() -> new AccessDeniedException("Active staff profile is required"));
        if (ROLE_ADMIN.equals(persistedRole) && action.adminRequiresGlobalGrant) {
            if (grants.findEffectiveGlobalAdminSharedByUserId(userId).isEmpty()) {
                throw new AccessDeniedException("Current global Staff Admin authority is required");
            }
        }
        users.findSharedById(userId)
                .orElseThrow(() -> new AccessDeniedException("Authenticated User is unavailable"));
    }

    private Actor decide(Action action, Authentication credential) {
        Long userId = stableUserId(credential);
        String persistedRole = users.findRoleValueById(userId)
                .orElseThrow(() -> new AccessDeniedException("Authenticated User is unavailable"));
        String email = users.findEmailValueById(userId)
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new AccessDeniedException("Authenticated User identity is unavailable"));

        String claimedRole = singleRole(credential);
        if (!persistedRole.equals(claimedRole) || !credential.getName().equalsIgnoreCase(email)) {
            throw new AccessDeniedException("The credential does not match the current User identity");
        }
        if (!profiles.existsByUserIdAndStaffActiveTrue(userId)) {
            throw new AccessDeniedException("Active staff profile is required");
        }

        boolean admin = ROLE_ADMIN.equals(persistedRole);
        boolean subAdmin = ROLE_SUB_ADMIN.equals(persistedRole);
        if (!admin && !(subAdmin && action.subAdminAllowed)) {
            throw new AccessDeniedException("Current back-office property authority is required");
        }
        if (admin && action.adminRequiresGlobalGrant && !grants.hasEffectiveGlobalAdmin(userId)) {
            throw new AccessDeniedException("Current global Staff Admin authority is required");
        }
        return new Actor(credential, userId, normalizeAdminKey(email), persistedRole);
    }

    private static Authentication currentCredential() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new AccessDeniedException("Authenticated User identity required");
        }
        return authentication;
    }

    private static Authentication requireCurrentCredential(Authentication authentication) {
        Authentication current = currentCredential();
        if (authentication != current) {
            throw new AccessDeniedException("The authority verdict belongs to a different request");
        }
        return current;
    }

    private static Long stableUserId(Authentication authentication) {
        if (authentication.getDetails() instanceof PathomeAuthenticationDetails details
                && details.getUserId() != null && details.getUserId() > 0) {
            return details.getUserId();
        }
        throw new AccessDeniedException("Authenticated User identity required");
    }

    private static String singleRole(Authentication authentication) {
        String matched = null;
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            String value = authority.getAuthority();
            if (!ROLE_ADMIN.equals(value) && !ROLE_SUB_ADMIN.equals(value)) {
                continue;
            }
            if (matched != null && !matched.equals(value)) {
                throw new AccessDeniedException("Ambiguous back-office role authority");
            }
            matched = value;
        }
        if (matched == null) {
            throw new AccessDeniedException("Current back-office property authority is required");
        }
        return matched;
    }

    private static String normalizeAdminKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String baseDraftId(String originDraftId) {
        if (originDraftId == null || originDraftId.isBlank()) return null;
        int separator = originDraftId.indexOf(':');
        return (separator < 0 ? originDraftId : originDraftId.substring(0, separator)).trim();
    }
}
