package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.AutomaticVisitRoutingControl;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface AutomaticVisitRoutingControlRepository
        extends JpaRepository<AutomaticVisitRoutingControl, Short> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select control from AutomaticVisitRoutingControl control where control.id = 1")
    Optional<AutomaticVisitRoutingControl> findLocked();

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select control from AutomaticVisitRoutingControl control where control.id = 1")
    Optional<AutomaticVisitRoutingControl> findShared();
}
