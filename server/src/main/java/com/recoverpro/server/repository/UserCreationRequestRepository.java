package com.recoverpro.server.repository;

import com.recoverpro.server.entity.UserCreationRequest;
import com.recoverpro.server.entity.UserCreationRequest.RequestStatus;
import com.recoverpro.server.entity.UserCreationRequest.RequestedRole;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface UserCreationRequestRepository extends JpaRepository<UserCreationRequest, UUID> {

    Page<UserCreationRequest> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId, Pageable pageable);

    List<UserCreationRequest> findByOrganizationIdAndStatus(UUID organizationId, RequestStatus status);

    Page<UserCreationRequest> findByStatusOrderByCreatedAtDesc(RequestStatus status, Pageable pageable);

    boolean existsByRequestedEmailAndOrganizationId(String email, UUID organizationId);

    boolean existsByRequestedEmailAndStatus(String email, RequestStatus status);

    Page<UserCreationRequest> findByRequestedRoleAndStatusOrderByCreatedAtDesc(
            RequestedRole role, RequestStatus status, Pageable pageable);

    Page<UserCreationRequest> findByOrganizationIdAndRequestedRoleAndStatusOrderByCreatedAtDesc(
            UUID organizationId, RequestedRole role, RequestStatus status, Pageable pageable);

    Page<UserCreationRequest> findByRequestedByIdOrderByCreatedAtDesc(UUID requestedById, Pageable pageable);

    long countByRequestedRoleAndStatus(RequestedRole role, RequestStatus status);

    long countByOrganizationIdAndRequestedRoleAndStatus(UUID organizationId, RequestedRole role, RequestStatus status);

    /** SYSTEM 18 TASK 18.4: an approved request keeps its own (unencrypted requestedEmail,
     *  encrypted requestedFirstName/LastName) copy of the identity later assigned to createdUser
     *  -- a genuine GDPR-erasure duplicate outside the users table. requestedEmail is plaintext
     *  here (mirrors users.email, which is also unencrypted -- see docs/PRIVACY.md), so it is
     *  overwritten directly rather than through the encryption converter. */
    @Modifying
    @Query("UPDATE UserCreationRequest r SET r.requestedEmail = :tombstoneEmail, "
            + "r.requestedFirstName = :tombstoneName, r.requestedLastName = :tombstoneName "
            + "WHERE r.createdUser.id = :userId")
    int scrubByCreatedUserId(@Param("userId") UUID userId,
                              @Param("tombstoneEmail") String tombstoneEmail,
                              @Param("tombstoneName") String tombstoneName);
}
