package com.indore.pathome.spaces.service.field;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

@Component
@ConditionalOnMissingBean(LocationSnapshotProvider.class)
public class NoLocationSnapshotProvider implements LocationSnapshotProvider {
    @Override
    public Optional<LocationSnapshot> latestFor(FieldResourceKey resource, Instant asOf) {
        return Optional.empty();
    }
}
