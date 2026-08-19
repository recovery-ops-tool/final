package com.recoverpro.server.repository;

import com.recoverpro.server.entity.ColumnSchema;
import com.recoverpro.server.enums.UploadType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ColumnSchemaRepository extends JpaRepository<ColumnSchema, UUID> {

    @Query("SELECT c FROM ColumnSchema c WHERE c.organization.id = :organizationId AND c.isActive = true ORDER BY c.sortOrder ASC")
    List<ColumnSchema> findAllActiveByOrganizationId(@Param("organizationId") UUID organizationId);

    @Query("SELECT c FROM ColumnSchema c WHERE c.organization.id = :organizationId AND c.entityType = :entityType "
            + "AND c.isActive = true ORDER BY c.sortOrder ASC")
    List<ColumnSchema> findAllActiveByOrganizationIdAndEntityType(@Param("organizationId") UUID organizationId,
                                                                  @Param("entityType") UploadType entityType);

    Optional<ColumnSchema> findByOrganizationIdAndNameAndIsActiveTrue(UUID organizationId, String name);

    Optional<ColumnSchema> findByOrganizationIdAndEntityTypeAndNameAndIsActiveTrue(
            UUID organizationId, UploadType entityType, String name);

    boolean existsByOrganizationIdAndName(UUID organizationId, String name);

    /** TASK 28.3: activation-checklist "configure column schemas" step. */
    boolean existsByOrganizationId(UUID organizationId);

    boolean existsByOrganizationIdAndEntityTypeAndName(UUID organizationId, UploadType entityType, String name);

    @Query("SELECT c FROM ColumnSchema c WHERE c.organization.id = :organizationId ORDER BY c.sortOrder ASC")
    List<ColumnSchema> findAllByOrganizationId(@Param("organizationId") UUID organizationId);

    @Query("SELECT c FROM ColumnSchema c WHERE c.organization.id = :organizationId AND c.entityType = :entityType "
            + "ORDER BY c.sortOrder ASC")
    List<ColumnSchema> findAllByOrganizationIdAndEntityType(@Param("organizationId") UUID organizationId,
                                                            @Param("entityType") UploadType entityType);
}
