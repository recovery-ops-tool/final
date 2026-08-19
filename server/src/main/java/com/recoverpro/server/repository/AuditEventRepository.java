package com.recoverpro.server.repository;

import com.recoverpro.server.entity.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Persistence for {@code unified_audit_events}. SYSTEM 10 TASK 10.4 added
 * {@link JpaSpecificationExecutor} for the filterable auditor-export query surface
 * ({@link AuditEventSpecification}) -- writes still go through {@code AuditServiceImpl} directly.
 */
@Repository
public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID>, JpaSpecificationExecutor<AuditEvent> {
}
