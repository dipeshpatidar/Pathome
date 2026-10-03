package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.config.VisitExecutionProperties;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.entity.VisitSession;
import com.indore.pathome.spaces.entity.VisitSessionStatus;
import com.indore.pathome.spaces.repository.EmployeeProfileRepository;
import com.indore.pathome.spaces.repository.GroundExecutiveSchedulingProfileRepository;
import com.indore.pathome.spaces.repository.UserRepository;
import com.indore.pathome.spaces.repository.VisitSessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class VisitExecutionServiceTest {
    @Test
    void materialShiftUsesSecondsRatherThanTruncatedWholeMinutes() {
        assertFalse(VisitExecutionService.isMaterialShift(Duration.ofMinutes(10).minusMillis(1), 10));
        assertFalse(VisitExecutionService.isMaterialShift(Duration.ofMinutes(10), 10));
        assertTrue(VisitExecutionService.isMaterialShift(Duration.ofMinutes(10).plusSeconds(1), 10));
    }

    @Test
    void challengeExpiresAtTheExactServerExpiryInstant() {
        Instant expiry = Instant.parse("2026-10-03T12:00:00Z");
        assertTrue(VisitExecutionService.challengeStillAvailable(expiry, expiry.minusMillis(1)));
        assertFalse(VisitExecutionService.challengeStillAvailable(expiry, expiry));
        assertFalse(VisitExecutionService.challengeStillAvailable(expiry, expiry.plusMillis(1)));
    }

    @Test
    void tenantContactUsesSessionLockSoReassignmentSerializesWithDisclosure() {
        VisitSessionRepository sessions = mock(VisitSessionRepository.class);
        VisitOperationsAuthorizationService authorization = mock(VisitOperationsAuthorizationService.class);
        VisitSession session = new VisitSession();
        User tenant = new User();
        tenant.setId(8L);
        tenant.setFullName("Tenant");
        tenant.setPhoneNumber("9990001111");
        User groundExecutive = new User();
        groundExecutive.setId(9L);
        session.setTenant(tenant);
        session.setRepresentative(groundExecutive);
        session.setStatus(VisitSessionStatus.SCHEDULED);
        session.setScheduledAt(Instant.now().plusSeconds(60));
        when(sessions.findLockedById(77L)).thenReturn(Optional.of(session));

        VisitExecutionService service = new VisitExecutionService(sessions, mock(UserRepository.class),
                mock(EmployeeProfileRepository.class), mock(GroundExecutiveSchedulingProfileRepository.class),
                authorization, mock(VisitEntitlementStore.class),
                new VisitExecutionProperties(), mock(VisitOtpDeliveryProvider.class), mock(VisitOtpCrypto.class),
                mock(JdbcTemplate.class), mock(VisitSchedulingRecommendationService.class),
                mock(org.springframework.transaction.PlatformTransactionManager.class));

        var contact = service.getTenantContact(9L, 77L);

        assertEquals("9990001111", contact.tenantPhone());
        verify(sessions).findLockedById(77L);
        verify(sessions, never()).findById(77L);
    }
}
