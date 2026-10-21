package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.ClaimableWorkItem;
import com.indore.pathome.spaces.dto.ClaimableWorkPage;
import com.indore.pathome.spaces.dto.ClaimableWorkTargetType;
import com.indore.pathome.spaces.dto.OperationsWorkFilter;
import com.indore.pathome.spaces.service.ClaimableWorkService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/operations/claimable-work")
public class ClaimableWorkController {
    private static final CacheControl NO_STORE = CacheControl.noStore();

    private final ClaimableWorkService claimableWork;

    public ClaimableWorkController(ClaimableWorkService claimableWork) {
        this.claimableWork = claimableWork;
    }

    @GetMapping
    public ResponseEntity<ClaimableWorkPage> list(Authentication authentication,
            @RequestParam(defaultValue = "0") String page,
            @RequestParam(defaultValue = "20") String size,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cityId,
            @RequestParam(required = false) String teamId,
            @RequestParam(required = false) String targetId) {
        OperationsWorkFilter filter = OperationsWorkFilter.parse(targetType, status, cityId, teamId, targetId);
        ClaimableWorkPage result = claimableWork.list(StaffAuthentication.userId(authentication),
                parseInteger(page, "page"), parseInteger(size, "size"), filter);
        return ResponseEntity.ok().cacheControl(NO_STORE).body(result);
    }

    @GetMapping("/{targetType}/{targetId}")
    public ResponseEntity<ClaimableWorkItem> refresh(Authentication authentication,
            @PathVariable String targetType,
            @PathVariable String targetId) {
        ClaimableWorkItem result = claimableWork.refresh(StaffAuthentication.userId(authentication),
                parseTargetType(targetType), parseLong(targetId, "targetId"));
        return ResponseEntity.ok().cacheControl(NO_STORE).body(result);
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

    private static ClaimableWorkTargetType parseTargetType(String value) {
        try {
            return ClaimableWorkTargetType.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Target type must be REQUEST or SESSION");
        }
    }
}
