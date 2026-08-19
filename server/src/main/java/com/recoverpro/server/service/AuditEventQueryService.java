package com.recoverpro.server.service;

import com.recoverpro.server.dto.request.AuditEventFilterRequest;
import com.recoverpro.server.dto.response.AuditEventResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * SYSTEM 10 TASK 10.4: read path over {@code unified_audit_events}, deliberately separate from
 * {@link AuditService} -- that interface's own javadoc scopes it to writes only.
 */
public interface AuditEventQueryService {

    /**
     * @param orgIdOverride non-null only for an elevated platform-admin request, to narrow the
     *                      otherwise cross-org bypass back down to one org. Null for an ordinary
     *                      org-scoped caller -- RLS alone scopes those, see
     *                      {@link com.recoverpro.server.repository.AuditEventSpecification}.
     */
    Page<AuditEventResponse> search(AuditEventFilterRequest filter, UUID orgIdOverride, Pageable pageable);
}
