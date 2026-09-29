package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.TenantVisitRequestPage;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.repository.UserRepository;
import com.indore.pathome.spaces.service.TenantVisitRequestHistoryService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tenant/visit-requests")
public class TenantVisitRequestController {
    private final UserRepository users;
    private final TenantVisitRequestHistoryService history;

    public TenantVisitRequestController(UserRepository users, TenantVisitRequestHistoryService history) {
        this.users = users;
        this.history = history;
    }

    @GetMapping
    public ResponseEntity<TenantVisitRequestPage> list(Authentication authentication,
                                                       @RequestParam(defaultValue = "0") int page) {
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getName())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        User user = users.findByEmail(authentication.getName())
                .orElseThrow(() -> new AccessDeniedException("Account unavailable"));
        return ResponseEntity.ok(history.listForUser(user.getId(), page));
    }
}
