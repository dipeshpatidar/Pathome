package com.indore.pathome.spaces.security;

import java.net.URI;
import java.util.regex.Pattern;

/**
 * Single authoritative source for "is this origin a legitimate local-development origin?"
 *
 * <p>Rules (development only — never applied in production):
 * <ul>
 *   <li>Scheme must be {@code http} or {@code https}.</li>
 *   <li>Host {@code localhost} (case-insensitive).</li>
 *   <li>IPv6 loopback: {@code ::1} or {@code [::1]}.</li>
 *   <li>IPv4 loopback: {@code 127.x.x.x} (RFC 5735 127.0.0.0/8).</li>
 *   <li>Private RFC 1918 ranges only:
 *     <ul>
 *       <li>{@code 10.0.0.0/8} — {@code 10.x.x.x}</li>
 *       <li>{@code 172.16.0.0/12} — {@code 172.16.x.x} through {@code 172.31.x.x}</li>
 *       <li>{@code 192.168.0.0/16} — {@code 192.168.x.x}</li>
 *     </ul>
 *   </li>
 *   <li>mDNS {@code .local} hostnames (e.g. {@code dipeshs-macbook-air.local}).</li>
 * </ul>
 *
 * <p>Explicitly rejected despite superficial similarity:
 * <ul>
 *   <li>{@code localhost.attacker.com} — not {@code localhost}, contains extra labels.</li>
 *   <li>{@code evil-localhost.com}, {@code pathome.local.attacker.com} — not {@code *.local}.</li>
 *   <li>{@code 172.15.x.x}, {@code 172.32.x.x} — outside 172.16.0.0/12.</li>
 *   <li>Any public routable IP or internet hostname.</li>
 * </ul>
 */
public final class DevOriginPolicy {

    /** Matches IPv4 loopback (127.0.0.0/8) and RFC 1918 private ranges only. */
    static final Pattern PRIVATE_OR_LOOPBACK_IP = Pattern.compile(
            "^("
            + "127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}"                    // 127.0.0.0/8 loopback
            + "|10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}"                     // 10.0.0.0/8
            + "|192\\.168\\.\\d{1,3}\\.\\d{1,3}"                          // 192.168.0.0/16
            + "|172\\.(1[6-9]|2\\d|3[01])\\.\\d{1,3}\\.\\d{1,3}"         // 172.16.0.0/12
            + "|::1|\\[::1\\]"                                             // IPv6 loopback
            + ")$"
    );

    /** Matches {@code *.local} mDNS hostnames — label must be simple alphanumeric+hyphen+dot chain ending in .local. */
    static final Pattern LOCAL_HOSTNAME = Pattern.compile(
            "^[a-zA-Z0-9]([a-zA-Z0-9.-]*[a-zA-Z0-9])?\\.local$", Pattern.CASE_INSENSITIVE);

    private DevOriginPolicy() {}

    /**
     * Returns {@code true} if {@code origin} is a legitimate local-development origin
     * as defined by the rules above.
     *
     * <p>This method must never be called in production context.
     * Callers are responsible for gating on the active environment profile.
     *
     * @param origin the {@code Origin} header value (e.g. {@code http://dipeshs-macbook-air.local:5173})
     * @return {@code true} if the origin is a safe local-dev origin
     */
    public static boolean isLegitimateDevOrigin(String origin) {
        if (origin == null || origin.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(origin);
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                return false;
            }
            String host = uri.getHost();
            if (host == null) {
                return false;
            }
            if ("localhost".equalsIgnoreCase(host)) {
                return true;
            }
            if (LOCAL_HOSTNAME.matcher(host).matches()) {
                return true;
            }
            if (PRIVATE_OR_LOOPBACK_IP.matcher(host).matches()) {
                return true;
            }
        } catch (IllegalArgumentException ignored) {
            return false;
        }
        return false;
    }

    /**
     * Returns CORS {@code allowedOriginPatterns} entries for Spring's
     * {@code CorsConfiguration.setAllowedOriginPatterns()} that cover the
     * same dev origins recognised by {@link #isLegitimateDevOrigin(String)}.
     *
     * <p>These patterns use Spring's restricted wildcard syntax ({@code [*]} for ports,
     * {@code *} for host segments) and intentionally restrict {@code 172.*} to the
     * correct private range only.
     *
     * @return immutable list of dev CORS origin patterns
     */
    public static java.util.List<String> corsDevPatterns() {
        return java.util.List.of(
                "http://localhost", "https://localhost",
                "http://localhost:[*]", "https://localhost:[*]",
                "http://127.0.0.1", "https://127.0.0.1",
                "http://127.0.0.1:[*]", "https://127.0.0.1:[*]",
                // IPv6 loopback — Spring CorsConfiguration treats [::1] as a literal host
                "http://[::1]", "https://[::1]",
                "http://[::1]:[*]", "https://[::1]:[*]",
                // mDNS .local hostnames
                "http://*.local", "https://*.local",
                "http://*.local:[*]", "https://*.local:[*]",
                // RFC 1918 — 10.0.0.0/8
                "http://10.*.*.*", "https://10.*.*.*",
                "http://10.*.*.*:[*]", "https://10.*.*.*:[*]",
                // RFC 1918 — 192.168.0.0/16
                "http://192.168.*.*", "https://192.168.*.*",
                "http://192.168.*.*:[*]", "https://192.168.*.*:[*]",
                // RFC 1918 — 172.16.0.0/12 (16–31 only — NOT 172.* which covers public space)
                "http://172.16.*.*", "https://172.16.*.*", "http://172.16.*.*:[*]", "https://172.16.*.*:[*]",
                "http://172.17.*.*", "https://172.17.*.*", "http://172.17.*.*:[*]", "https://172.17.*.*:[*]",
                "http://172.18.*.*", "https://172.18.*.*", "http://172.18.*.*:[*]", "https://172.18.*.*:[*]",
                "http://172.19.*.*", "https://172.19.*.*", "http://172.19.*.*:[*]", "https://172.19.*.*:[*]",
                "http://172.20.*.*", "https://172.20.*.*", "http://172.20.*.*:[*]", "https://172.20.*.*:[*]",
                "http://172.21.*.*", "https://172.21.*.*", "http://172.21.*.*:[*]", "https://172.21.*.*:[*]",
                "http://172.22.*.*", "https://172.22.*.*", "http://172.22.*.*:[*]", "https://172.22.*.*:[*]",
                "http://172.23.*.*", "https://172.23.*.*", "http://172.23.*.*:[*]", "https://172.23.*.*:[*]",
                "http://172.24.*.*", "https://172.24.*.*", "http://172.24.*.*:[*]", "https://172.24.*.*:[*]",
                "http://172.25.*.*", "https://172.25.*.*", "http://172.25.*.*:[*]", "https://172.25.*.*:[*]",
                "http://172.26.*.*", "https://172.26.*.*", "http://172.26.*.*:[*]", "https://172.26.*.*:[*]",
                "http://172.27.*.*", "https://172.27.*.*", "http://172.27.*.*:[*]", "https://172.27.*.*:[*]",
                "http://172.28.*.*", "https://172.28.*.*", "http://172.28.*.*:[*]", "https://172.28.*.*:[*]",
                "http://172.29.*.*", "https://172.29.*.*", "http://172.29.*.*:[*]", "https://172.29.*.*:[*]",
                "http://172.30.*.*", "https://172.30.*.*", "http://172.30.*.*:[*]", "https://172.30.*.*:[*]",
                "http://172.31.*.*", "https://172.31.*.*", "http://172.31.*.*:[*]", "https://172.31.*.*:[*]"
        );
    }
}
