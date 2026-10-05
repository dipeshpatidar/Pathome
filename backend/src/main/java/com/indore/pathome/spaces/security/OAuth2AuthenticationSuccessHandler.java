package com.indore.pathome.spaces.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.entity.Role;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.repository.UserRepository;
import com.indore.pathome.spaces.service.TenantVisitEntitlementService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Component
public class OAuth2AuthenticationSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final JwtUtils jwtUtils;
    private final UserRepository userRepository;
    private final TenantVisitEntitlementService entitlements;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OAuth2AuthenticationSuccessHandler(JwtUtils jwtUtils, UserRepository userRepository,
                                              TenantVisitEntitlementService entitlements) {
        this.jwtUtils = jwtUtils;
        this.userRepository = userRepository;
        this.entitlements = entitlements;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {
        OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();
        String email = oAuth2User.getAttribute("email");
        String name = oAuth2User.getAttribute("name");
        String sub = oAuth2User.getAttribute("sub");

        Optional<User> existingUserOpt = userRepository.findByEmail(email);
        User user;
        if (existingUserOpt.isPresent()) {
            user = existingUserOpt.get();
            if (user.getGoogleSub() == null) {
                user.setGoogleSub(sub);
                userRepository.save(user);
            }
        } else {
            user = new User();
            user.setEmail(email);
            user.setFullName(name);
            user.setGoogleSub(sub);
            user.setRole(Role.ROLE_TENANT);
            user = entitlements.createTenantWithGrant(user);
        }

        String jwtToken = jwtUtils.generateToken(user.getId(), user.getEmail(), user.getRole().name());

        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        Map<String, Object> responseData = new HashMap<>();
        responseData.put("token", jwtToken);
        responseData.put("userId", user.getId());
        responseData.put("email", user.getEmail());
        responseData.put("fullName", user.getFullName());
        responseData.put("role", user.getRole().name());
        responseData.put("freeVisitsRemaining", user.getFreeVisitsRemaining());

        response.getWriter().write(objectMapper.writeValueAsString(responseData));
    }
}
