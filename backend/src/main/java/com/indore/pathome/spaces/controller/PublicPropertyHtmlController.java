package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.service.PublicPropertyHtmlService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** Initial HTML for the canonical public property route. */
@RestController
public class PublicPropertyHtmlController {
    private static final Pattern POSITIVE_LONG = Pattern.compile("[1-9][0-9]{0,18}");

    private final PublicPropertyHtmlService htmlService;

    public PublicPropertyHtmlController(PublicPropertyHtmlService htmlService) {
        this.htmlService = htmlService;
    }

    @GetMapping("/property/{propertyId}")
    public ResponseEntity<String> property(@PathVariable String propertyId) {
        return response(propertyId, true);
    }

    @RequestMapping(value = "/property/{propertyId}", method = RequestMethod.HEAD)
    public ResponseEntity<String> head(@PathVariable String propertyId) {
        return response(propertyId, false);
    }

    private ResponseEntity<String> response(String propertyId, boolean includeBody) {
        Long parsed = parsePositiveLong(propertyId);
        var result = parsed == null
                ? htmlService.renderInvalidIdentifier()
                : htmlService.render(parsed);
        return ResponseEntity.status(result.status())
                .contentType(new MediaType("text", "html", java.nio.charset.StandardCharsets.UTF_8))
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header("Referrer-Policy", "no-referrer")
                .contentLength(result.html().getBytes(StandardCharsets.UTF_8).length)
                .body(includeBody ? result.html() : null);
    }

    private static Long parsePositiveLong(String value) {
        if (value == null || !POSITIVE_LONG.matcher(value).matches()) return null;
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
