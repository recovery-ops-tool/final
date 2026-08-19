package com.recoverpro.server.repository;

import com.recoverpro.server.entity.PtpRecord;
import com.recoverpro.server.enums.PtpStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public interface PtpRepository extends JpaRepository<PtpRecord, UUID>, JpaSpecificationExecutor<PtpRecord> {

    Optional<PtpRecord> findByIdAndIsDeletedFalse(UUID id);

    Page<PtpRecord> findAllByIsDeletedFalse(Pageable pageable);

    /** SYSTEM-PLAN 26.1 backfill: PtpRecord has no organization_id of its own, so org scope is
     *  derived via allocation_id -> allocations.organization_id, mirroring rls_ptp_records_isolation. */
    @Query("SELECT p FROM PtpRecord p WHERE p.allocationId IN " +
            "(SELECT a.id FROM Allocation a WHERE a.organization.id = :orgId) AND p.isDeleted = false")
    Page<PtpRecord> findAllByOrganizationIdPaged(@Param("orgId") UUID orgId, Pageable pageable);

    List<PtpRecord> findAllByAllocationIdAndIsDeletedFalse(UUID allocationId);

    /** Batch dedup for historical imports - narrows on both axes so the fetch stays bounded. */
    List<PtpRecord> findByAllocationIdInAndPromisedDateInAndIsDeletedFalse(
            Set<UUID> allocationIds, Set<LocalDate> promisedDates);

    @Query("SELECT p FROM PtpRecord p WHERE p.allocationId = :allocationId AND p.isDeleted = false ORDER BY p.createdAt DESC")
    List<PtpRecord> findAllByAllocationIdOrderByCreatedAtDesc(@Param("allocationId") UUID allocationId);

    @Query("SELECT p FROM PtpRecord p WHERE p.allocationId IN :allocationIds AND p.isDeleted = false ORDER BY p.createdAt DESC")
    List<PtpRecord> findAllByAllocationIdsOrdered(@Param("allocationIds") List<UUID> allocationIds);

    @Query("SELECT p FROM PtpRecord p WHERE p.status = 'PENDING' AND p.promisedDate < :today AND p.isDeleted = false")
    List<PtpRecord> findExpiredPendingPtps(@Param("today") LocalDate today);

    @Query("SELECT p FROM PtpRecord p WHERE p.status = 'PENDING' AND p.promisedDate = :tomorrow AND p.reminderSent = false AND p.isDeleted = false")
    List<PtpRecord> findPtpsDueForReminder(@Param("tomorrow") LocalDate tomorrow);

    @Modifying
    @Query("UPDATE PtpRecord p SET p.status = 'BROKEN', p.brokenAt = CURRENT_TIMESTAMP, p.brokenReason = :reason WHERE p.id IN :ids")
    int bulkMarkAsBroken(@Param("ids") List<UUID> ids, @Param("reason") String reason);

    @Modifying
    @Query("UPDATE PtpRecord p SET p.reminderSent = true, p.reminderSentAt = CURRENT_TIMESTAMP WHERE p.id IN :ids")
    int bulkMarkReminderSent(@Param("ids") List<UUID> ids);

    /** SYSTEM 18 TASK 18.4: agentName is a denormalized (encrypted) snapshot of the assigned
     *  agent's name -- see PtpHistoryRepository#scrubChangedByName's javadoc for why a JPQL bulk
     *  UPDATE is the right tool here (re-encrypts via the entity converter, not a raw SQL write). */
    @Modifying
    @Query("UPDATE PtpRecord p SET p.agentName = :tombstone WHERE p.agentId = :agentId")
    int scrubAgentName(@Param("agentId") UUID agentId, @Param("tombstone") String tombstone);

    @Query("""
        SELECT p FROM PtpRecord p
        WHERE p.agentId = :agentId
        AND p.isDeleted = false
        AND (:status IS NULL OR p.status = :status)
        ORDER BY p.promisedDate ASC
    """)
    Page<PtpRecord> findByAgentIdAndStatus(
            @Param("agentId") UUID agentId,
            @Param("status") PtpStatus status,
            Pageable pageable);

    @Query("""
        SELECT COUNT(p), SUM(p.promisedAmount), SUM(p.collectedAmount)
        FROM PtpRecord p
        WHERE p.agentId = :agentId
        AND p.status IN ('FULFILLED', 'PARTIALLY_FULFILLED')
        AND p.isDeleted = false
    """)
    List<Object[]> findFulfillmentStatsByAgent(@Param("agentId") UUID agentId);

    boolean existsByAllocationIdAndStatusAndIsDeletedFalse(UUID allocationId, PtpStatus status);

    @Query("""
           SELECT (COUNT(p) > 0)
           FROM PtpRecord p
           WHERE p.allocationId = :allocationId
             AND p.status = com.recoverpro.server.enums.PtpStatus.BROKEN
             AND p.brokenAt >= :cutoff
             AND p.isDeleted = false
           """)
    boolean existsRecentlyBroken(
            @Param("allocationId") UUID allocationId,
            @Param("cutoff") Instant cutoff);

    @Query("SELECT COUNT(p) FROM PtpRecord p WHERE p.agentId = :agentId AND p.status = :status AND p.isDeleted = false")
    long countByAgentIdAndStatus(@Param("agentId") UUID agentId, @Param("status") PtpStatus status);

    @Query("SELECT COUNT(p) FROM PtpRecord p WHERE p.agentId = :agentId AND p.promisedDate = :date AND p.isDeleted = false")
    long countByAgentIdAndPromisedDate(@Param("agentId") UUID agentId, @Param("date") LocalDate date);

    @Query("SELECT COUNT(p) FROM PtpRecord p WHERE p.agentId = :agentId AND p.status = :status AND p.promisedDate BETWEEN :from AND :to AND p.isDeleted = false")
    long countByAgentStatusAndDateRange(@Param("agentId") UUID agentId, @Param("status") PtpStatus status, @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT COUNT(p) FROM PtpRecord p WHERE p.allocationId IN (SELECT a.allocationId FROM Assignment a WHERE a.organizationId = :orgId) AND p.status = 'PENDING' AND p.isDeleted = false")
    long countPendingByOrg(@Param("orgId") UUID orgId);

    @Query("SELECT COUNT(p) FROM PtpRecord p WHERE p.allocationId IN (SELECT a.allocationId FROM Assignment a WHERE a.organizationId = :orgId) AND p.status = 'BROKEN' AND p.promisedDate BETWEEN :from AND :to AND p.isDeleted = false")
    long countBrokenByOrgAndRange(@Param("orgId") UUID orgId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT COUNT(p) FROM PtpRecord p WHERE p.allocationId IN (SELECT a.allocationId FROM Assignment a WHERE a.organizationId = :orgId) AND p.status = :status AND p.promisedDate BETWEEN :from AND :to AND p.isDeleted = false")
    long countByOrgAndStatusAndDateRange(@Param("orgId") UUID orgId, @Param("status") PtpStatus status, @Param("from") LocalDate from, @Param("to") LocalDate to);

    List<PtpRecord> findByAllocationIdIn(List<UUID> allocationIds);

    @Query("SELECT FUNCTION('TO_CHAR', p.promisedDate, 'YYYY-MM') as month, COUNT(p), COALESCE(SUM(p.promisedAmount), 0) FROM PtpRecord p WHERE p.allocationId IN (SELECT a.allocationId FROM Assignment a WHERE a.organizationId = :orgId) AND p.isDeleted = false AND p.promisedDate >= :from GROUP BY FUNCTION('TO_CHAR', p.promisedDate, 'YYYY-MM') ORDER BY month DESC")
    List<Object[]> findMonthlyTrendByOrg(@Param("orgId") UUID orgId, @Param("from") LocalDate from);

    @Query("SELECT COALESCE(SUM(p.promisedAmount), 0) FROM PtpRecord p WHERE p.allocationId IN (SELECT a.allocationId FROM Assignment a WHERE a.organizationId = :orgId) AND p.status = 'PENDING' AND p.promisedDate = :date AND p.isDeleted = false")
    BigDecimal sumPendingAmountByOrgAndDate(@Param("orgId") UUID orgId, @Param("date") LocalDate date);

    @Query("SELECT p.agentId, COUNT(p), COALESCE(SUM(p.promisedAmount), 0) FROM PtpRecord p WHERE p.agentId IN :agentIds AND p.createdAt >= :from AND p.createdAt < :to AND p.isDeleted = false GROUP BY p.agentId")
    List<Object[]> countAndSumByAgentsForDate(@Param("agentIds") List<UUID> agentIds, @Param("from") Instant from, @Param("to") Instant to);

    @Query("SELECT COUNT(p), COALESCE(SUM(p.promisedAmount), 0) FROM PtpRecord p WHERE p.agentId IN :agentIds AND p.createdAt >= :from AND p.createdAt < :to AND p.isDeleted = false")
    List<Object[]> countAndSumForOrgDate(@Param("agentIds") List<UUID> agentIds, @Param("from") Instant from, @Param("to") Instant to);
}
