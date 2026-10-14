package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.CreateOperatingTeamCommand;
import com.indore.pathome.spaces.dto.OperatingTeamProvisioningCityPage;
import com.indore.pathome.spaces.dto.OperatingTeamProvisioningPage;
import com.indore.pathome.spaces.dto.OperatingTeamProvisioningView;
import com.indore.pathome.spaces.service.OperatingTeamProvisioningService;
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
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Package 3A governed Team provisioning routes. Authentication is enforced by the existing
 * {@code /api/v1/staff/**} security matcher; the effective {@code STAFF_ADMIN@GLOBAL} capability is
 * enforced server-side in {@link OperatingTeamProvisioningService} on every call.
 */
@RestController
@RequestMapping("/api/v1/staff/admin")
public class OperatingTeamProvisioningController {
    private static final CacheControl NO_STORE = CacheControl.noStore();

    private final OperatingTeamProvisioningService provisioning;

    public OperatingTeamProvisioningController(OperatingTeamProvisioningService provisioning) {
        this.provisioning = provisioning;
    }

    @GetMapping("/operating-teams/cities")
    public ResponseEntity<OperatingTeamProvisioningCityPage> listActiveCities(Authentication authentication,
            @RequestParam(defaultValue = "0") String page, @RequestParam(defaultValue = "20") String size) {
        OperatingTeamProvisioningCityPage result = provisioning.listActiveCities(
                StaffAuthentication.userId(authentication), parseInteger(page, "page"), parseInteger(size, "size"));
        return ResponseEntity.ok().cacheControl(NO_STORE).body(result);
    }

    @GetMapping("/cities/{cityId}/operating-teams")
    public ResponseEntity<OperatingTeamProvisioningPage> listCityTeams(Authentication authentication,
            @PathVariable String cityId,
            @RequestParam(defaultValue = "0") String page, @RequestParam(defaultValue = "20") String size) {
        OperatingTeamProvisioningPage result = provisioning.listCityTeams(
                StaffAuthentication.userId(authentication), parseLong(cityId, "cityId"),
                parseInteger(page, "page"), parseInteger(size, "size"));
        return ResponseEntity.ok().cacheControl(NO_STORE).body(result);
    }

    @PostMapping("/cities/{cityId}/operating-teams")
    public ResponseEntity<OperatingTeamProvisioningView> createTeam(Authentication authentication,
            @PathVariable String cityId, @RequestBody CreateOperatingTeamCommand command) {
        OperatingTeamProvisioningView created = provisioning.createTeam(
                StaffAuthentication.userId(authentication), parseLong(cityId, "cityId"), command);
        return ResponseEntity.created(ServletUriComponentsBuilder.fromCurrentRequest().build().toUri())
                .cacheControl(NO_STORE).body(created);
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
