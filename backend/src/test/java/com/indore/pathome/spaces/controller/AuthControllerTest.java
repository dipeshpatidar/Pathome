package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.AuthResponse;
import com.indore.pathome.spaces.dto.LoginRequest;
import com.indore.pathome.spaces.dto.RegisterRequest;
import com.indore.pathome.spaces.entity.Role;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.repository.UserRepository;
import com.indore.pathome.spaces.security.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AuthControllerTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtUtils jwtUtils;

    private AuthController authController;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        authController = new AuthController(userRepository, passwordEncoder, jwtUtils);
    }

    @Test
    @DisplayName("Register with existing email returns 400 Bad Request with EMAIL_ALREADY_REGISTERED error and no leaked details")
    void registerWithExistingEmailReturnsSafeCategorizedError() {
        User existing = new User();
        existing.setId(99L);
        existing.setEmail("existing@example.com");
        existing.setFullName("Private User Name");
        existing.setPhoneNumber("+919876543210");
        existing.setRole(Role.ROLE_TENANT);

        when(userRepository.findByEmail("existing@example.com")).thenReturn(Optional.of(existing));

        RegisterRequest request = new RegisterRequest();
        request.setEmail("existing@example.com");
        request.setPassword("SecretPass123!");
        request.setFullName("Attacker Guess");

        ResponseEntity<?> response = authController.registerUser(request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody() instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, String> body = (Map<String, String>) response.getBody();
        assertEquals("EMAIL_ALREADY_REGISTERED", body.get("error"));
        assertEquals("An account with this email already exists.", body.get("message"));

        // Verify security: No sensitive account info leaked
        assertFalse(body.containsKey("fullName"));
        assertFalse(body.containsKey("phoneNumber"));
        assertFalse(body.containsKey("userId"));
        assertFalse(body.containsKey("username"));
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("Register with new email successfully creates tenant user and returns token")
    void registerWithNewEmailCreatesUser() {
        when(userRepository.findByEmail("new@example.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("Password123!")).thenReturn("hashed-pwd");
        when(jwtUtils.generateToken(any(), anyString(), anyString())).thenReturn("mock-jwt-token");

        User savedUser = new User();
        savedUser.setId(10L);
        savedUser.setEmail("new@example.com");
        savedUser.setFullName("New User");
        savedUser.setRole(Role.ROLE_TENANT);
        savedUser.setFreeVisitsRemaining(5);

        when(userRepository.save(any(User.class))).thenReturn(savedUser);

        RegisterRequest request = new RegisterRequest();
        request.setEmail("new@example.com");
        request.setPassword("Password123!");
        request.setFullName("New User");

        ResponseEntity<?> response = authController.registerUser(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody() instanceof AuthResponse);
        AuthResponse authResponse = (AuthResponse) response.getBody();
        assertEquals("mock-jwt-token", authResponse.getToken());
        assertEquals("new@example.com", authResponse.getEmail());
        assertEquals("ROLE_TENANT", authResponse.getRole());
    }

    @Test
    @DisplayName("Concurrent duplicate registration returns the same safe duplicate-email contract")
    void concurrentDuplicateRegistrationReturnsSafeCategorizedError() {
        User winner = new User();
        winner.setId(20L);
        winner.setEmail("race@example.com");

        when(userRepository.findByEmail("race@example.com"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(passwordEncoder.encode("Password123!")).thenReturn("hashed-pwd");
        when(userRepository.save(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("unique constraint"));

        RegisterRequest request = new RegisterRequest();
        request.setEmail("race@example.com");
        request.setPassword("Password123!");
        request.setFullName("Concurrent User");

        ResponseEntity<?> response = authController.registerUser(request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody() instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, String> body = (Map<String, String>) response.getBody();
        assertEquals("EMAIL_ALREADY_REGISTERED", body.get("error"));
        assertEquals("An account with this email already exists.", body.get("message"));
        assertEquals(2, body.size());
        verify(userRepository, times(2)).findByEmail("race@example.com");
    }

    @Test
    @DisplayName("Unrelated persistence failure is not mislabeled as duplicate email")
    void unrelatedPersistenceFailureIsRethrown() {
        when(userRepository.findByEmail("failure@example.com"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty());
        when(passwordEncoder.encode("Password123!")).thenReturn("hashed-pwd");
        DataIntegrityViolationException failure = new DataIntegrityViolationException("unrelated constraint");
        when(userRepository.save(any(User.class))).thenThrow(failure);

        RegisterRequest request = new RegisterRequest();
        request.setEmail("failure@example.com");
        request.setPassword("Password123!");
        request.setFullName("Persistence Failure");

        DataIntegrityViolationException thrown = assertThrows(
                DataIntegrityViolationException.class,
                () -> authController.registerUser(request)
        );

        assertSame(failure, thrown);
        verify(userRepository, times(2)).findByEmail("failure@example.com");
    }

    @Test
    @DisplayName("Login with invalid credentials returns 401 Unauthorized")
    void loginWithInvalidCredentialsReturns401() {
        when(userRepository.findByEmail("unknown@example.com")).thenReturn(Optional.empty());

        LoginRequest request = new LoginRequest();
        request.setEmail("unknown@example.com");
        request.setPassword("WrongPassword");

        ResponseEntity<?> response = authController.loginUser(request);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    @DisplayName("Login with valid credentials returns 200 with secure session token")
    void loginWithValidCredentialsReturnsToken() {
        User user = new User();
        user.setId(5L);
        user.setEmail("user@example.com");
        user.setPasswordHash("hashed-pw");
        user.setRole(Role.ROLE_TENANT);
        user.setFullName("Valid User");
        user.setFreeVisitsRemaining(3);

        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("CorrectPassword", "hashed-pw")).thenReturn(true);
        when(jwtUtils.generateToken(5L, "user@example.com", "ROLE_TENANT")).thenReturn("valid-jwt");

        LoginRequest request = new LoginRequest();
        request.setEmail("user@example.com");
        request.setPassword("CorrectPassword");

        ResponseEntity<?> response = authController.loginUser(request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody() instanceof AuthResponse);
        AuthResponse authResponse = (AuthResponse) response.getBody();
        assertEquals("valid-jwt", authResponse.getToken());
    }
}
