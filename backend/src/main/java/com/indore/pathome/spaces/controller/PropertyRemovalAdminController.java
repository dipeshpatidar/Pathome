package com.indore.pathome.spaces.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.indore.pathome.spaces.dto.removal.PropertyRemovalDtos;
import com.indore.pathome.spaces.entity.PropertyRemovalRequestStatus;
import com.indore.pathome.spaces.service.PropertyRemovalService;
import com.indore.pathome.spaces.service.StrictJsonRequestDecoder;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/property-removal-requests")
@PreAuthorize("hasRole('ADMIN')")
public class PropertyRemovalAdminController {
    private final PropertyRemovalService removals;
    private final StrictJsonRequestDecoder strict;

    public PropertyRemovalAdminController(PropertyRemovalService removals,
                                          StrictJsonRequestDecoder strict) {
        this.removals = removals;
        this.strict = strict;
    }

    @GetMapping
    public ResponseEntity<PropertyRemovalDtos.AdminQueue> queue(
            Authentication auth,
            @RequestParam(required = false) PropertyRemovalRequestStatus status,
            @RequestParam(required = false) Long cityId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return noStore(removals.adminQueue(
                StaffAuthentication.userId(auth), status, cityId, page, size));
    }

    @GetMapping("/{requestId}")
    public ResponseEntity<PropertyRemovalDtos.AdminDetail> detail(
            Authentication auth, @PathVariable Long requestId) {
        return noStore(removals.adminDetail(StaffAuthentication.userId(auth), requestId));
    }

    @PostMapping("/{requestId}/decision")
    public ResponseEntity<PropertyRemovalDtos.RequestView> decide(
            Authentication auth, @PathVariable Long requestId,
            @RequestHeader("If-Match") Long requestVersion,
            @RequestBody JsonNode body) {
        return noStore(removals.decide(StaffAuthentication.userId(auth), requestId,
                requestVersion, strict.decode(body, PropertyRemovalDtos.DecisionRequest.class)));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}

