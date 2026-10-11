package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.OperationsMyWorkPage;
import com.indore.pathome.spaces.dto.OperationsStandaloneRequestDetailView;
import com.indore.pathome.spaces.service.OperationsMyWorkService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Phase 2A-1 read-only coordinator workspace routes. No coordinator mutation is exposed here. */
@RestController
@RequestMapping("/api/v1/operations")
public class OperationsMyWorkController {
    private static final CacheControl NO_STORE = CacheControl.noStore();

    private final OperationsMyWorkService myWork;

    public OperationsMyWorkController(OperationsMyWorkService myWork) {
        this.myWork = myWork;
    }

    @GetMapping("/my-work")
    public ResponseEntity<OperationsMyWorkPage> list(Authentication authentication,
            @RequestParam(defaultValue = "0") String page,
            @RequestParam(defaultValue = "20") String size,
            @RequestParam(defaultValue = "false") String attentionOnly) {
        OperationsMyWorkPage result = myWork.list(StaffAuthentication.userId(authentication),
                parseInteger(page, "page"), parseInteger(size, "size"), parseBoolean(attentionOnly));
        return ResponseEntity.ok().cacheControl(NO_STORE).body(result);
    }

    @GetMapping("/visit-requests/{requestId}")
    public ResponseEntity<OperationsStandaloneRequestDetailView> requestDetail(Authentication authentication,
            @PathVariable String requestId) {
        OperationsStandaloneRequestDetailView result = myWork.requestDetail(
                StaffAuthentication.userId(authentication), parseLong(requestId, "requestId"));
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

    private static boolean parseBoolean(String value) {
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        throw new IllegalArgumentException("attentionOnly must be true or false");
    }
}
