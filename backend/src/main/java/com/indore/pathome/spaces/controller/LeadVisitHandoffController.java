package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.VisitHandoffExchangeCommand;
import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.VisitHandoffView;
import com.indore.pathome.spaces.service.LeadVisitProposalService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
public class LeadVisitHandoffController {
    public static final String COOKIE_NAME = "pathome_visit_context";
    private static final Duration COOKIE_LIFETIME = Duration.ofMinutes(30);

    private final LeadVisitProposalService proposals;
    private final boolean secureCookies;

    public LeadVisitHandoffController(
            LeadVisitProposalService proposals,
            @Value("${pathome.crm.secure-cookies:true}") boolean secureCookies) {
        this.proposals = proposals;
        this.secureCookies = secureCookies;
    }

    @PostMapping("/api/v1/lead-visit-handoffs/exchange")
    public ResponseEntity<VisitHandoffView> exchange(
            @RequestBody VisitHandoffExchangeCommand command) {
        LeadVisitProposalService.HandoffExchange exchange = proposals.exchange(command);
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, exchange.continuationToken())
                .httpOnly(true)
                .secure(secureCookies)
                .sameSite("Lax")
                .path("/api/v1")
                .maxAge(COOKIE_LIFETIME)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .header("Referrer-Policy", "no-referrer")
                .cacheControl(CacheControl.noStore())
                .body(exchange.view());
    }

    @GetMapping("/api/v1/lead-visit-handoffs/current")
    public ResponseEntity<VisitHandoffView> current(
            @CookieValue(name = COOKIE_NAME, required = false) String continuationToken) {
        if (continuationToken == null || continuationToken.isBlank()) {
            return ResponseEntity.notFound().cacheControl(CacheControl.noStore()).build();
        }
        return ResponseEntity.ok()
                .header("Referrer-Policy", "no-referrer")
                .cacheControl(CacheControl.noStore())
                .body(proposals.current(continuationToken));
    }

    @PostMapping("/api/v1/lead-visit-handoffs/clear")
    public ResponseEntity<Void> clear() {
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, "")
                .httpOnly(true)
                .secure(secureCookies)
                .sameSite("Lax")
                .path("/api/v1")
                .maxAge(Duration.ZERO)
                .build();
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .cacheControl(CacheControl.noStore())
                .build();
    }
}
