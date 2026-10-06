package com.indore.pathome.spaces.config;

import com.indore.pathome.spaces.service.StaffAdminBootstrapService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class DataInitializerTest {
    @Test
    void ordinaryStartupNeverInvokesFirstAdminProvisioning() {
        StaffAdminBootstrapService bootstrap = mock(StaffAdminBootstrapService.class);
        new DataInitializer(bootstrap, "owner@example.test", null, "DEPLOYMENT_CHANGE_1", false).run();
        verifyNoInteractions(bootstrap);
    }

    @Test
    void firstAdminProvisioningRequiresExplicitMode() {
        StaffAdminBootstrapService bootstrap = mock(StaffAdminBootstrapService.class);
        new DataInitializer(bootstrap, "owner@example.test", null, "DEPLOYMENT_CHANGE_1", true).run();
        verify(bootstrap).provisionFirstAdmin("owner@example.test", null, "DEPLOYMENT_CHANGE_1");
    }
}
