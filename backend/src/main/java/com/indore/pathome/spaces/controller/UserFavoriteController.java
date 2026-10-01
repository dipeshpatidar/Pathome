package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.FavoritePropertyIdsResponse;
import com.indore.pathome.spaces.dto.FavoritePropertyResponse;
import com.indore.pathome.spaces.dto.PublicDiscoveryPage;
import com.indore.pathome.spaces.security.PathomeAuthenticationDetails;
import com.indore.pathome.spaces.repository.UserRepository;
import com.indore.pathome.spaces.service.UserFavoriteService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@RestController
@RequestMapping("/api/v1/favorites")
public class UserFavoriteController {
    private final UserRepository users;
    private final UserFavoriteService favorites;

    public UserFavoriteController(UserRepository users, UserFavoriteService favorites) {
        this.users = users;
        this.favorites = favorites;
    }

    @GetMapping
    public ResponseEntity<FavoritePropertyIdsResponse> list(Authentication authentication,
            @RequestParam(required = false) List<Long> propertyIds) {
        Long userId = authenticatedUserId(authentication);
        return ResponseEntity.ok(new FavoritePropertyIdsResponse(
                favorites.listSavedActiveProperties(userId, propertyIds)));
    }

    @GetMapping("/properties")
    public ResponseEntity<PublicDiscoveryPage> listSavedPropertySummaries(Authentication authentication,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "6") int size) {
        return ResponseEntity.ok(favorites.listSavedActiveListings(authenticatedUserId(authentication), page, size));
    }

    @PutMapping("/{propertyId}")
    public ResponseEntity<FavoritePropertyResponse> save(Authentication authentication,
            @PathVariable Long propertyId) {
        favorites.save(authenticatedUserId(authentication), propertyId);
        return ResponseEntity.ok(new FavoritePropertyResponse(propertyId, true));
    }

    @DeleteMapping("/{propertyId}")
    public ResponseEntity<Void> remove(Authentication authentication, @PathVariable Long propertyId) {
        favorites.remove(authenticatedUserId(authentication), propertyId);
        return ResponseEntity.noContent().build();
    }

    private Long authenticatedUserId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getName())) {
            throw new org.springframework.security.access.AccessDeniedException("Authentication required");
        }
        if (!(authentication.getDetails() instanceof PathomeAuthenticationDetails details)
                || details.getUserId() == null || details.getUserId() <= 0) {
            throw new org.springframework.security.access.AccessDeniedException("Authenticated user identity unavailable");
        }
        return users.findById(details.getUserId())
                .map(user -> user.getId())
                .orElseThrow(() -> new EntityNotFoundException("Account unavailable"));
    }
}
