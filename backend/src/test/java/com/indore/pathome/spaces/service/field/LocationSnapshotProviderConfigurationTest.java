package com.indore.pathome.spaces.service.field;

import com.indore.pathome.spaces.repository.EmployeeProfileRepository;
import com.indore.pathome.spaces.repository.GroundExecutiveCoverageRepository;
import com.indore.pathome.spaces.repository.GroundExecutiveSchedulingProfileRepository;
import com.indore.pathome.spaces.repository.GroundExecutiveShiftRepository;
import com.indore.pathome.spaces.repository.GroundExecutiveUnavailabilityRepository;
import com.indore.pathome.spaces.repository.ListingRepository;
import com.indore.pathome.spaces.repository.LocalityRepository;
import com.indore.pathome.spaces.repository.PropertyVisitRequestRepository;
import com.indore.pathome.spaces.repository.VisitPolicyRepository;
import com.indore.pathome.spaces.repository.VisitSessionItemRepository;
import com.indore.pathome.spaces.repository.VisitSessionRepository;
import com.indore.pathome.spaces.service.SchedulingRecommendationPolicy;
import com.indore.pathome.spaces.service.TravelTimeEstimator;
import com.indore.pathome.spaces.service.VisitOperationsAuthorizationService;
import com.indore.pathome.spaces.service.VisitSchedulingRecommendationService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

class LocationSnapshotProviderConfigurationTest {
    @Test
    void fallbackStartsContextAndCreatesRecommendationServiceWithoutLiveLocation() {
        new ApplicationContextRunner()
            .withUserConfiguration(StartupConfiguration.class)
            .withConfiguration(AutoConfigurations.of(LocationSnapshotProviderAutoConfiguration.class))
            .run(context -> {
            assertNull(context.getStartupFailure());
            var providers = context.getBeansOfType(LocationSnapshotProvider.class);
            assertEquals(1, providers.size());
            var provider = assertInstanceOf(NoLocationSnapshotProvider.class, providers.values().iterator().next());
            assertNotNull(context.getBean(VisitSchedulingRecommendationService.class));
            assertEquals(Optional.empty(), provider.latestFor(new FieldResourceKey("INTERNAL_GE", "7"), Instant.now()));
            });
    }

    @Test
    void fallbackBacksOffWhenAProductionProviderIsRegistered() {
        new ApplicationContextRunner()
            .withUserConfiguration(StartupConfiguration.class, ProductionProviderConfiguration.class)
            .withConfiguration(AutoConfigurations.of(LocationSnapshotProviderAutoConfiguration.class))
            .run(context -> {
            assertNull(context.getStartupFailure());
            var providers = context.getBeansOfType(LocationSnapshotProvider.class);
            assertEquals(1, providers.size());
            assertEquals("productionLocationSnapshotProvider", providers.keySet().iterator().next());
            assertNotNull(context.getBean(VisitSchedulingRecommendationService.class));
            });
    }

    @Configuration(proxyBeanMethods = false)
    @Import(VisitSchedulingRecommendationService.class)
    static class StartupConfiguration {
        @Bean VisitSessionRepository visitSessions() { return mock(VisitSessionRepository.class); }
        @Bean VisitSessionItemRepository visitSessionItems() { return mock(VisitSessionItemRepository.class); }
        @Bean PropertyVisitRequestRepository visitRequests() { return mock(PropertyVisitRequestRepository.class); }
        @Bean GroundExecutiveSchedulingProfileRepository schedulingProfiles() {
            return mock(GroundExecutiveSchedulingProfileRepository.class);
        }
        @Bean GroundExecutiveCoverageRepository coverage() { return mock(GroundExecutiveCoverageRepository.class); }
        @Bean GroundExecutiveShiftRepository shifts() { return mock(GroundExecutiveShiftRepository.class); }
        @Bean GroundExecutiveUnavailabilityRepository unavailability() {
            return mock(GroundExecutiveUnavailabilityRepository.class);
        }
        @Bean EmployeeProfileRepository employees() { return mock(EmployeeProfileRepository.class); }
        @Bean ListingRepository listings() { return mock(ListingRepository.class); }
        @Bean LocalityRepository localities() { return mock(LocalityRepository.class); }
        @Bean VisitPolicyRepository visitPolicies() { return mock(VisitPolicyRepository.class); }
        @Bean VisitOperationsAuthorizationService authorization() {
            return mock(VisitOperationsAuthorizationService.class);
        }
        @Bean TravelTimeEstimator travelTimeEstimator() { return mock(TravelTimeEstimator.class); }
        @Bean SchedulingRecommendationPolicy schedulingRecommendationPolicy() {
            return mock(SchedulingRecommendationPolicy.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ProductionProviderConfiguration {
        @Bean
        LocationSnapshotProvider productionLocationSnapshotProvider() {
            return (resource, asOf) -> Optional.empty();
        }
    }
}
