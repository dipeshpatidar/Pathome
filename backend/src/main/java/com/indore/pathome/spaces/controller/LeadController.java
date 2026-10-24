package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.crm.LeadActivityPage;
import com.indore.pathome.spaces.dto.crm.LeadActivityCommand;
import com.indore.pathome.spaces.dto.crm.LeadActivityView;
import com.indore.pathome.spaces.dto.crm.LeadAssignmentCommand;
import com.indore.pathome.spaces.dto.crm.LeadCaptureCommand;
import com.indore.pathome.spaces.dto.crm.LeadCommandReceiptView;
import com.indore.pathome.spaces.dto.crm.LeadContactView;
import com.indore.pathome.spaces.dto.crm.LeadContactPreferenceCommand;
import com.indore.pathome.spaces.dto.crm.LeadContactPreferenceView;
import com.indore.pathome.spaces.dto.crm.LeadDestinationRevealView;
import com.indore.pathome.spaces.dto.crm.LeadDetailView;
import com.indore.pathome.spaces.dto.crm.LeadDuplicateCandidateResponse;
import com.indore.pathome.spaces.dto.crm.LeadDuplicateCandidatesCommand;
import com.indore.pathome.spaces.dto.crm.LeadLifecycleCommand;
import com.indore.pathome.spaces.dto.crm.LeadPage;
import com.indore.pathome.spaces.dto.crm.LeadPatchCommand;
import com.indore.pathome.spaces.dto.crm.LeadRoutingCommand;
import com.indore.pathome.spaces.dto.crm.LeadShareEventCommand;
import com.indore.pathome.spaces.dto.crm.LeadShareEventView;
import com.indore.pathome.spaces.dto.crm.LeadShareHistoryPage;
import com.indore.pathome.spaces.dto.crm.LeadSharePrepareCommand;
import com.indore.pathome.spaces.dto.crm.LeadShareView;
import com.indore.pathome.spaces.dto.crm.LeadShortlistCommand;
import com.indore.pathome.spaces.dto.crm.LeadShortlistView;
import com.indore.pathome.spaces.dto.crm.LeadSourceAttachCommand;
import com.indore.pathome.spaces.dto.crm.LeadSourceExceptionPage;
import com.indore.pathome.spaces.dto.crm.LeadSourceExceptionView;
import com.indore.pathome.spaces.dto.crm.LeadTeamTransferCommand;
import com.indore.pathome.spaces.entity.LeadStatus;
import com.indore.pathome.spaces.service.LeadContactPreferenceService;
import com.indore.pathome.spaces.service.LeadFollowUpFilter;
import com.indore.pathome.spaces.service.LeadService;
import com.indore.pathome.spaces.service.LeadShareService;
import com.indore.pathome.spaces.service.LeadShortlistService;
import com.indore.pathome.spaces.service.LeadSourceProcessingService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Approved D-B backend surface. The screen composition remains a v0 design dependency; this
 * controller deliberately exposes no speculative visual or provider behavior.
 */
@RestController
@RequestMapping("/api/v1/operations")
public class LeadController {
    private static final CacheControl NO_STORE = CacheControl.noStore();

    private final LeadService leads;
    private final LeadContactPreferenceService contactPreferences;
    private final LeadShortlistService shortlists;
    private final LeadShareService shares;
    private final LeadSourceProcessingService sources;

    public LeadController(LeadService leads,
                          LeadContactPreferenceService contactPreferences,
                          LeadShortlistService shortlists,
                          LeadShareService shares,
                          LeadSourceProcessingService sources) {
        this.leads = leads;
        this.contactPreferences = contactPreferences;
        this.shortlists = shortlists;
        this.shares = shares;
        this.sources = sources;
    }

    @GetMapping("/leads")
    public ResponseEntity<LeadPage> list(
            Authentication authentication,
            @RequestParam(required = false) String cityId,
            @RequestParam(required = false) String teamId,
            @RequestParam(required = false) String status,
            @RequestParam(name = "owner", required = false) String owner,
            @RequestParam(required = false) String followUpBefore,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String followUp,
            @RequestParam(required = false) String followUpZone,
            @RequestParam(defaultValue = "0") String page,
            @RequestParam(defaultValue = "20") String size) {
        boolean ownerMe = "me".equalsIgnoreCase(trim(owner));
        LeadPage result = leads.list(StaffAuthentication.userId(authentication),
                optionalLong(cityId, "cityId"), optionalLong(teamId, "teamId"),
                optionalStatus(status), ownerMe, optionalInstant(followUpBefore), q,
                optionalFollowUp(followUp), trim(followUpZone),
                integer(page, "page"), integer(size, "size"));
        return ResponseEntity.ok().cacheControl(NO_STORE).body(result);
    }

    @PostMapping("/leads")
    public ResponseEntity<LeadDetailView> capture(
            Authentication authentication,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody LeadCaptureCommand command) {
        LeadDetailView result = leads.capture(StaffAuthentication.userId(authentication),
                idempotencyKey, command);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(NO_STORE).body(result);
    }

    @PostMapping("/leads/duplicate-candidates")
    public ResponseEntity<LeadDuplicateCandidateResponse> duplicateCandidates(
            Authentication authentication,
            @RequestBody LeadDuplicateCandidatesCommand command) {
        LeadDuplicateCandidateResponse result = leads.duplicateCandidates(
                StaffAuthentication.userId(authentication), command);
        return ResponseEntity.ok().cacheControl(NO_STORE).body(result);
    }

    @PostMapping("/leads/{leadId}/source-events")
    public ResponseEntity<LeadDetailView> attachSource(
            Authentication authentication,
            @PathVariable String leadId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody LeadSourceAttachCommand command) {
        LeadDetailView result = leads.attachSource(StaffAuthentication.userId(authentication),
                positiveLong(leadId, "leadId"), idempotencyKey, command);
        return ResponseEntity.ok().cacheControl(NO_STORE).body(result);
    }

    @PostMapping("/leads/{leadId}/activities")
    public ResponseEntity<LeadActivityView> logActivity(
            Authentication authentication,
            @PathVariable String leadId,
            @RequestBody LeadActivityCommand command) {
        LeadActivityView result = leads.logActivity(StaffAuthentication.userId(authentication),
                positiveLong(leadId, "leadId"), command);
        return ResponseEntity.ok().cacheControl(NO_STORE).body(result);
    }

    @GetMapping("/leads/source-exceptions")
    public ResponseEntity<LeadSourceExceptionPage> sourceExceptions(
            Authentication authentication,
            @RequestParam(defaultValue = "0") String page,
            @RequestParam(defaultValue = "20") String size) {
        LeadSourceExceptionPage result = sources.exceptions(
                StaffAuthentication.userId(authentication), integer(page, "page"), integer(size, "size"));
        return ResponseEntity.ok().cacheControl(NO_STORE).body(result);
    }

    @PostMapping("/leads/source-exceptions/{eventId}/retry")
    public ResponseEntity<LeadSourceExceptionView> retrySource(
            Authentication authentication,
            @PathVariable String eventId) {
        LeadSourceExceptionView result = sources.retry(
                StaffAuthentication.userId(authentication), uuid(eventId));
        return ResponseEntity.ok().cacheControl(NO_STORE).body(result);
    }

    @GetMapping("/leads/{leadId}")
    public ResponseEntity<LeadDetailView> detail(Authentication authentication, @PathVariable String leadId) {
        LeadDetailView result = leads.detail(StaffAuthentication.userId(authentication), positiveLong(leadId, "leadId"));
        return ResponseEntity.ok().cacheControl(NO_STORE).body(result);
    }

    @PatchMapping("/leads/{leadId}")
    public ResponseEntity<LeadDetailView> patch(Authentication authentication, @PathVariable String leadId,
                                                @RequestBody LeadPatchCommand command) {
        LeadDetailView result = leads.patch(StaffAuthentication.userId(authentication),
                positiveLong(leadId, "leadId"), command);
        return ResponseEntity.ok().cacheControl(NO_STORE).body(result);
    }

    @PostMapping("/leads/{leadId}/lifecycle")
    public ResponseEntity<LeadDetailView> lifecycle(Authentication authentication, @PathVariable String leadId,
                                                    @RequestBody LeadLifecycleCommand command) {
        LeadDetailView result = leads.transition(StaffAuthentication.userId(authentication),
                positiveLong(leadId, "leadId"), command);
        return ResponseEntity.ok().cacheControl(NO_STORE).body(result);
    }

    @PostMapping("/leads/{leadId}/routing")
    public ResponseEntity<LeadDetailView> route(Authentication authentication, @PathVariable String leadId,
                                                @RequestBody LeadRoutingCommand command) {
        LeadDetailView result = leads.route(StaffAuthentication.userId(authentication),
                positiveLong(leadId, "leadId"), command);
        return ResponseEntity.ok().cacheControl(NO_STORE).body(result);
    }

    @PostMapping("/leads/{leadId}/assignment")
    public ResponseEntity<LeadDetailView> assign(Authentication authentication, @PathVariable String leadId,
                                                 @RequestBody LeadAssignmentCommand command) {
        LeadDetailView result = leads.assign(StaffAuthentication.userId(authentication),
                positiveLong(leadId, "leadId"), command);
        return ResponseEntity.ok().cacheControl(NO_STORE).body(result);
    }

    @PostMapping("/leads/{leadId}/team-transfer")
    public ResponseEntity<LeadDetailView> transferTeam(Authentication authentication, @PathVariable String leadId,
                                                       @RequestBody LeadTeamTransferCommand command) {
        LeadDetailView result = leads.transferTeam(StaffAuthentication.userId(authentication),
                positiveLong(leadId, "leadId"), command);
        return ResponseEntity.ok().cacheControl(NO_STORE).body(result);
    }

    @GetMapping("/leads/{leadId}/contact")
    public ResponseEntity<LeadContactView> contact(Authentication authentication, @PathVariable String leadId) {
        LeadContactView result = leads.revealContact(StaffAuthentication.userId(authentication),
                positiveLong(leadId, "leadId"));
        return ResponseEntity.ok().cacheControl(NO_STORE).body(result);
    }

    @PostMapping("/leads/{leadId}/contact-preferences")
    public ResponseEntity<LeadContactPreferenceView> contactPreference(
            Authentication authentication,
            @PathVariable String leadId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody LeadContactPreferenceCommand command) {
        LeadContactPreferenceView result = contactPreferences.record(
                StaffAuthentication.userId(authentication), positiveLong(leadId, "leadId"),
                idempotencyKey, command);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(NO_STORE).body(result);
    }

    @GetMapping("/leads/{leadId}/contact-preferences")
    public ResponseEntity<List<LeadContactPreferenceView>> contactPreferenceHistory(
            Authentication authentication,
            @PathVariable String leadId) {
        List<LeadContactPreferenceView> result = contactPreferences.history(
                StaffAuthentication.userId(authentication), positiveLong(leadId, "leadId"));
        return ResponseEntity.ok().cacheControl(NO_STORE).body(result);
    }

    @GetMapping("/leads/{leadId}/contact-preferences/{revision}/destination")
    public ResponseEntity<LeadDestinationRevealView> revealDestination(
            Authentication authentication,
            @PathVariable String leadId,
            @PathVariable String revision) {
        LeadDestinationRevealView result = contactPreferences.reveal(
                StaffAuthentication.userId(authentication), positiveLong(leadId, "leadId"),
                positiveLong(revision, "revision"));
        return ResponseEntity.ok().cacheControl(NO_STORE).body(result);
    }

    @PostMapping("/leads/{leadId}/shortlists")
    public ResponseEntity<LeadShortlistView> createShortlist(
            Authentication authentication,
            @PathVariable String leadId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody LeadShortlistCommand command) {
        LeadShortlistView result = shortlists.create(StaffAuthentication.userId(authentication),
                positiveLong(leadId, "leadId"), idempotencyKey, command);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(NO_STORE).body(result);
    }

    @PutMapping("/leads/{leadId}/shortlists/{shortlistId}")
    public ResponseEntity<LeadShortlistView> updateShortlist(
            Authentication authentication,
            @PathVariable String leadId,
            @PathVariable String shortlistId,
            @RequestBody LeadShortlistCommand command) {
        LeadShortlistView result = shortlists.update(StaffAuthentication.userId(authentication),
                positiveLong(leadId, "leadId"), positiveLong(shortlistId, "shortlistId"), command);
        return ResponseEntity.ok().cacheControl(NO_STORE).body(result);
    }

    @GetMapping("/leads/{leadId}/shortlists/{shortlistId}")
    public ResponseEntity<LeadShortlistView> getShortlist(
            Authentication authentication,
            @PathVariable String leadId,
            @PathVariable String shortlistId) {
        LeadShortlistView result = shortlists.get(StaffAuthentication.userId(authentication),
                positiveLong(leadId, "leadId"), positiveLong(shortlistId, "shortlistId"));
        return ResponseEntity.ok().cacheControl(NO_STORE).body(result);
    }

    @PostMapping("/leads/{leadId}/shares")
    public ResponseEntity<LeadShareView> prepareShare(
            Authentication authentication,
            @PathVariable String leadId,
            @RequestBody LeadSharePrepareCommand command) {
        LeadShareView result = shares.prepare(StaffAuthentication.userId(authentication),
                positiveLong(leadId, "leadId"), command);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(NO_STORE).body(result);
    }

    @PostMapping("/leads/{leadId}/shares/{shareId}/events")
    public ResponseEntity<LeadShareEventView> recordShareEvent(
            Authentication authentication,
            @PathVariable String leadId,
            @PathVariable String shareId,
            @RequestBody LeadShareEventCommand command) {
        LeadShareEventView result = shares.recordEvent(StaffAuthentication.userId(authentication),
                positiveLong(leadId, "leadId"), uuid(shareId), command);
        return ResponseEntity.ok().cacheControl(NO_STORE).body(result);
    }

    @GetMapping("/leads/{leadId}/shares")
    public ResponseEntity<LeadShareHistoryPage> shareHistory(
            Authentication authentication,
            @PathVariable String leadId,
            @RequestParam(defaultValue = "0") String page,
            @RequestParam(defaultValue = "10") String size) {
        LeadShareHistoryPage result = shares.history(
                StaffAuthentication.userId(authentication), positiveLong(leadId, "leadId"),
                integer(page, "page"), integer(size, "size"));
        return ResponseEntity.ok().cacheControl(NO_STORE).body(result);
    }

    @GetMapping("/leads/{leadId}/activities")
    public ResponseEntity<LeadActivityPage> activities(
            Authentication authentication,
            @PathVariable String leadId,
            @RequestParam(defaultValue = "0") String page,
            @RequestParam(defaultValue = "20") String size) {
        LeadActivityPage result = leads.activity(StaffAuthentication.userId(authentication),
                positiveLong(leadId, "leadId"), integer(page, "page"), integer(size, "size"));
        return ResponseEntity.ok().cacheControl(NO_STORE).body(result);
    }

    @GetMapping("/lead-commands/{operationId}")
    public ResponseEntity<LeadCommandReceiptView> receipt(Authentication authentication,
                                                          @PathVariable String operationId,
                                                          @RequestParam(required = false) String action) {
        LeadCommandReceiptView result = leads.receipt(StaffAuthentication.userId(authentication),
                uuid(operationId), trim(action));
        return ResponseEntity.ok().cacheControl(NO_STORE).body(result);
    }

    private static int integer(String value, String field) {
        try {
            return Integer.parseInt(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
    }

    private static Long positiveLong(String value, String field) {
        Long result = optionalLong(value, field);
        if (result == null || result <= 0) throw new IllegalArgumentException(field + " must be positive");
        return result;
    }

    private static Long optionalLong(String value, String field) {
        String clean = trim(value);
        if (clean == null) return null;
        try {
            long parsed = Long.parseLong(clean);
            if (parsed <= 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(field + " must be a positive integer");
        }
    }

    private static LeadStatus optionalStatus(String value) {
        String clean = trim(value);
        if (clean == null) return null;
        try {
            return LeadStatus.valueOf(clean.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("status is invalid");
        }
    }

    private static Instant optionalInstant(String value) {
        String clean = trim(value);
        if (clean == null) return null;
        try {
            return Instant.parse(clean);
        } catch (Exception exception) {
            throw new IllegalArgumentException("followUpBefore must be an ISO-8601 instant");
        }
    }

    private static LeadFollowUpFilter optionalFollowUp(String value) {
        String clean = trim(value);
        if (clean == null) return null;
        try {
            return LeadFollowUpFilter.valueOf(clean.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "followUp must be OVERDUE, TODAY or NONE");
        }
    }

    private static UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("operationId must be a UUID");
        }
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
