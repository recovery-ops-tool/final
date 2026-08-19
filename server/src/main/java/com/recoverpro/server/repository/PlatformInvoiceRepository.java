package com.recoverpro.server.repository;

import com.recoverpro.server.entity.PlatformInvoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PlatformInvoiceRepository extends JpaRepository<PlatformInvoice, UUID> {

    Optional<PlatformInvoice> findByProviderInvoiceId(String providerInvoiceId);

    /** One org's invoice history, newest first. */
    List<PlatformInvoice> findByOrgIdOrderByIssuedAtDesc(UUID orgId);

    boolean existsByOrgId(UUID orgId);

    /**
     * Collected revenue bucketed by {@code unit} -- only invoices Stripe settled.
     *
     * <p>Buckets truncate in IST to match the reporting convention the rest of
     * the platform console uses; truncating in UTC would push a late-evening IST
     * payment into the previous bucket.
     *
     * <p>{@code unit} is bound as a parameter to Postgres's
     * {@code date_trunc(text, timestamp)} rather than concatenated into the SQL.
     * Callers must still restrict it to a known set -- an unrecognised unit makes
     * Postgres raise, which would surface as a 500 rather than a 400.
     *
     * @return rows of {@code [bucket_start, total_paise, count]}, ascending.
     *         Buckets with no settled invoice are absent; callers zero-fill.
     */
    @Query(value =
            "SELECT date_trunc(:unit, pi.paid_at AT TIME ZONE 'Asia/Kolkata') AS bucket, " +
            "       COALESCE(SUM(pi.amount_paid), 0) AS total, " +
            "       COUNT(*) AS cnt " +
            "FROM platform_invoices pi " +
            "WHERE pi.status = 'paid' AND pi.paid_at IS NOT NULL AND pi.paid_at >= :from " +
            "GROUP BY 1 ORDER BY 1", nativeQuery = true)
    List<Object[]> findCollectedRevenueBucketed(@Param("unit") String unit,
                                                @Param("from") Instant from);

    /**
     * Lifetime collected revenue per org, in one pass.
     *
     * <p>Grouped rather than per-row on purpose: the console lists every org at
     * once, so a per-org lookup would be an N+1 across the whole tenant base.
     *
     * @return rows of {@code [org_id, total_paise]}; orgs that never paid are
     *         absent rather than zero.
     */
    @Query(value =
            "SELECT pi.org_id, COALESCE(SUM(pi.amount_paid), 0) " +
            "FROM platform_invoices pi " +
            "WHERE pi.status = 'paid' " +
            "GROUP BY pi.org_id", nativeQuery = true)
    List<Object[]> findLifetimeRevenueByOrg();

    /** Single-org variant, for the paths that touch one org rather than the whole list. */
    @Query("SELECT COALESCE(SUM(p.amountPaid), 0) FROM PlatformInvoice p " +
           "WHERE p.orgId = :orgId AND p.status = 'paid'")
    long sumCollectedByOrg(@Param("orgId") UUID orgId);
}
