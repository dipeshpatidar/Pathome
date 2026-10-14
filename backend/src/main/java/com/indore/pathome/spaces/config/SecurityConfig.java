package com.indore.pathome.spaces.config;

import com.indore.pathome.spaces.security.JwtAuthenticationFilter;
import com.indore.pathome.spaces.security.OAuth2AuthenticationSuccessHandler;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final OAuth2AuthenticationSuccessHandler oAuth2SuccessHandler;
    private final boolean googleLoginEnabled;
    private final org.springframework.core.env.Environment env;
    private final String configuredOrigins;

    /**
     * Authorises exactly one thing: the servlet container's own {@link DispatcherType#ERROR} dispatch
     * to the Boot error page. A {@code DispatcherType} is assigned by the container and cannot be set
     * by a client - no request header, query parameter or body can forge it - so this exception cannot
     * be reached by an ordinary external request.
     *
     * <p>The dispatch type is the whole decision, and the decision is only ever evaluated for requests
     * that already matched {@code /error} (see the filter chain below), so an ordinary
     * {@code GET /error} is denied like every other unlisted path and answers 401 or 403 through the
     * entry point. That matters because {@code /error} is a real Boot endpoint: permitting it outright
     * would expose the error-attribute rendering path to anonymous callers.
     */
    private static final AuthorizationManager<RequestAuthorizationContext> SERVER_ERROR_DISPATCH =
            (authentication, context) ->
                    new AuthorizationDecision(context.getRequest().getDispatcherType() == DispatcherType.ERROR);

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          OAuth2AuthenticationSuccessHandler oAuth2SuccessHandler,
                          @Value("${pathome.google-login.enabled:false}") boolean googleLoginEnabled,
                          org.springframework.core.env.Environment env,
                          @Value("${pathome.guest.allowed-origins:}") String configuredOrigins) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.oAuth2SuccessHandler = oAuth2SuccessHandler;
        this.googleLoginEnabled = googleLoginEnabled;
        this.env = env;
        this.configuredOrigins = configuredOrigins;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.POST, "/api/v1/lessor/guest/drafts/*/claim").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/v1/tenant/visit-requests").hasRole("TENANT")
                .requestMatchers(HttpMethod.GET, "/api/v1/tenant/visit-entitlement").hasRole("TENANT")
                .requestMatchers("/api/v1/tenant/visit-sessions/**").hasRole("TENANT")
                .requestMatchers("/api/v1/ground/visit-sessions/**").hasRole("GROUND_BOY")
                .requestMatchers("/api/v1/operations/**", "/api/v1/ground/visit-sessions/**").authenticated()
                .requestMatchers("/api/v1/staff/**").authenticated()
                .requestMatchers("/api/v1/favorites/**").hasRole("TENANT")
                .requestMatchers(HttpMethod.GET, "/api/v1/notifications", "/api/v1/notifications/**").authenticated()
                .requestMatchers(HttpMethod.PUT, "/api/v1/notifications", "/api/v1/notifications/**").authenticated()
                .requestMatchers(HttpMethod.POST, "/api/v1/notifications/mark-all-read").authenticated()
                .requestMatchers(HttpMethod.POST, "/api/v1/notifications").hasRole("ADMIN")
                .requestMatchers(
                    "/api/v1/auth/**",
                    "/api/v1/lessor/guest/**",
                    "/api/v1/webhooks/**",
                    "/login/oauth2/**",
                    "/oauth2/**",
                    "/actuator/health",
                    "/v3/api-docs/**",
                    "/swagger-ui/**"
                ).permitAll()
                // The container's ERROR dispatch to "/error" must stay reachable, and nothing else may
                // be. When a filter-level rule denies an authenticated caller (for example
                // "/api/v1/ground/visit-sessions/**" requires ROLE_GROUND_BOY), Spring Security's
                // AccessDeniedHandler responds with sendError(403), which the container turns into an
                // ERROR dispatch to "/error". That dispatch runs through this same chain with an empty
                // stateless context, so a protected "/error" would be denied as anonymous and the entry
                // point would overwrite the correct 403 with a 401 - the caller is told their
                // credentials are missing when in fact their permissions are insufficient, and the web
                // client signs them out.
                //
                // SERVER_ERROR_DISPATCH permits that dispatch and only that dispatch: /error stays
                // protected for ordinary requests, so the endpoint is not exposed to anonymous callers.
                // This grants no authority beyond the container's own decision path - "/error" renders
                // only the error attributes (status, path, timestamp) of a request that has already
                // been decided - and missing, invalid or expired authentication still answers 401 from
                // the entry point on every protected route.
                .requestMatchers("/error").access(SERVER_ERROR_DISPATCH)
                .requestMatchers(HttpMethod.GET, "/api/v1/properties/*/tagged-media").hasAnyRole("ADMIN", "SUB_ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/v1/properties/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/properties/*/visit-requests").hasRole("TENANT")
                .requestMatchers(HttpMethod.POST, "/api/v1/properties/search-feedback").permitAll()
                .requestMatchers("/api/v1/properties/**").hasAnyRole("ADMIN", "SUB_ADMIN")
                .requestMatchers("/api/v1/admin/failed-uploads/**").hasAnyRole("ADMIN", "SUB_ADMIN")
                .requestMatchers("/api/v1/admin/drafts/**").hasAnyRole("ADMIN", "SUB_ADMIN")
                // Approved listing-review authority extension (narrow by construction): these seven
                // listing-review operations additionally admit a caller holding a live effective
                // STAFF_ADMIN@GLOBAL grant, which ListingReviewAuthority re-decides from database facts
                // inside the service. They are listed as exact paths - and are matched before the
                // "/api/v1/admin/**" rule below - so every other administrative path, including this
                // controller's request-changes and revision operations, keeps the existing ROLE_ADMIN
                // requirement. No general "/admin/**" access is granted to staff.
                .requestMatchers(HttpMethod.GET,
                    "/api/v1/admin/lessor-review/listings",
                    "/api/v1/admin/lessor-review/listings/*",
                    "/api/v1/admin/lessor-review/revisions",
                    "/api/v1/admin/lessor-review/revisions/*"
                ).authenticated()
                .requestMatchers(HttpMethod.POST,
                    "/api/v1/admin/lessor-review/listings/*/start-review",
                    "/api/v1/admin/lessor-review/listings/*/link-locality",
                    "/api/v1/admin/lessor-review/listings/*/approve"
                ).authenticated()
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .exceptionHandling(exception -> exception
                .authenticationEntryPoint(new org.springframework.security.web.authentication.HttpStatusEntryPoint(org.springframework.http.HttpStatus.UNAUTHORIZED))
            )
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        if (googleLoginEnabled) {
            http.oauth2Login(oauth2 -> oauth2.successHandler(oAuth2SuccessHandler));
        }

        return http.build();
    }

    /**
     * CORS configuration source that delegates origin decisions to
     * {@link com.indore.pathome.spaces.security.DevOriginPolicy#isLegitimateDevOrigin(String)}
     * in development, and to the explicit {@code pathome.guest.allowed-origins} list in production.
     *
     * <p>The incoming {@code Origin} header is parsed by {@code DevOriginPolicy} which uses
     * {@link java.net.URI#getHost()} semantics — private IPv4 ranges are matched by a
     * numeric-only regex, so hostnames like {@code 10.foo.attacker.com} are never accepted.
     * {@link com.indore.pathome.spaces.security.GuestRequestGuard} uses the same method,
     * ensuring one shared decision with no drift between CORS and the application guard.
     */
    @Bean
    public org.springframework.web.cors.CorsConfigurationSource corsConfigurationSource() {
        boolean production = MediaStagingConfig.isProductionEnvironment(env);
        java.util.Set<String> explicitOrigins = java.util.Arrays.stream(configuredOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        // Base config shared across all allowed origins.
        org.springframework.web.cors.CorsConfiguration baseConfig = new org.springframework.web.cors.CorsConfiguration();
        baseConfig.setAllowedMethods(java.util.List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        baseConfig.setAllowedHeaders(java.util.List.of("*"));
        baseConfig.setAllowCredentials(true);

        return request -> {
            String origin = request.getHeader("Origin");
            if (origin == null || origin.isBlank()) {
                return null; // Not a CORS request.
            }
            boolean allowed = explicitOrigins.contains(origin)
                    || (!production && com.indore.pathome.spaces.security.DevOriginPolicy.isLegitimateDevOrigin(origin));
            if (!allowed) {
                return null; // Disallowed origin — Spring CORS filter will respond 403.
            }
            // Echo back the exact request Origin (never "*") so credentials can be used
            // and no additional origins are implicitly permitted.
            org.springframework.web.cors.CorsConfiguration config =
                    new org.springframework.web.cors.CorsConfiguration(baseConfig);
            config.setAllowedOrigins(java.util.List.of(origin));
            return config;
        };
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authenticationConfiguration) throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }
}
