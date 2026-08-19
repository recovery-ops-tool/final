package com.recoverpro.server.repository;

import com.recoverpro.server.entity.PtpHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PtpHistoryRepository extends JpaRepository<PtpHistory, UUID> {

    List<PtpHistory> findAllByPtpIdOrderByChangedAtDesc(UUID ptpId);

    @Query("SELECT h FROM PtpHistory h WHERE h.allocationId = :allocationId ORDER BY h.changedAt DESC")
    List<PtpHistory> findFullHistoryByAllocationId(@Param("allocationId") UUID allocationId);

    /** SYSTEM 18 TASK 18.4: changedByName is a denormalized (encrypted) snapshot of the acting
     *  user's name -- not touched by fn_audit_log_immutable (that trigger only covers the six
     *  dedicated audit-log tables, not this one), so it is a genuine GDPR-erasure duplicate that
     *  needs its own scrub. A JPQL bulk UPDATE (not native SQL) still routes the parameter through
     *  the entity's @Convert(EncryptedStringConverter.class), so the tombstone value lands
     *  correctly re-encrypted, same as every other row in this column. */
    @Modifying
    @Query("UPDATE PtpHistory h SET h.changedByName = :tombstone WHERE h.changedBy = :userId")
    int scrubChangedByName(@Param("userId") UUID userId, @Param("tombstone") String tombstone);
}
