package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.lessor.LandlordContactDto;
import com.indore.pathome.spaces.entity.LessorProfile;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LandlordContactServiceTest {
    private UserRepository users;
    private com.indore.pathome.spaces.repository.LessorProfileRepository lessorProfileRepo;
    private LessorProfileService lessorProfileService;
    private LandlordContactService service;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        lessorProfileRepo = mock(com.indore.pathome.spaces.repository.LessorProfileRepository.class);
        java.util.Map<Long, LessorProfile> profileByUser = new java.util.HashMap<>();
        when(lessorProfileRepo.findByLinkedUserId(any())).thenAnswer(inv ->
                java.util.Optional.ofNullable(profileByUser.get(inv.getArgument(0))));
        when(lessorProfileRepo.saveAndFlush(any())).thenAnswer(invocation -> {
            LessorProfile p = invocation.getArgument(0);
            if (p.getLinkedUserId() != null) {
                profileByUser.put(p.getLinkedUserId(), p);
            }
            return p;
        });
        when(lessorProfileRepo.insertIfNotExists(any(), any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            Long userId = inv.getArgument(0);
            if (userId != null && !profileByUser.containsKey(userId)) {
                LessorProfile p = new LessorProfile();
                p.setId(userId + 1000L);
                p.setLinkedUserId(userId);
                p.setDisplayName(inv.getArgument(1));
                p.setMobileNumber(inv.getArgument(2));
                p.setEmail(inv.getArgument(3));
                p.setSourceType(com.indore.pathome.spaces.entity.LessorSourceType.SELF_SERVICE);
                profileByUser.put(userId, p);
                return 1;
            }
            return 0;
        });
        lessorProfileService = new LessorProfileService(lessorProfileRepo);
        service = new LandlordContactService(users, lessorProfileService);
    }

    @Test
    void getContactReportsIncompleteWhenNameOrPhoneMissing() {
        User userWithoutPhone = new User();
        userWithoutPhone.setId(1L);
        userWithoutPhone.setEmail("owner@example.com");
        userWithoutPhone.setFullName("John Doe");
        userWithoutPhone.setPhoneNumber(null);
        when(users.findByEmail("owner@example.com")).thenReturn(Optional.of(userWithoutPhone));

        LandlordContactDto result = service.getContact("owner@example.com");
        assertEquals("John Doe", result.fullName());
        assertNull(result.phoneNumber());
        assertFalse(result.complete());

        User userWithoutName = new User();
        userWithoutName.setId(2L);
        userWithoutName.setEmail("noname@example.com");
        userWithoutName.setFullName("");
        userWithoutName.setPhoneNumber("+91 9826012345");
        when(users.findByEmail("noname@example.com")).thenReturn(Optional.of(userWithoutName));

        LandlordContactDto result2 = service.getContact("noname@example.com");
        assertFalse(result2.complete());
        verify(lessorProfileRepo, never()).insertIfNotExists(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void getContactReportsCompleteWhenBothNameAndValidPhoneExist() {
        User completeUser = new User();
        completeUser.setId(3L);
        completeUser.setEmail("owner@example.com");
        completeUser.setFullName("Ramesh Sharma");
        completeUser.setPhoneNumber("+91 9826012345");
        when(users.findByEmail("owner@example.com")).thenReturn(Optional.of(completeUser));

        LandlordContactDto result = service.getContact("owner@example.com");
        assertTrue(result.complete());
        assertEquals("Ramesh Sharma", result.fullName());
        assertEquals("+91 9826012345", result.phoneNumber());
    }

    @Test
    void updateContactBeforeFirstSubmissionPersistsWithoutCreatingProfile() {
        User user = new User();
        user.setId(4L);
        user.setEmail("owner@example.com");
        user.setFullName("Initial Name");
        user.setPhoneNumber(null);
        when(users.findByEmail("owner@example.com")).thenReturn(Optional.of(user));

        LandlordContactDto dto = new LandlordContactDto("Ramesh Sharma", "9826012345", false);
        LandlordContactDto updated = service.updateContact("owner@example.com", dto);

        assertTrue(updated.complete());
        assertEquals("Ramesh Sharma", updated.fullName());
        assertEquals("+91 9826012345", updated.phoneNumber());
        assertEquals("Ramesh Sharma", user.getFullName());
        assertEquals("+91 9826012345", user.getPhoneNumber());
        verify(users).save(user);
        verify(lessorProfileRepo, never()).insertIfNotExists(any(), any(), any(), any(), any(), any(), any());
        assertEquals(updated, service.getContact("owner@example.com"));
    }

    @Test
    void updateContactRejectsInvalidMobileNumbers() {
        User user = new User();
        when(users.findByEmail("owner@example.com")).thenReturn(Optional.of(user));

        // Invalid: starts with 5
        assertThrows(IllegalArgumentException.class, () ->
                service.updateContact("owner@example.com", new LandlordContactDto("Valid Name", "5123456789", false)));

        // Invalid: 9 digits
        assertThrows(IllegalArgumentException.class, () ->
                service.updateContact("owner@example.com", new LandlordContactDto("Valid Name", "982601234", false)));

        // Invalid: non-numeric
        assertThrows(IllegalArgumentException.class, () ->
                service.updateContact("owner@example.com", new LandlordContactDto("Valid Name", "98260abcde", false)));

        // Invalid: null phone
        assertThrows(IllegalArgumentException.class, () ->
                service.updateContact("owner@example.com", new LandlordContactDto("Valid Name", null, false)));
    }

    @Test
    void updateContactRejectsInvalidNames() {
        User user = new User();
        when(users.findByEmail("owner@example.com")).thenReturn(Optional.of(user));

        // Empty / single character
        assertThrows(IllegalArgumentException.class, () ->
                service.updateContact("owner@example.com", new LandlordContactDto("A", "9826012345", false)));

        // Blank
        assertThrows(IllegalArgumentException.class, () ->
                service.updateContact("owner@example.com", new LandlordContactDto("   ", "9826012345", false)));

        // Null
        assertThrows(IllegalArgumentException.class, () ->
                service.updateContact("owner@example.com", new LandlordContactDto(null, "9826012345", false)));
    }

    @Test
    void updateContactThrowsForNonExistentUser() {
        when(users.findByEmail("ghost@example.com")).thenReturn(Optional.empty());
        assertThrows(AccessDeniedException.class, () ->
                service.updateContact("ghost@example.com", new LandlordContactDto("Valid Name", "9826012345", false)));
    }
}
