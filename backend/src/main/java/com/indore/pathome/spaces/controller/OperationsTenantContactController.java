package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.OperationsTenantContactView;
import com.indore.pathome.spaces.dto.OperationsSessionTenantContactView;
import com.indore.pathome.spaces.service.OperationsTenantContactService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * R1-OE-1 secure tenant-contact read routes.
 *
 * <p>Backend-only seam for the currently owning Operations Executive. These routes do not implement
 * calling, messaging, scheduling or any mutation.</p>
 */
@RestController
@RequestMapping("/api/v1/operations")
public class OperationsTenantContactController {

    private final OperationsTenantContactService tenantContact;

    public OperationsTenantContactController(OperationsTenantContactService tenantContact) {
        this.tenantContact = tenantContact;
    }

    @GetMapping("/visit-requests/{requestId}/tenant-contact")
    public ResponseEntity<OperationsTenantContactView> requestContact(Authentication authentication,
            @PathVariable String requestId, HttpServletResponse response) {
        markNoStore(response);
        Long userId = StaffAuthentication.userId(authentication);
        long id = parseLong(requestId, "requestId");
        return ResponseEntity.ok(tenantContact.requestContact(userId, id));
    }

    @GetMapping("/visit-sessions/{sessionId}/tenant-contact")
    public ResponseEntity<OperationsSessionTenantContactView> sessionContact(Authentication authentication,
            @PathVariable String sessionId, HttpServletResponse response) {
        markNoStore(response);
        Long userId = StaffAuthentication.userId(authentication);
        long id = parseLong(sessionId, "sessionId");
        return ResponseEntity.ok(tenantContact.sessionContact(userId, id));
    }

    /** Marks the response non-cacheable before any work runs, including handled error paths. */
    private static void markNoStore(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    private static long parseLong(String value, String field) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
    }
}
