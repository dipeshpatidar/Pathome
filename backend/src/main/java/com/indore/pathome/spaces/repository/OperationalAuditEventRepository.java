package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.OperationalAuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OperationalAuditEventRepository extends JpaRepository<OperationalAuditEvent, Long> {
}
