package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.AuthResponse;
import com.indore.pathome.spaces.dto.LoginRequest;
import com.indore.pathome.spaces.dto.RegisterRequest;
import com.indore.pathome.spaces.entity.Role;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.repository.LessorProfileRepository;
import com.indore.pathome.spaces.repository.EmployeeProfileRepository;
import com.indore.pathome.spaces.repository.UserRepository;
import com.indore.pathome.spaces.security.JwtUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;
    private final LessorProfileRepository lessorProfileRepository;
    private final EmployeeProfileRepository employeeProfileRepository;

    public AuthController(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtUtils jwtUtils,
                          LessorProfileRepository lessorProfileRepository, EmployeeProfileRepository employeeProfileRepository) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtils = jwtUtils;
        this.lessorProfileRepository = lessorProfileRepository;
        this.employeeProfileRepository = employeeProfileRepository;
    }

    /**
     * Pathway 1: Conventional Email & Password Registration
     */
    @PostMapping("/register")
    public ResponseEntity<?> registerUser(@RequestBody RegisterRequest request) {
        if (request == null || request.getEmail() == null || request.getEmail().isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "EMAIL_REQUIRED",
                    "message", "Email is required to register with a password."
            ));
        }
        if (userRepository.findByEmail(request.getEmail()).isPresent()) {
            return duplicateEmailResponse();
        }

        User user = new User();
        user.setEmail(request.getEmail());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setFullName(request.getFullName());
        user.setPhoneNumber(request.getPhoneNumber());
        // Public registration must never grant an administrative role. Admin users
        // are provisioned through the protected operational process.
        user.setRole(Role.ROLE_TENANT);
        user.setFreeVisitsRemaining(5);

        try {
            user = userRepository.save(user);
        } catch (DataIntegrityViolationException persistenceFailure) {
            if (userRepository.findByEmail(request.getEmail()).isPresent()) {
                return duplicateEmailResponse();
            }
            throw persistenceFailure;
        }

        String token = jwtUtils.generateToken(user.getId(), user.getEmail(), user.getRole().name());

        AuthResponse response = new AuthResponse(
                token, user.getId(), user.getEmail(), user.getFullName(), user.getRole().name(), user.getFreeVisitsRemaining(), false);
        employeeProfileRepository.findByUserId(user.getId()).ifPresent(profile -> response.setEmployeeRoleType(profile.getRoleType()));
        return ResponseEntity.ok(response);
    }

    private ResponseEntity<Map<String, String>> duplicateEmailResponse() {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "error", "EMAIL_ALREADY_REGISTERED",
                "message", "An account with this email already exists."
        ));
    }

    /**
     * Pathway 1: Conventional Email & Password Login
     */
    @PostMapping("/login")
    public ResponseEntity<?> loginUser(@RequestBody LoginRequest request) {
        Optional<User> userOpt = userRepository.findByEmail(request.getEmail());
        if (userOpt.isEmpty() || request.getPassword() == null ||
                !passwordEncoder.matches(request.getPassword(), userOpt.get().getPasswordHash())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid email or password");
        }

        User user = userOpt.get();
        String token = jwtUtils.generateToken(user.getId(), user.getEmail(), user.getRole().name());

        boolean hasLessorProfile = lessorProfileRepository.existsByLinkedUserId(user.getId());

        AuthResponse response = new AuthResponse(
                token, user.getId(), user.getEmail(), user.getFullName(), user.getRole().name(), user.getFreeVisitsRemaining(), hasLessorProfile);
        employeeProfileRepository.findByUserId(user.getId()).ifPresent(profile -> response.setEmployeeRoleType(profile.getRoleType()));
        return ResponseEntity.ok(response);
    }
}
