package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.TenantVisitEntitlementView;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.entity.VisitPolicy;
import com.indore.pathome.spaces.repository.UserRepository;
import com.indore.pathome.spaces.repository.VisitPolicyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TenantVisitEntitlementServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final UserRepository users = mock(UserRepository.class);
    private final VisitPolicyRepository policies = mock(VisitPolicyRepository.class);
    private TenantVisitEntitlementService service;

    @BeforeEach
    void setUp() {
        service = new TenantVisitEntitlementService(jdbc, users, policies);
    }

    @Test
    void newGrantUsesCurrentPolicyAndPersistsTenantSpecificTotal() {
        for (int grant : new int[]{5, 7}) {
            VisitPolicy policy = new VisitPolicy();
            policy.setFreeVisitSessionsDefault(grant);
            when(policies.findById(VisitPolicy.SINGLETON_ID)).thenReturn(Optional.of(policy));
            when(users.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
                User user = invocation.getArgument(0);
                assertEquals(grant, user.getFreeVisitsRemaining());
                user.setId((long) grant);
                return user;
            });
            when(jdbc.queryForObject(contains("returning id"), eq(Long.class), eq((long) grant), eq(grant), eq(grant)))
                    .thenReturn(100L + grant);
            User created = service.createTenantWithGrant(new User());
            assertEquals(grant, created.getFreeVisitsRemaining());
            verify(jdbc).update(contains("'INITIAL_GRANT'"), eq(100L + grant), eq((long) grant), eq(grant), eq("INITIAL_GRANT:" + grant));
            clearInvocations(jdbc, users, policies);
        }
    }

    @Test
    void zeroPolicyGrantCreatesNoZeroDeltaLedgerRow() {
        VisitPolicy policy = new VisitPolicy();
        policy.setFreeVisitSessionsDefault(0);
        when(policies.findById(VisitPolicy.SINGLETON_ID)).thenReturn(Optional.of(policy));
        when(users.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(20L);
            return user;
        });
        when(jdbc.queryForObject(contains("returning id"), eq(Long.class), eq(20L), eq(0), eq(0))).thenReturn(120L);
        assertEquals(0, service.createTenantWithGrant(new User()).getFreeVisitsRemaining());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void readKeepsReservationsSeparateAndDoesNotUseChangedGlobalDefault() throws Exception {
        assertEquals(new TenantVisitEntitlementView(5, 5, 0, 5, false), read(41L, 5, 5, 0, false));
        assertEquals(new TenantVisitEntitlementView(5, 5, 1, 4, false), read(41L, 5, 4, 1, false));
        assertEquals(new TenantVisitEntitlementView(5, 4, 0, 4, false), read(41L, 5, 4, 0, false));
        assertEquals(new TenantVisitEntitlementView(5, 3, 0, 3, false), read(41L, 5, 3, 0, false));
        assertEquals(new TenantVisitEntitlementView(5, 0, 0, 0, false), read(41L, 5, 0, 0, false));
        assertEquals(new TenantVisitEntitlementView(7, 7, 0, 7, false), read(42L, 7, 7, 0, false));
        verifyNoInteractions(policies);
    }

    @Test
    void legacyOrInconsistentTotalNeverReturnsAFalseDenominator() throws Exception {
        assertEquals(new TenantVisitEntitlementView(null, 4, 1, 3, true), read(41L, null, 3, 1, false));
        assertEquals(new TenantVisitEntitlementView(null, 6, 0, 6, true), read(41L, 5, 6, 0, false));
        assertEquals(new TenantVisitEntitlementView(null, 5, 0, 5, true), read(41L, 5, 5, 0, true));
    }

    private TenantVisitEntitlementView read(Long userId, Integer total, int available, int reserved,
                                             boolean reconciliationRequired) throws Exception {
        ResultSet result = mock(ResultSet.class);
        when(result.getObject("total_granted_credits")).thenReturn(total);
        when(result.getInt("available_credits")).thenReturn(available);
        when(result.getInt("reserved_credits")).thenReturn(reserved);
        when(result.getBoolean("reconciliation_required")).thenReturn(reconciliationRequired);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(userId))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            RowMapper<TenantVisitEntitlementView> mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(result, 0));
        });
        return service.getMine(userId);
    }
}
