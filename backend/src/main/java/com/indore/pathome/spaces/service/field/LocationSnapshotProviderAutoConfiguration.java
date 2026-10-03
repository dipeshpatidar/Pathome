package com.indore.pathome.spaces.service.field;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class LocationSnapshotProviderAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(LocationSnapshotProvider.class)
    public LocationSnapshotProvider noLocationSnapshotProvider() {
        return new NoLocationSnapshotProvider();
    }
}
