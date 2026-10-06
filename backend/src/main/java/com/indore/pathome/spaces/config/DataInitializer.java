package com.indore.pathome.spaces.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import com.indore.pathome.spaces.service.StaffAdminBootstrapService;

@Component
public class DataInitializer implements CommandLineRunner {

    private final StaffAdminBootstrapService staffAdminBootstrapService;
    private final String bootstrapAdminEmail;
    private final String bootstrapAdminPassword;
    private final String bootstrapOperatorReference;
    private final boolean firstAdminProvisioningEnabled;

    public DataInitializer(StaffAdminBootstrapService staffAdminBootstrapService,
                           @Value("${APP_BOOTSTRAP_ADMIN_EMAIL:}") String bootstrapAdminEmail,
                           @Value("${APP_BOOTSTRAP_ADMIN_PASSWORD:}") String bootstrapAdminPassword,
                           @Value("${APP_BOOTSTRAP_OPERATOR_REFERENCE:}") String bootstrapOperatorReference,
                           @Value("${APP_FIRST_ADMIN_PROVISIONING_ENABLED:false}") boolean firstAdminProvisioningEnabled) {
        this.staffAdminBootstrapService = staffAdminBootstrapService;
        this.bootstrapAdminEmail = bootstrapAdminEmail;
        this.bootstrapAdminPassword = bootstrapAdminPassword;
        this.bootstrapOperatorReference = bootstrapOperatorReference;
        this.firstAdminProvisioningEnabled = firstAdminProvisioningEnabled;
    }

    @Override
    public void run(String... args) {
        if (!firstAdminProvisioningEnabled) return;
        staffAdminBootstrapService.provisionFirstAdmin(bootstrapAdminEmail, bootstrapAdminPassword,
                bootstrapOperatorReference);
    }
}
