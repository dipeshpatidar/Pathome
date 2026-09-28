package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.lessor.LandlordContactDto;
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
    private LandlordContactService service;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        service = new LandlordContactService(users);
    }

    @Test
    void getContactReportsIncompleteWhenNameOrPhoneMissing() {
        User userWithoutPhone = new User();
        userWithoutPhone.setEmail("owner@example.com");
        userWithoutPhone.setFullName("John Doe");
        userWithoutPhone.setPhoneNumber(null);
        when(users.findByEmail("owner@example.com")).thenReturn(Optional.of(userWithoutPhone));

        LandlordContactDto result = service.getContact("owner@example.com");
        assertEquals("John Doe", result.fullName());
        assertNull(result.phoneNumber());
        assertFalse(result.complete());

        User userWithoutName = new User();
        userWithoutName.setEmail("noname@example.com");
        userWithoutName.setFullName("");
        userWithoutName.setPhoneNumber("+91 9826012345");
        when(users.findByEmail("noname@example.com")).thenReturn(Optional.of(userWithoutName));

        LandlordContactDto result2 = service.getContact("noname@example.com");
        assertFalse(result2.complete());
    }

    @Test
    void getContactReportsCompleteWhenBothNameAndValidPhoneExist() {
        User completeUser = new User();
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
    void updateContactNormalizesAndSavesValidContact() {
        User user = new User();
        user.setEmail("owner@example.com");
        user.setFullName("Initial Name");
        when(users.findByEmail("owner@example.com")).thenReturn(Optional.of(user));

        LandlordContactDto dto = new LandlordContactDto("Ramesh Sharma", "9826012345", false);
        LandlordContactDto updated = service.updateContact("owner@example.com", dto);

        assertTrue(updated.complete());
        assertEquals("Ramesh Sharma", updated.fullName());
        assertEquals("+91 9826012345", updated.phoneNumber());
        assertEquals("Ramesh Sharma", user.getFullName());
        assertEquals("+91 9826012345", user.getPhoneNumber());
        verify(users).saveAndFlush(user);
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
