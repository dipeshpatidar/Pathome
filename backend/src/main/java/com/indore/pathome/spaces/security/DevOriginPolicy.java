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
 *   <li>Private RFC 1918 ranges only (parsed numerically via regex, not wildcard strings):
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
 *   <li>{@code 10.foo.attacker.com}, {@code 172.16.foo.attacker.com} — not numeric IPs.</li>
 *   <li>Any public routable IP or internet hostname.</li>
 * </ul>
 *
 * <p><strong>CORS integration note</strong>: This policy is used directly as the CORS
 * {@link org.springframework.web.cors.CorsConfigurationSource} decision function
 * (see {@link com.indore.pathome.spaces.config.SecurityConfig}). There are no separate
 * CORS wildcard pattern strings for private IPs — both CORS and {@link GuestRequestGuard}
 * call {@link #isLegitimateDevOrigin(String)} on the parsed {@code Origin} header so the
 * decision is made exactly once and is always consistent.
 */
public final class DevOriginPolicy {

    /**
     * Matches only numeric IPv4 addresses in the loopback (127.0.0.0/8) and RFC 1918
     * private ranges. Uses {@code \d{1,3}} so only numeric octets match — hostname labels
     * like {@code foo}, {@code attacker}, etc. are never matched.
     */
    static final Pattern PRIVATE_OR_LOOPBACK_IP = Pattern.compile(
            "^("
            + "127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}"                    // 127.0.0.0/8 loopback
            + "|10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}"                     // 10.0.0.0/8
            + "|192\\.168\\.\\d{1,3}\\.\\d{1,3}"                          // 192.168.0.0/16
            + "|172\\.(1[6-9]|2\\d|3[01])\\.\\d{1,3}\\.\\d{1,3}"         // 172.16.0.0/12
            + "|::1|\\[::1\\]"                                             // IPv6 loopback
            + ")$"
    );

    /** Matches {@code *.local} mDNS hostnames — the label chain must end with exactly {@code .local}. */
    static final Pattern LOCAL_HOSTNAME = Pattern.compile(
            "^[a-zA-Z0-9]([a-zA-Z0-9.-]*[a-zA-Z0-9])?\\.local$", Pattern.CASE_INSENSITIVE);

    private DevOriginPolicy() {}

    /**
     * Returns {@code true} if {@code origin} is a legitimate local-development origin
     * as defined by the rules above.
     *
     * <p>Host matching uses parsed URI semantics ({@link URI#getHost()}) — it never does
     * substring or wildcard string matching on the raw origin value. Private IPv4 ranges
     * are matched by the {@link #PRIVATE_OR_LOOPBACK_IP} regex which only matches
     * {@code \d{1,3}}-separated numeric octets, so hostname labels are always rejected.
     *
     * <p>This method must never be called in a production context.
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
}
