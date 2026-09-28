package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.service.LandlordLocationService;
import com.indore.pathome.spaces.security.LocationSuggestionThrottle;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/lessor/locations")
@PreAuthorize("isAuthenticated()")
public class LandlordLocationController {
    private final LandlordLocationService locations;
    private final LocationSuggestionThrottle throttle;

    public LandlordLocationController(LandlordLocationService locations, LocationSuggestionThrottle throttle) {
        this.locations = locations;
        this.throttle = throttle;
    }

    @GetMapping("/cities")
    public List<String> cities() { return locations.cities(); }

    @GetMapping("/suggestions")
    public List<LandlordLocationService.Option> suggest(@RequestParam String city, @RequestParam String q,
                                                        Authentication authentication) {
        throttle.check("auth:" + authentication.getName());
        return locations.suggest(city, q);
    }
}
