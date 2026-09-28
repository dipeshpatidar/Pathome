package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.controller.AuthController;
import com.indore.pathome.spaces.repository.LessorProfileRepository;
import com.indore.pathome.spaces.repository.UserRepository;
import com.indore.pathome.spaces.security.JwtUtils;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class LessorCapabilityBeanWiringTest {
    @Test
    void springConstructsAuthControllerAndLandlordCapabilityService() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(UserRepository.class, () -> mock(UserRepository.class));
            context.registerBean(LessorProfileRepository.class, () -> mock(LessorProfileRepository.class));
            context.registerBean(PasswordEncoder.class, () -> mock(PasswordEncoder.class));
            context.registerBean(JwtUtils.class, () -> mock(JwtUtils.class));
            context.register(AuthController.class, LandlordCapabilityService.class);
            context.refresh();

            assertNotNull(context.getBean(AuthController.class));
            assertNotNull(context.getBean(LandlordCapabilityService.class));
        }
    }
}
