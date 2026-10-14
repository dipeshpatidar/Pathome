package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.InitialVisitRequestRoutingCommand;
import com.indore.pathome.spaces.dto.IntakeRoutingCandidatesView;
import com.indore.pathome.spaces.dto.IntakeRoutingRequestPage;
import com.indore.pathome.spaces.service.OperationalIntakeRoutingReadService;
import com.indore.pathome.spaces.service.OperationalOwnershipService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Package 3B human-authorized initial routing routes. Authentication is enforced by the existing
 * {@code /api/v1/operations/**} security matcher; the effective routing authority is enforced
 * server-side on every call from live database facts.
 */
@RestController
@RequestMapping("/api/v1/operations/intake-routing")
public class OperationalIntakeRoutingController {
    private static final CacheControl NO_STORE = CacheControl.noStore();

    private final OperationalIntakeRoutingReadService reads;
    private final OperationalOwnershipService ownership;

    public OperationalIntakeRoutingController(OperationalIntakeRoutingReadService reads,
                                              OperationalOwnershipService ownership) {
        this.reads = reads;
        this.ownership = ownership;
    }

    @GetMapping("/visit-requests")
    public ResponseEntity<IntakeRoutingRequestPage> listCandidates(Authentication authentication,
            @RequestParam(defaultValue = "0") String page, @RequestParam(defaultValue = "20") String size) {
        IntakeRoutingRequestPage result = reads.listEligibleRequests(StaffAuthentication.userId(authentication),
                parseInteger(page, "page"), parseInteger(size, "size"));
        return ResponseEntity.ok().cacheControl(NO_STORE).body(result);
    }

    @GetMapping("/visit-requests/{requestId}/candidates")
    public ResponseEntity<IntakeRoutingCandidatesView> readCandidates(Authentication authentication,
            @PathVariable String requestId, @RequestParam(name = "cityId", required = false) String cityId,
            @RequestParam(defaultValue = "0") String page, @RequestParam(defaultValue = "20") String size) {
        // The City is an explicit, mandatory part of the candidate read: a missing value is a client
        // error (400), never a server error, and it can never be defaulted to some implicit City.
        if (cityId == null || cityId.isBlank()) {
            throw new IllegalArgumentException("cityId is required");
        }
        IntakeRoutingCandidatesView result = reads.readCandidates(StaffAuthentication.userId(authentication),
                parseLong(requestId, "requestId"), parseLong(cityId, "cityId"),
                parseInteger(page, "page"), parseInteger(size, "size"));
        return ResponseEntity.ok().cacheControl(NO_STORE).body(result);
    }

    @PostMapping("/visit-requests/{requestId}/route")
    public ResponseEntity<Void> route(Authentication authentication, @PathVariable String requestId,
            @RequestBody InitialVisitRequestRoutingCommand command) {
        if (command == null) throw new IllegalArgumentException("Request body is required");
        ownership.routeInitialRequest(StaffAuthentication.userId(authentication),
                parseLong(requestId, "requestId"), command.expectedRequestVersion(), command.cityId(),
                command.expectedCityVersion(), command.teamId(), command.expectedTeamVersion(),
                command.reasonCode());
        return ResponseEntity.noContent().cacheControl(NO_STORE).build();
    }

    private static int parseInteger(String value, String field) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
    }

    private static Long parseLong(String value, String field) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
    }
}
