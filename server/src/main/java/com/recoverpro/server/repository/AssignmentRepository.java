package com.recoverpro.server.repository;

import com.recoverpro.server.entity.Assignment;
import com.recoverpro.server.enums.AssignmentStatus;
import com.recoverpro.server.enums.Priority;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AssignmentRepository extends JpaRepository<Assignment, UUID> {

    @Query("SELECT COUNT(a) FROM Assignment a WHERE a.agentId = :agentId AND a.assignmentDate = :date AND a.isDeleted = false AND a.status <> 'CANCELLED'")
    int countActiveAssignmentsByAgentAndDate(@Param("agentId") UUID agentId, @Param("date") LocalDate date);

    @Query("SELECT a FROM Assignment a WHERE a.agentId = :agentId AND a.assignmentDate = :date AND a.isDeleted = false ORDER BY a.sequenceOrder ASC NULLS LAST")
    List<Assignment> findByAgentIdAndDateOrdered(@Param("agentId") UUID agentId, @Param("date") LocalDate date);

    @Query("SELECT a FROM Assignment a WHERE a.agentId = :agentId AND a.assignmentDate = :date AND a.isDeleted = false")
    Page<Assignment> findByAgentIdAndDate(@Param("agentId") UUID agentId, @Param("date") LocalDate date, Pageable pageable);

    @Query("SELECT a FROM Assignment a WHERE a.assignmentDate = :date AND a.organizationId = :orgId AND a.isDeleted = false")
    Page<Assignment> findByDateAndOrganization(@Param("date") LocalDate date, @Param("orgId") UUID orgId, Pageable pageable);

    Optional<Assignment> findByIdAndIsDeletedFalse(UUID id);

    List<Assignment> findByAllocationIdAndIsDeletedFalseOrderByCreatedAtDesc(UUID allocationId);

    @Query("SELECT a FROM Assignment a WHERE a.allocationId IN :allocationIds AND a.isDeleted = false ORDER BY a.createdAt DESC")
    List<Assignment> findAllByAllocationIdsOrdered(@Param("allocationIds") List<UUID> allocationIds);

    Optional<Assignment> findByAllocationIdAndIsDeletedFalseAndStatusNot(UUID allocationId, AssignmentStatus status);

    boolean existsByAllocationIdAndIsDeletedFalseAndStatusNot(UUID allocationId, AssignmentStatus status);

    /** TASK 28.3: activation-checklist "run your first assignment" step. */
    boolean existsByOrganizationIdAndIsDeletedFalse(UUID organizationId);

    @Query("SELECT a FROM Assignment a WHERE a.allocationId IN :allocationIds AND a.isDeleted = false AND a.status <> 'CANCELLED'")
    List<Assignment> findActiveByAllocationIds(@Param("allocationIds") List<UUID> allocationIds);

    @Query("SELECT MAX(a.sequenceOrder) FROM Assignment a WHERE a.agentId = :agentId AND a.assignmentDate = :date AND a.isDeleted = false")
    Optional<Integer> findMaxSequenceOrderByAgentAndDate(@Param("agentId") UUID agentId, @Param("date") LocalDate date);

    @Query("SELECT a FROM Assignment a WHERE a.organizationId = :orgId AND a.isDeleted = false AND " +
            "(:agentId IS NULL OR a.agentId = :agentId) AND " +
            "(:date IS NULL OR a.assignmentDate = :date) AND " +
            "(:status IS NULL OR a.status = :status) AND " +
            "(:priority IS NULL OR a.priority = :priority)")
    Page<Assignment> findWithFilters(
            @Param("orgId") UUID orgId,
            @Param("agentId") UUID agentId,
            @Param("date") LocalDate date,
            @Param("status") AssignmentStatus status,
            @Param("priority") Priority priority,
            Pageable pageable);

    @Query("SELECT a.agentId, COUNT(a) FROM Assignment a WHERE a.assignmentDate = :date AND a.organizationId = :orgId AND a.isDeleted = false AND a.status <> 'CANCELLED' GROUP BY a.agentId")
    List<Object[]> countAssignmentsByAgentForDate(@Param("date") LocalDate date, @Param("orgId") UUID orgId);

    @Modifying
    @Query("UPDATE Assignment a SET a.status = :cancelled " +
           "WHERE a.organizationId = :orgId AND a.isDeleted = false AND a.status IN :openStatuses " +
           "AND a.allocationId IN (SELECT al.id FROM Allocation al " +
           "    WHERE al.organization.id = :orgId AND al.isDeleted = false AND al.fileUpload.id <> :newFileId)")
    int cancelOpenAssignmentsForDroppedLoans(@Param("orgId") UUID orgId,
                                             @Param("newFileId") UUID newFileId,
                                             @Param("openStatuses") Collection<AssignmentStatus> openStatuses,
                                             @Param("cancelled") AssignmentStatus cancelled);

    @Modifying
    @Query("UPDATE Assignment a SET a.isDeleted = true WHERE a.id = :id")
    void softDeleteById(@Param("id") UUID id);

    @Query("SELECT COUNT(a) FROM Assignment a WHERE a.organizationId = :orgId AND a.status = 'COMPLETED' AND a.isDeleted = false")
    long countCompletedByOrg(@Param("orgId") UUID orgId);

    @Query("SELECT COUNT(a) FROM Assignment a WHERE a.organizationId = :orgId AND a.assignmentDate = :date AND a.isDeleted = false")
    long countByOrgAndDate(@Param("orgId") UUID orgId, @Param("date") LocalDate date);

    @Query("SELECT COUNT(a) FROM Assignment a WHERE a.organizationId = :orgId AND a.assignmentDate = :date AND a.status = :status AND a.isDeleted = false")
    long countByOrgAndDateAndStatus(@Param("orgId") UUID orgId, @Param("date") LocalDate date, @Param("status") AssignmentStatus status);

    @Query("SELECT COUNT(a) FROM Assignment a WHERE a.agentId = :agentId AND a.assignmentDate BETWEEN :from AND :to AND a.isDeleted = false")
    long countAllByAgentAndDateRange(@Param("agentId") UUID agentId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT COUNT(a) FROM Assignment a WHERE a.agentId = :agentId AND a.assignmentDate BETWEEN :from AND :to AND a.status = 'COMPLETED' AND a.isDeleted = false")
    long countCompletedByAgentAndDateRange(@Param("agentId") UUID agentId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT a FROM Assignment a WHERE a.agentId = :agentId AND a.assignmentDate = :date AND a.isDeleted = false")
    List<Assignment> findAllByAgentAndDate(@Param("agentId") UUID agentId, @Param("date") LocalDate date);

    @Query("SELECT a FROM Assignment a JOIN FETCH a.allocation WHERE a.agentId = :agentId AND a.assignmentDate = :date AND a.isDeleted = false")
    List<Assignment> findAllByAgentAndDateWithAllocation(@Param("agentId") UUID agentId, @Param("date") LocalDate date);

    @Query("""
        SELECT a FROM Assignment a
        WHERE a.agentId = :agentId
          AND a.organizationId = :organizationId
          AND a.status IN :statuses
          AND a.isDeleted = false
        ORDER BY a.assignmentDate ASC, a.sequenceOrder ASC NULLS LAST
        """)
    Page<Assignment> findActiveByAgentId(
            @Param("agentId") UUID agentId,
            @Param("organizationId") UUID organizationId,
            @Param("statuses") Collection<AssignmentStatus> statuses,
            Pageable pageable);
}
