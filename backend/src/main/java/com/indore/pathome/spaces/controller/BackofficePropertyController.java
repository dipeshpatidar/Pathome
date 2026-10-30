package com.indore.pathome.spaces.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.indore.pathome.spaces.dto.backoffice.BackofficeDtos;
import com.indore.pathome.spaces.service.BackofficePropertyService;
import com.indore.pathome.spaces.service.StrictJsonRequestDecoder;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/backoffice/properties")
public class BackofficePropertyController {
    private final BackofficePropertyService properties;
    private final StrictJsonRequestDecoder strict;

    public BackofficePropertyController(BackofficePropertyService properties,
                                        StrictJsonRequestDecoder strict) {
        this.properties = properties;
        this.strict = strict;
    }

    @GetMapping
    public ResponseEntity<BackofficeDtos.Page> list(Authentication auth,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "") String q) {
        return noStore(properties.list(StaffAuthentication.userId(auth), q, page));
    }

    @GetMapping("/{listingId}")
    public ResponseEntity<BackofficeDtos.Detail> detail(Authentication auth, @PathVariable Long listingId) {
        return noStore(properties.detail(StaffAuthentication.userId(auth), listingId));
    }

    @PostMapping("/{listingId}/revisions")
    public ResponseEntity<BackofficeDtos.Revision> startRevision(Authentication auth,
                                                                  @PathVariable Long listingId) {
        return noStore(properties.startRevision(StaffAuthentication.userId(auth), listingId));
    }

    @PostMapping("/{listingId}/revisions/corrective")
    public ResponseEntity<BackofficeDtos.Revision> startCorrection(Authentication auth,
                                                                    @PathVariable Long listingId,
                                                                    @RequestParam Long publicationVersion) {
        return noStore(properties.startCorrection(
                StaffAuthentication.userId(auth), listingId, publicationVersion));
    }

    @GetMapping("/{listingId}/revisions/{draftId}")
    public ResponseEntity<BackofficeDtos.Revision> revision(Authentication auth, @PathVariable Long listingId,
                                                             @PathVariable String draftId) {
        return noStore(properties.getRevision(StaffAuthentication.userId(auth), listingId, draftId));
    }

    @PutMapping("/{listingId}/revisions/{draftId}/basics")
    public ResponseEntity<BackofficeDtos.Revision> saveBasics(
            Authentication auth, @PathVariable Long listingId, @PathVariable String draftId,
            @RequestHeader("If-Match") int version, @RequestBody JsonNode body) {
        return noStore(properties.saveBasics(StaffAuthentication.userId(auth), listingId, draftId,
                version, strict.decode(body, BackofficeDtos.Basics.class)));
    }

    @PutMapping("/{listingId}/revisions/{draftId}/commercial")
    public ResponseEntity<BackofficeDtos.Revision> saveCommercial(
            Authentication auth, @PathVariable Long listingId, @PathVariable String draftId,
            @RequestHeader("If-Match") int version, @RequestBody JsonNode body) {
        return noStore(properties.saveCommercial(StaffAuthentication.userId(auth), listingId, draftId,
                version, strict.decode(body, BackofficeDtos.Commercial.class)));
    }

    @PutMapping("/{listingId}/revisions/{draftId}/details")
    public ResponseEntity<BackofficeDtos.Revision> saveDetails(
            Authentication auth, @PathVariable Long listingId, @PathVariable String draftId,
            @RequestHeader("If-Match") int version, @RequestBody JsonNode body) {
        return noStore(properties.saveDetails(StaffAuthentication.userId(auth), listingId, draftId,
                version, strict.decode(body, BackofficeDtos.Details.class)));
    }

    @PutMapping("/{listingId}/revisions/{draftId}/address")
    public ResponseEntity<BackofficeDtos.Revision> saveAddress(
            Authentication auth, @PathVariable Long listingId, @PathVariable String draftId,
            @RequestHeader("If-Match") int version, @RequestBody JsonNode body) {
        return noStore(properties.saveAddress(StaffAuthentication.userId(auth), listingId, draftId,
                version, strict.decode(body, BackofficeDtos.PresentationAddress.class)));
    }

    @PutMapping("/{listingId}/revisions/{draftId}/media")
    public ResponseEntity<BackofficeDtos.Revision> saveMedia(
            Authentication auth, @PathVariable Long listingId, @PathVariable String draftId,
            @RequestHeader("If-Match") int version,
            @RequestBody JsonNode body) {
        return noStore(properties.saveMedia(StaffAuthentication.userId(auth), listingId, draftId,
                version, strict.decodeList(body, BackofficeDtos.MediaPresentation.class)));
    }

    @PostMapping("/{listingId}/revisions/{draftId}/discard")
    public ResponseEntity<BackofficeDtos.Committed> discard(
            Authentication auth, @PathVariable Long listingId, @PathVariable String draftId,
            @RequestBody JsonNode body) {
        var command = strict.decode(body, BackofficeDtos.SupersedeCommand.class);
        return noStore(properties.discard(StaffAuthentication.userId(auth), listingId, draftId,
                command.reason()));
    }

    @PostMapping("/{listingId}/revisions/{draftId}/supersede")
    public ResponseEntity<BackofficeDtos.Revision> supersede(
            Authentication auth, @PathVariable Long listingId, @PathVariable String draftId,
            @RequestHeader("If-Match") int version,
            @RequestBody JsonNode body) {
        var command = strict.decode(body, BackofficeDtos.SupersedeCommand.class);
        return noStore(properties.supersede(StaffAuthentication.userId(auth), listingId, draftId,
                version, command.reason()));
    }

    @PostMapping("/{listingId}/revisions/{draftId}/publish")
    public ResponseEntity<BackofficeDtos.PublishResult> publish(
            Authentication auth, @PathVariable Long listingId, @PathVariable String draftId,
            @RequestHeader("If-Match") int draftVersion,
            @RequestHeader("X-Listing-Version") Long listingVersion,
            @RequestHeader("Idempotency-Key") UUID operationId,
            @RequestBody JsonNode body) {
        var command = strict.decode(body, BackofficeDtos.PublishCommand.class);
        return noStore(properties.publish(StaffAuthentication.userId(auth), listingId, draftId,
                operationId, draftVersion, listingVersion, command.reason()));
    }

    @PostMapping("/{listingId}/lifecycle/{action}")
    public ResponseEntity<BackofficeDtos.Committed> lifecycle(
            Authentication auth, @PathVariable Long listingId, @PathVariable String action,
            @RequestHeader("If-Match") Long version,
            @RequestHeader("Idempotency-Key") UUID operationId,
            @RequestBody JsonNode body) {
        var command = strict.decode(body, BackofficeDtos.LifecycleCommand.class);
        return noStore(properties.lifecycle(StaffAuthentication.userId(auth), listingId,
                operationId, version, action, command.reason()));
    }

    @GetMapping("/{listingId}/activity")
    public ResponseEntity<BackofficeDtos.ActivityPage> activity(
            Authentication auth, @PathVariable Long listingId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return noStore(properties.activity(StaffAuthentication.userId(auth), listingId,
                page, cursor, limit));
    }

    @GetMapping("/{listingId}/commands/{operationId}")
    public ResponseEntity<BackofficeDtos.CommandOutcome> commandOutcome(
            Authentication auth, @PathVariable Long listingId, @PathVariable UUID operationId) {
        return noStore(properties.commandOutcome(StaffAuthentication.userId(auth), listingId, operationId));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}

