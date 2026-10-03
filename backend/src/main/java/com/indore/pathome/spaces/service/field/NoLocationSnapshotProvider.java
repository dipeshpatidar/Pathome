package com.indore.pathome.spaces.service.field;

import java.time.Instant;
import java.util.Optional;

public class NoLocationSnapshotProvider implements LocationSnapshotProvider {
    @Override
    public Optional<LocationSnapshot> latestFor(FieldResourceKey resource, Instant asOf) {
        return Optional.empty();
    }
}
