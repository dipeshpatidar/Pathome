package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.crm.LeadActivityPage;
import com.indore.pathome.spaces.dto.crm.LeadAssignmentCommand;
import com.indore.pathome.spaces.dto.crm.LeadCaptureCommand;
import com.indore.pathome.spaces.dto.crm.LeadCommandReceiptView;
import com.indore.pathome.spaces.dto.crm.LeadContactView;
import com.indore.pathome.spaces.dto.crm.LeadDetailView;
import com.indore.pathome.spaces.dto.crm.LeadLifecycleCommand;
import com.indore.pathome.spaces.dto.crm.LeadPage;
import com.indore.pathome.spaces.dto.crm.LeadPatchCommand;
import com.indore.pathome.spaces.dto.crm.LeadRoutingCommand;
import com.indore.pathome.spaces.dto.crm.LeadTeamTransferCommand;
import com.indore.pathome.spaces.entity.LeadStatus;
import com.indore.pathome.spaces.service.LeadService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
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

    public LeadController(LeadService leads) {
        this.leads = leads;
    }

    @GetMapping("/leads")
    public ResponseEntity<LeadPage> list(
            Authentication authentication,
            @RequestParam(required = false) String cityId,
            @RequestParam(required = false) String teamId,
            @RequestParam(required = false) String status,
            @RequestParam(name = "owner", required = false) String owner,
            @RequestParam(required = false) String followUpBefore,
            @RequestParam(defaultValue = "0") String page,
            @RequestParam(defaultValue = "20") String size) {
        boolean ownerMe = "me".equalsIgnoreCase(trim(owner));
        LeadPage result = leads.list(StaffAuthentication.userId(authentication),
                optionalLong(cityId, "cityId"), optionalLong(teamId, "teamId"),
                optionalStatus(status), ownerMe, optionalInstant(followUpBefore),
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
                                                          @PathVariable String operationId) {
        LeadCommandReceiptView result = leads.receipt(StaffAuthentication.userId(authentication),
                uuid(operationId));
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
