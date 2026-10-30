package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.StaffAccessGrant;
import com.indore.pathome.spaces.entity.StaffCapability;
import com.indore.pathome.spaces.entity.StaffScopeType;
import com.indore.pathome.spaces.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LeadContactAnomalyServiceTest {
    private final StaffAccessService access = mock(StaffAccessService.class);
    private final DatabaseClock clock = mock(DatabaseClock.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final LeadContactAnomalyService service =
            new LeadContactAnomalyService(access, clock, jdbc);

    @Test
    void scopedNonGlobalManagerCannotReadForeignAnomalyMetadata() {
        when(access.requireAttributableUser(17L)).thenReturn(mock(User.class));
        when(access.effectiveGrants(17L)).thenReturn(List.of());

        assertThrows(AccessDeniedException.class, () -> service.page(17L, 0, 20));
        verifyNoInteractions(clock, jdbc);
    }

    @Test
    void pageAndSizeAreStrictlyBoundedBeforeQuery() {
        assertThrows(IllegalArgumentException.class, () -> service.page(17L, -1, 20));
        assertThrows(IllegalArgumentException.class, () -> service.page(17L, 0, 51));
        verifyNoInteractions(access, clock, jdbc);
    }
}
