package com.indore.pathome.spaces.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Injects escaped property facts into the shell produced by the same frontend build. */
@Service
public class PublicPropertyHtmlService {
    private static final Logger log = LoggerFactory.getLogger(PublicPropertyHtmlService.class);
    private static final String SHELL_LOCATION = "classpath:static/property-shell/index.html";
    private static final int MAXIMUM_SHELL_BYTES = 2 * 1024 * 1024;
    private static final int MAXIMUM_SHELL_READ_BYTES =
            maximumShellReadBytes(MAXIMUM_SHELL_BYTES);
    private static final int MAXIMUM_DESCRIPTION_LENGTH = 240;
    private static final Pattern SHELL_TITLE_SLOT = Pattern.compile(
            "<title\\s+data-pathome-shell-title(?:\\s*=\\s*[\"'][^\"']*[\"'])?\\s*>.*?</title>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final PublicPropertyReadService publicProperties;
    private final String configuredOrigin;
    private final ResourceLoader resourceLoader;

    public PublicPropertyHtmlService(
            PublicPropertyReadService publicProperties,
            @Value("${pathome.crm.public-origin:}") String configuredOrigin,
            ResourceLoader resourceLoader) {
        this.publicProperties = publicProperties;
        this.configuredOrigin = configuredOrigin;
        this.resourceLoader = resourceLoader;
    }

    public HtmlResult render(long propertyId) {
        long started = System.nanoTime();
        String correlationId = UUID.randomUUID().toString();
        try {
            var snapshot = publicProperties.findPublicSnapshot(propertyId).orElse(null);
            if (snapshot == null) {
                return complete(404, neutralUnavailable(
                        "Property unavailable",
                        "This property is no longer available."), started, propertyId, correlationId);
            }
            String origin = PublicPropertyOrigin.canonical(configuredOrigin);
            String shell = loadShell();
            String html = injectMetadata(shell, snapshot, origin);
            return complete(200, html, started, propertyId, correlationId);
        } catch (Exception exception) {
            log.warn("public_property_html outcome=unavailable status=503 propertyId={} durationMs={} correlationId={}",
                    propertyId, elapsedMilliseconds(started), correlationId);
            return complete(503, neutralUnavailable(
                    "Property preview unavailable",
                    "This property preview is temporarily unavailable. Please try again."),
                    started, propertyId, correlationId);
        }
    }

    public HtmlResult renderInvalidIdentifier() {
        return new HtmlResult(404, neutralUnavailable(
                "Property unavailable", "This property link is not valid."));
    }

    private String loadShell() throws IOException {
        Resource shell = resourceLoader.getResource(SHELL_LOCATION);
        if (!shell.exists()) throw new IOException("Packaged public property shell is unavailable");
        try (InputStream input = shell.getInputStream()) {
            byte[] bytes = input.readNBytes(MAXIMUM_SHELL_READ_BYTES);
            if (bytes.length == 0 || bytes.length > MAXIMUM_SHELL_BYTES) {
                throw new IOException("Packaged public property shell has an invalid size");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private String injectMetadata(String shell,
                                  PublicPropertyReadService.PublicPropertySnapshot property,
                                  String origin) {
        String canonicalUrl = origin + "/property/" + property.id();
        String description = description(property);
        StringBuilder metadata = new StringBuilder();
        metadata.append("<title>")
                .append(escape(property.title() + " | Pathome"))
                .append("</title>")
                .append("<meta name=\"description\" content=\"").append(escape(description)).append("\" ")
                .append(metadataAttribute()).append(">")
                .append("<meta name=\"robots\" content=\"index,follow\" ")
                .append(metadataAttribute()).append(">")
                .append("<meta property=\"og:title\" content=\"").append(escape(property.title()))
                .append("\" ").append(metadataAttribute()).append(">")
                .append("<meta property=\"og:description\" content=\"").append(escape(description))
                .append("\" ").append(metadataAttribute()).append(">")
                .append("<meta property=\"og:url\" content=\"").append(escape(canonicalUrl))
                .append("\" ").append(metadataAttribute()).append(">");
        if (property.coverImageUrl() != null) {
            metadata.append("<meta property=\"og:image\" content=\"")
                    .append(escape(property.coverImageUrl())).append("\" ")
                    .append(metadataAttribute()).append(">");
        }
        metadata.append("<meta property=\"og:type\" content=\"website\" ")
                .append(metadataAttribute()).append(">")
                .append("<meta property=\"og:site_name\" content=\"Pathome\" ")
                .append(metadataAttribute()).append(">")
                .append("<link rel=\"canonical\" href=\"").append(escape(canonicalUrl))
                .append("\" ").append(metadataAttribute()).append(">");

        var matcher = SHELL_TITLE_SLOT.matcher(shell);
        if (!matcher.find()) throw new IllegalStateException("Property metadata shell slot is missing");
        if (matcher.find()) {
            throw new IllegalStateException("Property metadata shell slot must occur exactly once");
        }
        return matcher.replaceFirst(Matcher.quoteReplacement(metadata.toString()));
    }

    private static int maximumShellReadBytes(int maximumShellBytes) {
        if (maximumShellBytes < 0) {
            throw new IllegalArgumentException("Maximum shell size must not be negative");
        }
        return Math.addExact(maximumShellBytes, 1);
    }

    private static String description(PublicPropertyReadService.PublicPropertySnapshot property) {
        List<String> facts = new ArrayList<>(4);
        facts.add("Property in " + property.sector() + ", " + property.city());
        if (property.monthlyRent() != null) {
            facts.add("Rent " + currency(property.monthlyRent()) + " per month");
        }
        if (property.bhk() != null) facts.add(property.bhk());
        if (property.totalAreaSqFt() != null) facts.add(number(property.totalAreaSqFt()) + " sq ft");
        String description = String.join(". ", facts);
        if (description.codePointCount(0, description.length()) <= MAXIMUM_DESCRIPTION_LENGTH) {
            return description;
        }
        int end = description.offsetByCodePoints(0, MAXIMUM_DESCRIPTION_LENGTH - 1);
        return description.substring(0, end) + "…";
    }

    private static String currency(BigDecimal value) {
        NumberFormat format = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN"));
        format.setMinimumFractionDigits(0);
        format.setMaximumFractionDigits(2);
        return format.format(value);
    }

    private static String number(Number value) {
        NumberFormat format = NumberFormat.getNumberInstance(Locale.forLanguageTag("en-IN"));
        format.setMinimumFractionDigits(0);
        format.setMaximumFractionDigits(2);
        return format.format(value);
    }

    private static String escape(String value) {
        return HtmlUtils.htmlEscape(value == null ? "" : value, StandardCharsets.UTF_8.name());
    }

    private static String metadataAttribute() {
        return "data-pathome-public-metadata=\"true\"";
    }

    private static String neutralUnavailable(String title, String message) {
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<meta name=\"robots\" content=\"noindex,nofollow\">"
                + "<title>" + escape(title) + "</title></head><body><main>"
                + "<h1>" + escape(title) + "</h1><p>" + escape(message) + "</p>"
                + "</main></body></html>";
    }

    private static HtmlResult complete(int status, String html, long started,
                                       long propertyId, String correlationId) {
        if (status == 200) {
            log.debug("public_property_html outcome=ok status={} propertyId={} durationMs={} correlationId={}",
                    status, propertyId, elapsedMilliseconds(started), correlationId);
        } else if (status == 404) {
            log.debug("public_property_html outcome=not_found status={} propertyId={} durationMs={} correlationId={}",
                    status, propertyId, elapsedMilliseconds(started), correlationId);
        }
        return new HtmlResult(status, html);
    }

    private static long elapsedMilliseconds(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    public record HtmlResult(int status, String html) {}
}
