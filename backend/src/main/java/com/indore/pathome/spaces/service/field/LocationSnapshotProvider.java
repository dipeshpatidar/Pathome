package com.indore.pathome.spaces.service.field;

import java.time.Instant;
import java.util.Optional;

public interface LocationSnapshotProvider {
    Optional<LocationSnapshot> latestFor(FieldResourceKey resource, Instant asOf);
}
