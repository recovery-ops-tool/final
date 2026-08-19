package com.recoverpro.server.repository;

import com.recoverpro.server.dto.request.AuditEventFilterRequest;
import com.recoverpro.server.entity.AuditEvent;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class AuditEventSpecification {

    private AuditEventSpecification() {}

    /**
     * SYSTEM 10 TASK 10.4.b: {@code orgIdOverride} is deliberately not derived from the caller's
     * own org for an ordinary org-scoped caller -- RLS ({@code rls_unified_audit_events_isolation},
     * V085) already restricts every query to {@code current_org_id()}, so adding an app-level
     * equality predicate for that case would duplicate the control for no benefit. This parameter
     * exists only for the platform-admin path: once {@code PlatformAdminAccessGuard} has elevated
     * the request past RLS entirely, the bypass has no per-org boundary of its own, so the caller
     * (the controller) narrows the otherwise-global result set back down to the one org named in
     * the admin's cross-org-access reason. Pass {@code null} for every non-elevated caller.
     */
    public static Specification<AuditEvent> withFilters(AuditEventFilterRequest filter, UUID orgIdOverride) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (orgIdOverride != null)
                predicates.add(cb.equal(root.get("organizationId"), orgIdOverride));
            if (filter.getFrom() != null)
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), filter.getFrom()));
            if (filter.getTo() != null)
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), filter.getTo()));
            if (filter.getActorUserId() != null)
                predicates.add(cb.equal(root.get("actorUserId"), filter.getActorUserId()));
            if (filter.getAction() != null)
                predicates.add(cb.equal(root.get("action"), filter.getAction()));
            if (filter.getResourceType() != null)
                predicates.add(cb.equal(root.get("resourceType"), filter.getResourceType()));
            if (filter.getSeverity() != null)
                predicates.add(cb.equal(root.get("severity"), filter.getSeverity()));
            if (filter.getResult() != null)
                predicates.add(cb.equal(root.get("result"), filter.getResult()));
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
