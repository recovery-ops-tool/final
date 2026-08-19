package com.recoverpro.server.repository;

import com.recoverpro.server.entity.User;
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
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    Optional<User> findByEmailIgnoreCase(String email);

    List<User> findByEmailIgnoreCaseIn(List<String> emails);

    boolean existsByEmail(String email);

    long countByOrganizationId(UUID organizationId);

    List<User> findByOrganizationId(UUID organizationId);

    @Query("SELECT u.email FROM User u WHERE u.organizationId = :orgId")
    List<String> findEmailsByOrganizationId(@Param("orgId") UUID orgId);

    @Query("SELECT u FROM User u WHERE u.organizationId = :orgId AND u.lastLoginAt IS NULL AND u.enabled = true ORDER BY u.createdAt DESC")
    List<User> findPendingInvitesByOrganizationId(@Param("orgId") UUID orgId);

    Page<User> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId, Pageable pageable);

    Page<User> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Query("SELECT u FROM User u WHERE u.accountLocked = true AND u.lockoutUntil < :now")
    List<User> findExpiredLockouts(@Param("now") Instant now);

    @Query("SELECT u FROM User u JOIN u.roles r WHERE u.organizationId = :orgId AND r.name = :roleName AND u.enabled = true ORDER BY u.firstName ASC, u.lastName ASC")
    List<User> findByOrganizationIdAndRoleName(@Param("orgId") UUID orgId, @Param("roleName") String roleName);

    @Query("SELECT DISTINCT u FROM User u JOIN u.roles r WHERE r.name IN :roleNames")
    List<User> findDistinctByRoleNameIn(@Param("roleNames") List<String> roleNames);

    @Modifying
    @Query("UPDATE User u SET u.failedLoginAttempts = 0, u.accountLocked = false, u.lockoutUntil = null WHERE u.id = :id")
    void resetLockout(@Param("id") UUID id);

    @Modifying
    @Query("UPDATE User u SET u.lastLoginAt = :loginAt, u.failedLoginAttempts = 0, u.accountLocked = false, u.lockoutUntil = null WHERE u.id = :id")
    void recordSuccessfulLogin(@Param("id") UUID id, @Param("loginAt") Instant loginAt);

    @Query("SELECT COUNT(u) FROM User u JOIN u.roles r WHERE r.name = :roleName")
    long countByRoleName(@Param("roleName") String roleName);

    @Query("SELECT COUNT(u) FROM User u JOIN u.roles r WHERE r.name = :roleName AND u.enabled = true")
    long countByRoleNameAndEnabledTrue(@Param("roleName") String roleName);

    @Query("SELECT COUNT(u) FROM User u JOIN u.roles r WHERE u.organizationId = :orgId AND r.name = :roleName AND u.enabled = true")
    long countByOrganizationIdAndRoleName(@Param("orgId") UUID orgId, @Param("roleName") String roleName);

    @Query("SELECT FUNCTION('TO_CHAR', u.createdAt, 'YYYY-MM'), COUNT(u) "
            + "FROM User u "
            + "WHERE u.createdAt >= :from "
            + "AND NOT EXISTS (SELECT 1 FROM u.roles r WHERE r.name = :excludeRole) "
            + "GROUP BY FUNCTION('TO_CHAR', u.createdAt, 'YYYY-MM') "
            + "ORDER BY FUNCTION('TO_CHAR', u.createdAt, 'YYYY-MM')")
    List<Object[]> findMonthlyUserGrowth(@Param("from") Instant from,
                                         @Param("excludeRole") String excludeRole);

    @Query("SELECT u.organizationId, COUNT(u) FROM User u WHERE u.organizationId IN :orgIds GROUP BY u.organizationId")
    List<Object[]> countGroupedByOrganizationIdIn(@Param("orgIds") List<UUID> orgIds);
}
