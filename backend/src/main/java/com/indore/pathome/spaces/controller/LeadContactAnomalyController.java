package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.crm.LeadContactAnomalyView;
import com.indore.pathome.spaces.service.LeadContactAnomalyService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class LeadContactAnomalyController {
    private final LeadContactAnomalyService anomalies;

    public LeadContactAnomalyController(LeadContactAnomalyService anomalies) {
        this.anomalies = anomalies;
    }

    @GetMapping("/api/v1/operations/lead-contact-anomalies")
    public ResponseEntity<List<LeadContactAnomalyView>> page(
            Authentication authentication,
            @RequestParam(defaultValue = "0") String page,
            @RequestParam(defaultValue = "20") String size) {
        List<LeadContactAnomalyView> result = anomalies.page(
                StaffAuthentication.userId(authentication),
                integer(page, "page"), integer(size, "size"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }

    private static int integer(String value, String field) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
    }
}
