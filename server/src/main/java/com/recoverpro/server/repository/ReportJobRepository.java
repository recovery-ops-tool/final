package com.recoverpro.server.repository;

import com.recoverpro.server.entity.ReportJob;
import com.recoverpro.server.enums.ReportStatus;
import com.recoverpro.server.enums.ReportType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReportJobRepository extends JpaRepository<ReportJob, UUID> {

    Optional<ReportJob> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Page<ReportJob> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId, Pageable pageable);

    @Query("SELECT r FROM ReportJob r WHERE r.organizationId = :orgId " +
            "AND (:type IS NULL OR r.reportType = :type) " +
            "AND (:status IS NULL OR r.status = :status) " +
            "ORDER BY r.createdAt DESC")
    Page<ReportJob> findWithFilters(
            @Param("orgId") UUID orgId,
            @Param("type") ReportType type,
            @Param("status") ReportStatus status,
            Pageable pageable);

    List<ReportJob> findByStatus(ReportStatus status);

    @Modifying
    @Query("UPDATE ReportJob r SET r.status = :status, r.errorMessage = :error WHERE r.id = :id")
    void updateStatusAndError(@Param("id") UUID id, @Param("status") ReportStatus status, @Param("error") String error);

    Optional<ReportJob> findById(UUID jobId);

    boolean existsByRequestedByAndStatusIn(UUID requestedBy, Collection<ReportStatus> statuses);

    /** TASK 20.4: live count for the monthly report-generation entitlement cap. */
    long countByOrganizationIdAndCreatedAtAfter(UUID organizationId, java.time.Instant since);
}