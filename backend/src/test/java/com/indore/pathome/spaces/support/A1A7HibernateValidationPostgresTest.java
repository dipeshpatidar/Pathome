package com.indore.pathome.spaces.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(properties = {
        "spring.task.scheduling.enabled=false",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.default_schema=${PATHOME_A1_A7_SCHEMA}",
        "spring.jpa.show-sql=false",
        "APP_FIRST_ADMIN_PROVISIONING_ENABLED=false",
        "app.jwt.secret=pathome-a1-a7-hibernate-jwt-secret-32-bytes",
        "pathome.visit.otp.hmac-secret=pathome-a1-a7-visit-otp-secret-32-bytes",
        "pathome.crm.contact-encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "pathome.crm.contact-encryption-key-id=a1a7-primary",
        "pathome.crm.public-origin=https://pathome.test",
        "pathome.crm.meta-source-enabled=false",
        "pathome.crm.phone-verification-simulator-enabled=true",
        "pathome.crm.secure-cookies=false",
        "pathome.crm.phone-delivery-poll-delay-ms=3600000",
        "cloudinary.api-key=a1-a7-test-key",
        "cloudinary.api-secret=a1-a7-test-secret",
        "pathome.visit.outbox.poll-delay-ms=3600000",
        "pathome.visit.no-show-settlement-delay-ms=3600000",
        "pathome.visit.entitlement-reservation-poll-delay-ms=3600000",
        "pathome.visit.overrun-alert-poll-delay-ms=3600000",
        "search.learning.autonomous.enabled=false"
})
@Import(A1A7RuntimeConfiguration.class)
@ContextConfiguration(initializers = A1A7HarnessContextInitializer.class)
@EnabledIfEnvironmentVariable(named = A1A7IsolatedTarget.TEST_FLAG, matches = "true")
class A1A7HibernateValidationPostgresTest {
    @Autowired private DataSource dataSource;

    @Test
    void hibernateValidatesEveryMappedEntityAgainstV63Schema() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            try (var statement = connection.createStatement();
                 var rows = statement.executeQuery("select current_database()")) {
                rows.next();
                assertEquals(A1A7IsolatedTarget.DATABASE, rows.getString(1));
            }
        }
    }
}
