package com.indore.pathome.spaces.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.indore.pathome.spaces.dto.removal.PropertyRemovalDtos;
import com.indore.pathome.spaces.service.PropertyRemovalService;
import com.indore.pathome.spaces.service.StrictJsonRequestDecoder;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/lessor/properties/{listingId}/removal-requests")
public class PropertyRemovalOwnerController {
    private final PropertyRemovalService removals;
    private final StrictJsonRequestDecoder strict;

    public PropertyRemovalOwnerController(PropertyRemovalService removals,
                                          StrictJsonRequestDecoder strict) {
        this.removals = removals;
        this.strict = strict;
    }

    @PostMapping
    public ResponseEntity<PropertyRemovalDtos.CreateResult> create(
            Authentication auth, @PathVariable Long listingId,
            @RequestHeader("If-Match") Long listingVersion,
            @RequestBody JsonNode body) {
        var result = removals.create(StaffAuthentication.userId(auth), listingId, listingVersion,
                strict.decode(body, PropertyRemovalDtos.CreateRequest.class));
        return ResponseEntity.status(result.created() ? 201 : 200)
                .cacheControl(CacheControl.noStore()).body(result);
    }

    @GetMapping
    public ResponseEntity<PropertyRemovalDtos.OwnerHistory> history(
            Authentication auth, @PathVariable Long listingId,
            @RequestParam(defaultValue = "0") int page) {
        return noStore(removals.ownerHistory(StaffAuthentication.userId(auth), listingId, page));
    }

    @GetMapping("/{requestId}")
    public ResponseEntity<PropertyRemovalDtos.RequestView> request(
            Authentication auth, @PathVariable Long listingId, @PathVariable Long requestId) {
        return noStore(removals.ownerRequest(StaffAuthentication.userId(auth), listingId, requestId));
    }

    @PostMapping("/{requestId}/withdraw")
    public ResponseEntity<PropertyRemovalDtos.RequestView> withdraw(
            Authentication auth, @PathVariable Long listingId, @PathVariable Long requestId,
            @RequestHeader("If-Match") Long requestVersion,
            @RequestBody JsonNode body) {
        return noStore(removals.withdraw(StaffAuthentication.userId(auth), listingId, requestId,
                requestVersion, strict.decode(body, PropertyRemovalDtos.WithdrawRequest.class)));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}

