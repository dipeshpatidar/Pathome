package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.security.GuestRequestGuard;
import com.indore.pathome.spaces.security.LocationSuggestionThrottle;
import com.indore.pathome.spaces.service.LandlordLocationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Read-only canonical location choices for the existing lessor editor. */
@RestController
@RequestMapping("/api/v1/lessor/guest/locations")
public class GuestLocationController {
    private final LandlordLocationService locations;
    private final GuestRequestGuard guard;
    private final LocationSuggestionThrottle throttle;

    public GuestLocationController(LandlordLocationService locations, GuestRequestGuard guard,
                                   LocationSuggestionThrottle throttle) {
        this.locations = locations; this.guard = guard; this.throttle = throttle;
    }

    @GetMapping("/cities")
    public List<String> cities(HttpServletRequest request) {
        guard.check(request);
        return locations.cities();
    }

    @GetMapping("/suggestions")
    public List<LandlordLocationService.Option> suggest(HttpServletRequest request,
            @RequestParam String city, @RequestParam String q) {
        guard.check(request);
        throttle.check("guest:" + request.getRemoteAddr());
        return locations.suggest(city, q);
    }
}
