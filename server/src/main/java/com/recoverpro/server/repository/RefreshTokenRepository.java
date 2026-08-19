package com.recoverpro.server.repository;

import com.recoverpro.server.entity.RefreshToken;
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
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    List<RefreshToken> findByUser_IdAndRevokedFalse(UUID userId);

    /**
     * Device history — deliberately spans revoked tokens.
     *
     * Rotation revokes the presenting token before the replacement is inspected,
     * so the live (revoked=false) set no longer contains the device that is
     * currently authenticating. Answering "have we seen this device?" from that
     * set therefore reports every refresh from a user with a second active
     * session as a brand-new device. History is the correct source: a device is
     * known if it ever held a token, revoked or not.
     *
     * Horizon is the refresh-token lifetime (7d) because MaintenanceScheduler
     * deletes rows past expiresAt — a device dormant longer than that is
     * re-challenged, which is the intended behaviour.
     */
    boolean existsByUser_IdAndDeviceId(UUID userId, String deviceId);

    /**
     * The genuine previous session, revoked or not — used for impossible-travel.
     * The active set would yield some other concurrent session, not the one this
     * login actually follows.
     */
    Optional<RefreshToken> findFirstByUser_IdOrderByCreatedAtDesc(UUID userId);

    @Query("SELECT rt FROM RefreshToken rt WHERE rt.tokenPrefix = :prefix AND rt.revoked = false")
    List<RefreshToken> findByTokenPrefixAndRevokedFalse(@Param("prefix") String prefix);

    @Query("SELECT rt FROM RefreshToken rt WHERE rt.tokenPrefix = :prefix")
    List<RefreshToken> findByTokenPrefix(@Param("prefix") String prefix);

    @Modifying
    @Query("UPDATE RefreshToken rt SET rt.revoked = true, rt.revokedAt = :now WHERE rt.user.id = :userId AND rt.revoked = false")
    void revokeAllByUserId(@Param("userId") UUID userId, @Param("now") Instant now);

    /** SYSTEM 08 TASK 8.2.c: "log out every other device, stay signed in here." Deliberately
     *  scoped by deviceId, not by the presenting refresh token's own id -- the caller may be
     *  acting from a valid ACCESS token (no refresh token in hand at all for this request), so
     *  there is no single row to naturally exclude by id the way {@link #revokeIfActive} does. */
    @Modifying
    @Query("UPDATE RefreshToken rt SET rt.revoked = true, rt.revokedAt = :now "
            + "WHERE rt.user.id = :userId AND rt.revoked = false "
            + "AND (rt.deviceId IS NULL OR rt.deviceId <> :currentDeviceId)")
    int revokeAllByUserIdExceptDevice(@Param("userId") UUID userId,
                                       @Param("currentDeviceId") String currentDeviceId,
                                       @Param("now") Instant now);

    @Modifying
    @Query("UPDATE RefreshToken rt SET rt.revoked = true, rt.revokedAt = :now WHERE rt.tokenHash = :hash AND rt.revoked = false")
    int revokeIfActive(@Param("hash") String hash, @Param("now") Instant now);

    @Modifying
    @Query("UPDATE RefreshToken rt SET rt.revoked = true, rt.revokedAt = :now WHERE rt.tokenHash = :hash")
    void revokeByTokenHash(@Param("hash") String hash, @Param("now") Instant now);

    @Modifying
    @Query("DELETE FROM RefreshToken rt WHERE rt.expiresAt < :now")
    void deleteExpiredTokens(@Param("now") Instant now);
}
