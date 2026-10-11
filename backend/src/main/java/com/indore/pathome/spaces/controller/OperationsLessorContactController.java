package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.OperationsLessorContactView;
import com.indore.pathome.spaces.service.OperationsLessorContactService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 2A A1a secure lessor-contact read routes.
 *
 * <p>Backend-only seam for the currently owning Operations Executive. This controller does not
 * implement call-attempt recording, property confirmation, scheduling or any mutation.</p>
 */
@RestController
@RequestMapping("/api/v1/operations")
public class OperationsLessorContactController {

    private final OperationsLessorContactService lessorContact;

    public OperationsLessorContactController(OperationsLessorContactService lessorContact) {
        this.lessorContact = lessorContact;
    }

    @GetMapping("/visit-requests/{requestId}/lessor-contact")
    public ResponseEntity<OperationsLessorContactView> requestContact(Authentication authentication,
            @PathVariable String requestId, HttpServletResponse response) {
        markNoStore(response);
        Long userId = StaffAuthentication.userId(authentication);
        long id = parseLong(requestId, "requestId");
        return ResponseEntity.ok(lessorContact.requestContact(userId, id));
    }

    @GetMapping("/visit-sessions/{sessionId}/items/{itemId}/lessor-contact")
    public ResponseEntity<OperationsLessorContactView> sessionItemContact(Authentication authentication,
            @PathVariable String sessionId, @PathVariable String itemId, HttpServletResponse response) {
        markNoStore(response);
        Long userId = StaffAuthentication.userId(authentication);
        long session = parseLong(sessionId, "sessionId");
        long item = parseLong(itemId, "itemId");
        return ResponseEntity.ok(lessorContact.sessionItemContact(userId, session, item));
    }

    /**
     * Marks the response non-cacheable before any work runs. Because the header is written directly to
     * the response, it survives the shared exception handling path as well, so 400/401/403/404/5xx
     * responses for these routes are never cacheable either. These routes can emit real contact PII.
     */
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
