package com.recoverpro.server.repository;

import com.recoverpro.server.entity.ChatSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ChatSessionRepository extends JpaRepository<ChatSession, String> {

    Optional<ChatSession> findByIdAndIsActiveTrue(String id);

    Optional<ChatSession> findByIdAndAgentIdAndIsActiveTrue(String id, UUID agentId);

    Optional<ChatSession> findTopByAgentIdAndIsActiveTrueOrderByCreatedAtDesc(UUID agentId);

    List<ChatSession> findByAgentIdAndIsActiveTrueOrderByCreatedAtDesc(UUID agentId);

    Page<ChatSession> findByAgentIdOrderByCreatedAtDesc(UUID agentId, Pageable pageable);

    Page<ChatSession> findAllByAgentIdOrderByCreatedAtDesc(UUID agentId, Pageable pageable);

    long countByAgentIdAndIsActiveTrue(UUID agentId);

    @Modifying
    @Query("UPDATE ChatSession s SET s.isActive = false, s.endedAt = :now WHERE s.id = :id")
    int closeSession(@Param("id") String id, @Param("now") Instant now);

    @Modifying
    @Query("UPDATE ChatSession s SET s.isActive = false, s.endedAt = :now WHERE s.agentId = :agentId AND s.isActive = true")
    int closeAllSessionsForAgent(@Param("agentId") UUID agentId, @Param("now") Instant now);

    @Modifying
    @Query("UPDATE ChatSession s SET s.totalMessages = s.totalMessages + 1 WHERE s.id = :id")
    void incrementMessageCount(@Param("id") String id);

    @Modifying
    @Query("UPDATE ChatSession s SET s.totalMessages = s.totalMessages + :count WHERE s.id = :id")
    void incrementBy(@Param("id") String id, @Param("count") int count);

    @Modifying
    @Query("DELETE FROM ChatSession s WHERE s.id = :id")
    int deleteSessionById(@Param("id") String id);

    @Modifying
    @Query("UPDATE ChatSession s SET s.isActive = false, s.endedAt = :now WHERE s.isActive = true AND s.updatedAt < :cutoff")
    int closeIdleSessions(@Param("cutoff") Instant cutoff, @Param("now") Instant now);

    @Modifying
    @Query("DELETE FROM ChatSession s WHERE s.createdAt < :cutoff")
    int deleteSessionsOlderThan(@Param("cutoff") Instant cutoff);

    /** SYSTEM 18 TASK 18.4: agentFirstName is a denormalized (encrypted) snapshot of the FO's
     *  first name -- see PtpHistoryRepository#scrubChangedByName's javadoc for why a JPQL bulk
     *  UPDATE is the right tool here. */
    @Modifying
    @Query("UPDATE ChatSession s SET s.agentFirstName = :tombstone WHERE s.agentId = :agentId")
    int scrubAgentFirstName(@Param("agentId") UUID agentId, @Param("tombstone") String tombstone);
}
