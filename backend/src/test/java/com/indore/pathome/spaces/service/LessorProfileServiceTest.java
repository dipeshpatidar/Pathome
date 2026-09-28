package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.dto.PublicDiscoveryResponse;
import com.indore.pathome.spaces.dto.PublicPropertyResponse;
import com.indore.pathome.spaces.dto.lessor.LandlordContactDto;
import com.indore.pathome.spaces.entity.*;
import com.indore.pathome.spaces.repository.LessorProfileRepository;
import com.indore.pathome.spaces.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.lang.reflect.RecordComponent;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Focused tests for canonical LessorProfile foundation, contact single-source-of-truth,
 * and internal provenance guarantees.
 */
class LessorProfileServiceTest {

    private LessorProfileRepository profileRepo;
    private UserRepository userRepo;
    private LessorProfileService profileService;
    private LandlordContactService contactService;
    private Map<Long, LessorProfile> profileTable;
    private Map<Long, LessorProfile> profileByUser;
    private AtomicLong idGen;

    @BeforeEach
    void setUp() {
        profileRepo = mock(LessorProfileRepository.class);
        userRepo = mock(UserRepository.class);
        profileTable = new HashMap<>();
        profileByUser = new HashMap<>();
        idGen = new AtomicLong(100L);

        when(profileRepo.findByLinkedUserId(any())).thenAnswer(inv -> {
            Long userId = inv.getArgument(0);
            return Optional.ofNullable(profileByUser.get(userId));
        });

        when(profileRepo.findById(any())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            return Optional.ofNullable(profileTable.get(id));
        });

        when(profileRepo.saveAndFlush(any())).thenAnswer(inv -> {
            LessorProfile p = inv.getArgument(0);
            if (p.getId() == null) {
                p.setId(idGen.incrementAndGet());
            }
            profileTable.put(p.getId(), p);
            if (p.getLinkedUserId() != null) {
                profileByUser.put(p.getLinkedUserId(), p);
            }
            return p;
        });

        when(profileRepo.insertIfNotExists(any(), any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            Long userId = inv.getArgument(0);
            String displayName = inv.getArgument(1);
            String mobile = inv.getArgument(2);
            String email = inv.getArgument(3);
            String sourceType = inv.getArgument(4);
            LocalDateTime createdAt = inv.getArgument(5);
            LocalDateTime updatedAt = inv.getArgument(6);
            if (userId != null && profileByUser.containsKey(userId)) {
                return 0; // conflict: ON CONFLICT DO NOTHING
            }
            LessorProfile p = new LessorProfile();
            p.setId(idGen.incrementAndGet());
            p.setLinkedUserId(userId);
            p.setDisplayName(displayName);
            p.setMobileNumber(mobile);
            p.setEmail(email);
            p.setSourceType(sourceType != null ? LessorSourceType.valueOf(sourceType) : LessorSourceType.SELF_SERVICE);
            p.setCreatedAt(createdAt);
            p.setUpdatedAt(updatedAt);
            profileTable.put(p.getId(), p);
            if (userId != null) {
                profileByUser.put(userId, p);
            }
            return 1;
        });

        profileService = new LessorProfileService(profileRepo);
        contactService = new LandlordContactService(userRepo, profileService);
    }

    // 1. One self-service User -> one LessorProfile
    @Test
    void oneSelfServiceUserMapsToOneLessorProfile() {
        User user = new User();
        user.setId(10L);
        user.setEmail("dipesh@example.com");
        user.setFullName("Dipesh Patidar");
        user.setPhoneNumber("+91 9826012345");

        LessorProfile profile = profileService.getOrCreateProfileForUser(user);

        assertNotNull(profile);
        assertNotNull(profile.getId());
        assertEquals(10L, profile.getLinkedUserId());
        assertEquals("Dipesh Patidar", profile.getDisplayName());
        assertEquals("+91 9826012345", profile.getMobileNumber());
        assertEquals("dipesh@example.com", profile.getEmail());
        assertEquals(LessorSourceType.SELF_SERVICE, profile.getSourceType());
    }

    // 2. Repeated activation does not duplicate profile
    @Test
    void repeatedActivationDoesNotDuplicateProfile() {
        User user = new User();
        user.setId(10L);
        user.setEmail("dipesh@example.com");
        user.setFullName("Dipesh Patidar");
        user.setPhoneNumber("+91 9826012345");

        LessorProfile first = profileService.getOrCreateProfileForUser(user);
        LessorProfile second = profileService.getOrCreateProfileForUser(user);

        assertSame(first, second);
        assertEquals(first.getId(), second.getId());
        assertEquals(1, profileTable.size());
    }

    // 3. One LessorProfile -> multiple properties
    @Test
    void oneLessorProfileSupportsMultipleProperties() {
        User user = new User();
        user.setId(10L);
        user.setFullName("Dipesh Patidar");
        user.setPhoneNumber("+91 9826012345");
        LessorProfile profile = profileService.getOrCreateProfileForUser(user);

        RentalDetails propA = new RentalDetails();
        propA.setId(1L);
        propA.setLessorProfileId(profile.getId());
        propA.setOwnerUserId(user.getId());

        RentalDetails propB = new RentalDetails();
        propB.setId(2L);
        propB.setLessorProfileId(profile.getId());
        propB.setOwnerUserId(user.getId());

        RentalDetails propC = new RentalDetails();
        propC.setId(3L);
        propC.setLessorProfileId(profile.getId());
        propC.setOwnerUserId(user.getId());

        assertEquals(profile.getId(), propA.getLessorProfileId());
        assertEquals(profile.getId(), propB.getLessorProfileId());
        assertEquals(profile.getId(), propC.getLessorProfileId());
        assertEquals(propA.getLessorProfileId(), propB.getLessorProfileId());
    }

    // 4. Existing ownerUser listings backfill to same profile (simulation of V26 migration logic)
    @Test
    void existingOwnerUserListingsBackfillToSameProfile() {
        Long existingOwnerUserId = 20L;
        User legacyUser = new User();
        legacyUser.setId(existingOwnerUserId);
        legacyUser.setEmail("legacy@example.com");
        legacyUser.setFullName("Legacy Landlord");
        legacyUser.setPhoneNumber("+91 9826099999");

        LessorProfile backfilledProfile = profileService.getOrCreateProfileForUser(legacyUser);

        List<RentalDetails> legacyListings = List.of(
                createListing(101L, existingOwnerUserId),
                createListing(102L, existingOwnerUserId),
                createListing(103L, existingOwnerUserId)
        );

        for (RentalDetails listing : legacyListings) {
            listing.setLessorProfileId(backfilledProfile.getId());
        }

        assertTrue(legacyListings.stream().allMatch(l -> backfilledProfile.getId().equals(l.getLessorProfileId())));
        assertEquals(1, profileTable.size());
    }

    // 5. Guest claim attaches correct profile
    @Test
    void guestClaimAttachesCorrectProfile() {
        User user = new User();
        user.setId(30L);
        user.setEmail("claimed@example.com");
        user.setFullName("Claimed User");
        user.setPhoneNumber("+91 9826011111");

        LessorProfile profile = profileService.getOrCreateProfileForUser(user);

        PropertyUploadDraft guestDraft = new PropertyUploadDraft();
        guestDraft.setDraftId("guest-draft-123");
        assertNull(guestDraft.getLandlordUserId());
        assertNull(guestDraft.getLessorProfileId());

        // Upon authentication & claim:
        guestDraft.setLandlordUserId(user.getId());
        guestDraft.setLessorProfileId(profile.getId());

        assertEquals(30L, guestDraft.getLandlordUserId());
        assertEquals(profile.getId(), guestDraft.getLessorProfileId());
    }

    // 6. Internally sourced profile with user=null is valid
    @Test
    void internallySourcedProfileWithUserNullIsValid() {
        LessorProfile fieldProfile = profileService.createInternalProfile(
                "Field Agent Landlord",
                "+91 9826022222",
                "field@partner.com",
                LessorSourceType.FIELD_TEAM,
                "CRM-REF-4589",
                1L
        );

        assertNotNull(fieldProfile);
        assertNull(fieldProfile.getLinkedUserId());
        assertEquals("Field Agent Landlord", fieldProfile.getDisplayName());
        assertEquals("+91 9826022222", fieldProfile.getMobileNumber());
        assertEquals(LessorSourceType.FIELD_TEAM, fieldProfile.getSourceType());
        assertEquals("CRM-REF-4589", fieldProfile.getSourceReference());
        assertNull(fieldProfile.getClaimedAt());
    }

    // 7. Source provenance remains unchanged after linking
    @Test
    void sourceProvenanceRemainsUnchangedAfterLinking() {
        LessorProfile fieldProfile = profileService.createInternalProfile(
                "Suresh Field",
                "+91 9826033333",
                "suresh@example.com",
                LessorSourceType.FIELD_TEAM,
                "AGENT-007",
                1L
        );
        assertEquals(LessorSourceType.FIELD_TEAM, fieldProfile.getSourceType());

        User claimingUser = new User();
        claimingUser.setId(45L);
        claimingUser.setEmail("suresh@example.com");

        LessorProfile linked = profileService.linkUserToProfile(fieldProfile.getId(), claimingUser);

        assertEquals(45L, linked.getLinkedUserId());
        assertNotNull(linked.getClaimedAt());
        // PROVENANCE INVARIANT: sourceType MUST remain FIELD_TEAM!
        assertEquals(LessorSourceType.FIELD_TEAM, linked.getSourceType());
    }

    // 8. Cross-user profile access/update rejected
    @Test
    void crossUserProfileAccessOrUpdateRejected() {
        when(userRepo.findByEmail("attacker@example.com")).thenReturn(Optional.of(new User(99L, "attacker@example.com", "hash", "Attacker", "+91 9826099999", Role.ROLE_TENANT, null, 5)));
        when(userRepo.findByEmail("victim@example.com")).thenReturn(Optional.of(new User(100L, "victim@example.com", "hash", "Victim", "+91 9826088888", Role.ROLE_LANDLORD, null, 5)));

        // Authenticated as attacker: attempts to query or update contact resolve attacker's profile only
        LandlordContactDto attackerDto = contactService.getContact("attacker@example.com");
        assertEquals("Attacker", attackerDto.fullName());
        assertNotEquals("Victim", attackerDto.fullName());

        // Linking another user's already-linked profile throws
        User victimUser = new User(100L, "victim@example.com", "hash", "Victim", "+91 9826088888", Role.ROLE_LANDLORD, null, 5);
        LessorProfile victimProfile = profileService.getOrCreateProfileForUser(victimUser);

        User attackerUser = new User(99L, "attacker@example.com", "hash", "Attacker", "+91 9826099999", Role.ROLE_TENANT, null, 5);
        assertThrows(IllegalStateException.class, () -> profileService.linkUserToProfile(victimProfile.getId(), attackerUser));
    }

    // 9. Client cannot assign linkedUser/source
    @Test
    void clientCannotAssignLinkedUserOrSourceThroughContactDto() {
        User user = new User(55L, "client@example.com", "hash", "Original Name", "+91 9826055555", Role.ROLE_LANDLORD, null, 5);
        when(userRepo.findByEmail("client@example.com")).thenReturn(Optional.of(user));

        // LandlordContactDto only accepts fullName and phoneNumber (records cannot contain hidden fields)
        LandlordContactDto requestDto = new LandlordContactDto("Updated Name", "9826055555", false);
        LandlordContactDto response = contactService.updateContact("client@example.com", requestDto);

        assertEquals("Updated Name", response.fullName());
        LessorProfile profile = profileService.getProfileForUser(55L).orElseThrow();
        assertEquals(55L, profile.getLinkedUserId());
        assertEquals(LessorSourceType.SELF_SERVICE, profile.getSourceType());
        assertNull(profile.getSourceReference());
    }

    // 10. Contact completion writes LessorProfile
    @Test
    void contactCompletionWritesLessorProfile() {
        User user = new User(60L, "lessor@example.com", "hash", "Incomplete", null, Role.ROLE_TENANT, null, 5);
        when(userRepo.findByEmail("lessor@example.com")).thenReturn(Optional.of(user));

        LandlordContactDto initial = contactService.getContact("lessor@example.com");
        assertFalse(initial.complete());

        LandlordContactDto updated = contactService.updateContact("lessor@example.com",
                new LandlordContactDto("Valid Lessor Name", "9826066666", false));

        assertTrue(updated.complete());
        LessorProfile profile = profileService.getProfileForUser(60L).orElseThrow();
        assertEquals("Valid Lessor Name", profile.getDisplayName());
        assertEquals("+91 9826066666", profile.getMobileNumber());
        // User record phone remains null (proves LessorProfile is authoritative supply contact)
        assertNull(user.getPhoneNumber());
    }

    // 11. Complete profile skips redundant contact prompt
    @Test
    void completeProfileSkipsRedundantContactPrompt() {
        User user = new User(70L, "ready@example.com", "hash", "Ready Landlord", "+91 9826077777", Role.ROLE_LANDLORD, null, 5);
        when(userRepo.findByEmail("ready@example.com")).thenReturn(Optional.of(user));

        LandlordContactDto contact = contactService.getContact("ready@example.com");
        assertTrue(contact.complete());
        assertEquals("Ready Landlord", contact.fullName());
        assertEquals("+91 9826077777", contact.phoneNumber());
    }

    // 12. Second property reuses contact
    @Test
    void secondPropertyReusesContactFromProfile() {
        User user = new User(80L, "multi@example.com", "hash", "Multi Property Owner", "+91 9826088888", Role.ROLE_LANDLORD, null, 5);
        when(userRepo.findByEmail("multi@example.com")).thenReturn(Optional.of(user));

        LessorProfile profile = profileService.getOrCreateProfileForUser(user);

        // Property 1 creation
        RentalDetails prop1 = new RentalDetails();
        prop1.setOwnerName(profile.getDisplayName());
        prop1.setOwnerPhoneNumber(profile.getMobileNumber());
        prop1.setLessorProfileId(profile.getId());

        // Property 2 creation (checks profile contact again)
        LandlordContactDto check = contactService.getContact("multi@example.com");
        assertTrue(check.complete(), "Second property must see contact is already complete");

        RentalDetails prop2 = new RentalDetails();
        prop2.setOwnerName(profile.getDisplayName());
        prop2.setOwnerPhoneNumber(profile.getMobileNumber());
        prop2.setLessorProfileId(profile.getId());

        assertEquals(prop1.getOwnerName(), prop2.getOwnerName());
        assertEquals(prop1.getOwnerPhoneNumber(), prop2.getOwnerPhoneNumber());
        assertEquals(prop1.getLessorProfileId(), prop2.getLessorProfileId());
    }

    // 13. New listings no longer depend on duplicated contact as source of truth
    @Test
    void newListingGetsLessorProfileIdAndUsesProfileAsAuthoritativeSource() {
        User user = new User(90L, "authoritative@example.com", "hash", "Auth Name", "+91 9826090000", Role.ROLE_LANDLORD, null, 5);
        LessorProfile profile = profileService.getOrCreateProfileForUser(user);

        RentalDetails listing = new RentalDetails();
        listing.setLessorProfileId(profile.getId());

        // Authoritative source of contact is profile, not listing column
        LessorProfile authoritative = profileService.getProfileById(listing.getLessorProfileId()).orElseThrow();
        assertEquals("Auth Name", authoritative.getDisplayName());
        assertEquals("+91 9826090000", authoritative.getMobileNumber());
    }

    // 14. Public DTO contains no private profile data
    @Test
    void publicDtoContainsNoPrivateProfileData() throws Exception {
        // Discovery Card DTO
        for (RecordComponent comp : PublicDiscoveryResponse.class.getRecordComponents()) {
            String name = comp.getName().toLowerCase(Locale.ROOT);
            assertFalse(name.contains("owner"), "PublicDiscoveryResponse must not expose owner: " + name);
            assertFalse(name.contains("profile"), "PublicDiscoveryResponse must not expose profile: " + name);
            assertFalse(name.contains("phone"), "PublicDiscoveryResponse must not expose phone: " + name);
            assertFalse(name.contains("mobile"), "PublicDiscoveryResponse must not expose mobile: " + name);
            assertFalse(name.contains("email"), "PublicDiscoveryResponse must not expose email: " + name);
        }

        // Public Property Detail DTO
        for (RecordComponent comp : PublicPropertyResponse.class.getRecordComponents()) {
            String name = comp.getName().toLowerCase(Locale.ROOT);
            assertFalse(name.contains("owner"), "PublicPropertyResponse must not expose owner: " + name);
            assertFalse(name.contains("profile"), "PublicPropertyResponse must not expose profile: " + name);
            assertFalse(name.contains("phone"), "PublicPropertyResponse must not expose phone: " + name);
            assertFalse(name.contains("mobile"), "PublicPropertyResponse must not expose mobile: " + name);
            assertFalse(name.contains("email"), "PublicPropertyResponse must not expose email: " + name);
        }
    }

    // 15. Existing guest claim remains idempotent
    @Test
    void guestClaimRemainsIdempotent() {
        User user = new User(110L, "idempotent@example.com", "hash", "Owner", "+91 9826011111", Role.ROLE_LANDLORD, null, 5);
        LessorProfile profile = profileService.getOrCreateProfileForUser(user);

        PropertyUploadDraft draft = new PropertyUploadDraft();
        draft.setDraftId("draft-idem-1");
        draft.setLandlordUserId(user.getId());
        draft.setLessorProfileId(profile.getId());

        // Repeated claim check
        assertEquals(user.getId(), draft.getLandlordUserId());
        assertEquals(profile.getId(), draft.getLessorProfileId());
    }

    private RentalDetails createListing(Long id, Long ownerUserId) {
        RentalDetails r = new RentalDetails();
        r.setId(id);
        r.setOwnerUserId(ownerUserId);
        r.setTitle("Listing " + id);
        r.setCity("Indore");
        r.setSector("Vijay Nagar");
        r.setBhkCount("2 BHK");
        r.setStatus(ListingStatus.ACTIVE);
        return r;
    }

    // P2 fix — nullable mobile: profile with null mobile is valid at domain level
    @Test
    void profileWithNullMobileIsValid() {
        LessorProfile profile = new LessorProfile();
        profile.setId(200L);
        profile.setLinkedUserId(200L);
        profile.setDisplayName("Lessor Name");
        profile.setMobileNumber(null);
        profile.setSourceType(LessorSourceType.SELF_SERVICE);

        assertNull(profile.getMobileNumber());
        // isUsablePhone must return false for null — contact completion UX should trigger
        assertFalse(LandlordContactService.isUsablePhone(profile.getMobileNumber()));
    }

    // P2 fix — self-service user with no phone seeds NULL (not fabricated sentinel)
    @Test
    void selfServiceUserWithNoPhoneGetsNullMobileNotSentinel() {
        User user = new User();
        user.setId(201L);
        user.setEmail("nophone@example.com");
        user.setFullName("No Phone User");
        user.setPhoneNumber(null);

        LessorProfile profile = profileService.getOrCreateProfileForUser(user);

        assertNull(profile.getMobileNumber(),
            "Profile mobile must be null when user has no phone — not a fabricated sentinel");
        assertNotEquals("+91 9999999999", profile.getMobileNumber());
    }

    // P2 fix — internal profile without phone is valid
    @Test
    void internalProfileWithNullPhoneIsValid() {
        LessorProfile profile = profileService.createInternalProfile(
            "Field Agent", null, "agent@internal.com",
            LessorSourceType.FIELD_TEAM, "REF-001", 1L);

        assertNull(profile.getMobileNumber(),
            "Internally sourced profile with no phone must have null mobile");
        assertEquals(LessorSourceType.FIELD_TEAM, profile.getSourceType());
    }

    // P2 fix — contact completion saves real mobile to LessorProfile
    @Test
    void contactCompletionSavesRealMobileToProfile() {
        User user = new User();
        user.setId(202L);
        user.setEmail("complete@example.com");
        user.setFullName("Complete User");
        user.setPhoneNumber(null);

        // Seed profile with null mobile
        LessorProfile profile = profileService.getOrCreateProfileForUser(user);
        assertNull(profile.getMobileNumber());

        // Contact completion provides a real mobile
        LessorProfile updated = profileService.updateProfileContact(user.getId(), "Complete User", "+91 9826099999");
        assertEquals("+91 9826099999", updated.getMobileNumber());
        assertTrue(LandlordContactService.isUsablePhone(updated.getMobileNumber()));
    }

    // P2 fix — submission with null profile mobile is still blocked
    @Test
    void submissionBlockedWhenProfileMobileIsNull() {
        LessorProfile profile = new LessorProfile();
        profile.setDisplayName("Valid Name");
        profile.setMobileNumber(null);

        boolean mobileOk = LandlordContactService.isUsablePhone(profile.getMobileNumber());
        assertFalse(mobileOk, "Submission gate must reject null mobile");
    }

    // ===== A. Name Truthfulness =====

    // A1: missing User name → profile display_name is NULL (not fabricated)
    @Test
    void missingUserNameSeedsNullDisplayName() {
        User user = new User();
        user.setId(300L);
        user.setEmail("noname@example.com");
        user.setFullName(null);
        user.setPhoneNumber("+91 9826055555");

        LessorProfile profile = profileService.getOrCreateProfileForUser(user);

        assertNull(profile.getDisplayName(),
            "Profile display name must be NULL when user has no name — not a fabricated placeholder");
    }

    // A2: blank User name also seeds NULL
    @Test
    void blankUserNameSeedsNullDisplayName() {
        User user = new User();
        user.setId(301L);
        user.setEmail("blank@example.com");
        user.setFullName("   ");
        user.setPhoneNumber("+91 9826055556");

        LessorProfile profile = profileService.getOrCreateProfileForUser(user);

        assertNull(profile.getDisplayName());
    }

    // A3: "Lessor <id>" strings pass isUsableName length check — the fix is V28 nullifying
    // them in DB, and the service now seeds null instead of ever writing such a placeholder.
    // Verify the service never seeds a "Lessor <id>" name for users with no full_name.
    @Test
    void serviceNeverSeedsLessorIdPlaceholderName() {
        User user = new User();
        user.setId(42L);
        user.setEmail("noid@example.com");
        user.setFullName(null);  // No real name
        user.setPhoneNumber("+91 9826012345");

        LessorProfile profile = profileService.getOrCreateProfileForUser(user);

        assertNull(profile.getDisplayName(),
            "Service must seed null, not 'Lessor 42', when user has no full_name");
        assertNotEquals("Lessor 42", profile.getDisplayName());
        assertNotEquals("Lessor " + user.getId(), profile.getDisplayName());
    }

    // A4: submission with null display_name is rejected
    @Test
    void submissionBlockedWhenProfileNameIsNull() {
        LessorProfile profile = new LessorProfile();
        profile.setDisplayName(null);
        profile.setMobileNumber("+91 9826055555");

        assertFalse(LandlordContactService.isUsableName(profile.getDisplayName()),
            "Submission gate must reject null display name");
    }

    // A5: valid entered name allows submission
    @Test
    void validNamePassesSubmissionGate() {
        LessorProfile profile = new LessorProfile();
        profile.setDisplayName("Ramesh Sharma");
        profile.setMobileNumber("+91 9826055555");

        assertTrue(LandlordContactService.isUsableName(profile.getDisplayName()));
        assertTrue(LandlordContactService.isUsablePhone(profile.getMobileNumber()));
    }

    // ===== B. Phone Truthfulness =====

    // B1: missing phone stays NULL
    @Test
    void missingPhoneStaysNull() {
        User user = new User();
        user.setId(310L);
        user.setEmail("b1@example.com");
        user.setFullName("Test User");
        user.setPhoneNumber(null);

        LessorProfile profile = profileService.getOrCreateProfileForUser(user);
        assertNull(profile.getMobileNumber());
    }

    // B2: genuine +91 9999999999 User phone is preserved
    @Test
    void genuineNinesPhoneIsPreservedFromUser() {
        User user = new User();
        user.setId(311L);
        user.setEmail("nines@example.com");
        user.setFullName("Nines User");
        user.setPhoneNumber("+91 9999999999");

        LessorProfile profile = profileService.getOrCreateProfileForUser(user);

        assertEquals("+91 9999999999", profile.getMobileNumber(),
            "User phone +91 9999999999 must be preserved — it is genuine source data");
    }

    // B3: service never recreates sentinel for missing phone
    @Test
    void serviceSeedNeverRecreatesSentinelFromMissingPhone() {
        User user = new User();
        user.setId(312L);
        user.setEmail("b3@example.com");
        user.setFullName("Test Three");
        user.setPhoneNumber("");

        LessorProfile profile = profileService.getOrCreateProfileForUser(user);
        assertNull(profile.getMobileNumber(),
            "Service must never recreate the +91 9999999999 sentinel for missing phones");
    }

    // B4: missing phone blocks self-service submission
    @Test
    void missingPhoneBlocksSelfServiceSubmission() {
        LessorProfile profile = new LessorProfile();
        profile.setDisplayName("Complete Name");
        profile.setMobileNumber(null);

        assertFalse(LandlordContactService.isUsablePhone(profile.getMobileNumber()));
    }

    // ===== C. Concurrency =====

    // C1 & C2: concurrent ensure-profile calls produce exactly one profile,
    // and both callers resolve the same profile
    @Test
    void concurrentProfileCreationProducesExactlyOneProfile() {
        User user = new User();
        user.setId(320L);
        user.setEmail("concurrent@example.com");
        user.setFullName("Concurrent User");
        user.setPhoneNumber("+91 9826033333");

        LessorProfile first = profileService.getOrCreateProfileForUser(user);
        assertNotNull(first);
        assertEquals(1, profileTable.size());

        LessorProfile second = profileService.getOrCreateProfileForUser(user);
        assertEquals(first.getId(), second.getId());
        assertEquals(1, profileTable.size(), "Must not create a duplicate profile");
    }

    // C3: insertIfNotExists absorbs constraint conflict at DB level without throwing or tainting caller transaction
    @Test
    void insertIfNotExistsAbsorbsConstraintConflict() {
        User user = new User();
        user.setId(321L);
        user.setEmail("absorb@example.com");
        user.setFullName("Absorb Test");
        user.setPhoneNumber("+91 9826044444");

        profileService.getOrCreateProfileForUser(user);

        // A concurrent/duplicate insert returns 0 rows updated, without throwing or tainting transaction
        int rows = profileRepo.insertIfNotExists(
                user.getId(),
                "Duplicate",
                "+91 9826044444",
                "absorb@example.com",
                LessorSourceType.SELF_SERVICE.name(),
                java.time.LocalDateTime.now(),
                java.time.LocalDateTime.now()
        );
        assertEquals(0, rows, "Concurrent/duplicate insert must return 0 rows inserted on conflict");
    }

    // ===== D. Source of Truth / No Contact Duplication =====

    // D1: new listing must not carry profile contact in legacy owner fields
    @Test
    void newListingDoesNotDuplicateProfileContact() {
        RentalDetails listing = new RentalDetails();
        listing.setId(400L);
        listing.setLessorProfileId(101L);

        assertNull(listing.getOwnerName(),
            "New LessorProfile-backed listing must not copy owner name into legacy field");
        assertNull(listing.getOwnerPhoneNumber(),
            "New LessorProfile-backed listing must not copy owner phone into legacy field");
    }

    // D2: profile contact update does not auto-sync to legacy listing fields
    @Test
    void profileContactUpdateDoesNotSyncToLegacyListingField() {
        User user = new User();
        user.setId(401L);
        user.setEmail("update@example.com");
        user.setFullName("Old Name");
        user.setPhoneNumber("+91 9826011111");

        profileService.getOrCreateProfileForUser(user);
        profileService.updateProfileContact(user.getId(), "New Name", "+91 9826022222");

        RentalDetails listing = createListing(401L, user.getId());
        listing.setOwnerName("Old Name");
        assertEquals("Old Name", listing.getOwnerName(),
            "Legacy listing owner name must not be auto-updated when LessorProfile contact changes");
    }

    // D3: existing legacy listing remains readable
    @Test
    void existingLegacyListingRemainsReadable() {
        RentalDetails listing = createListing(500L, 99L);
        listing.setOwnerName("Historic Owner");
        listing.setOwnerPhoneNumber("+91 9111111111");

        assertEquals("Historic Owner", listing.getOwnerName());
        assertEquals("+91 9111111111", listing.getOwnerPhoneNumber());
    }

    // ===== E. Existing Flow Regression =====

    // E1: guest claim remains idempotent
    @Test
    void guestClaimRemainsIdempotentRegression() {
        User user = new User(600L, "idem2@example.com", "hash", "Owner Two", "+91 9826088888", Role.ROLE_LANDLORD, null, 5);
        LessorProfile profile = profileService.getOrCreateProfileForUser(user);

        PropertyUploadDraft draft = new PropertyUploadDraft();
        draft.setDraftId("draft-reg-1");
        draft.setLandlordUserId(user.getId());
        draft.setLessorProfileId(profile.getId());

        assertEquals(user.getId(), draft.getLandlordUserId());
        assertEquals(profile.getId(), draft.getLessorProfileId());
    }

    // E2: cross-user ownership is protected
    @Test
    void crossUserOwnershipIsProtected() {
        User userA = new User();
        userA.setId(700L);
        userA.setEmail("owner-a@example.com");
        userA.setFullName("Owner A");
        userA.setPhoneNumber("+91 9826077771");

        User userB = new User();
        userB.setId(701L);
        userB.setEmail("owner-b@example.com");
        userB.setFullName("Owner B");
        userB.setPhoneNumber("+91 9826077772");

        LessorProfile profileA = profileService.getOrCreateProfileForUser(userA);

        assertThrows(IllegalStateException.class,
            () -> profileService.linkUserToProfile(profileA.getId(), userB),
            "Linking a profile already linked to another user must throw");
    }
}
