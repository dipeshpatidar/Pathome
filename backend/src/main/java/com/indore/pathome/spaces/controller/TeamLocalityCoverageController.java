package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.CoverageOptionsView;
import com.indore.pathome.spaces.dto.CoverageOptionView;
import com.indore.pathome.spaces.dto.TeamLocalityCoverageCommand;
import com.indore.pathome.spaces.dto.TeamLocalityCoveragePage;
import com.indore.pathome.spaces.dto.TeamLocalityCoverageStateCommand;
import com.indore.pathome.spaces.dto.TeamLocalityCoverageView;
import com.indore.pathome.spaces.service.TeamLocalityCoverageService;
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

import java.util.List;

@RestController
@RequestMapping("/api/v1/operations/automatic-routing/coverage")
public class TeamLocalityCoverageController {
    private static final CacheControl NO_STORE = CacheControl.noStore();
    private final TeamLocalityCoverageService coverage;

    public TeamLocalityCoverageController(TeamLocalityCoverageService coverage) {
        this.coverage = coverage;
    }

    @GetMapping
    public ResponseEntity<TeamLocalityCoveragePage> list(Authentication authentication,
            @RequestParam(required = false) String cityId,
            @RequestParam(defaultValue = "0") String page,
            @RequestParam(defaultValue = "20") String size) {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(coverage.list(
                StaffAuthentication.userId(authentication),
                cityId == null ? null : parseLong(cityId, "cityId"),
                parseInteger(page, "page"), parseInteger(size, "size")));
    }

    @GetMapping("/cities")
    public ResponseEntity<List<CoverageOptionView>> cities(Authentication authentication) {
        return ResponseEntity.ok().cacheControl(NO_STORE)
                .body(coverage.cities(StaffAuthentication.userId(authentication)));
    }

    @GetMapping("/options")
    public ResponseEntity<CoverageOptionsView> options(Authentication authentication,
            @RequestParam String cityId) {
        return ResponseEntity.ok().cacheControl(NO_STORE)
                .body(coverage.options(StaffAuthentication.userId(authentication),
                        parseLong(cityId, "cityId")));
    }

    @GetMapping("/locality")
    public ResponseEntity<List<TeamLocalityCoverageView>> activeForLocality(
            Authentication authentication, @RequestParam String cityId,
            @RequestParam String localityId) {
        return ResponseEntity.ok().cacheControl(NO_STORE)
                .body(coverage.activeForLocality(StaffAuthentication.userId(authentication),
                        parseLong(cityId, "cityId"), parseLong(localityId, "localityId")));
    }

    @PostMapping
    public ResponseEntity<TeamLocalityCoverageView> create(Authentication authentication,
            @RequestBody TeamLocalityCoverageCommand command) {
        return ResponseEntity.ok().cacheControl(NO_STORE)
                .body(coverage.create(StaffAuthentication.userId(authentication), command));
    }

    @PostMapping("/{coverageId}/state")
    public ResponseEntity<TeamLocalityCoverageView> changeState(Authentication authentication,
            @PathVariable String coverageId,
            @RequestBody TeamLocalityCoverageStateCommand command) {
        return ResponseEntity.ok().cacheControl(NO_STORE)
                .body(coverage.changeState(StaffAuthentication.userId(authentication),
                        parseLong(coverageId, "coverageId"), command));
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
