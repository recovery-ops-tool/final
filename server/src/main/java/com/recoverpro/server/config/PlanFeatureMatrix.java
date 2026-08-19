package com.recoverpro.server.config;

import com.recoverpro.server.entity.OrgSubscription.Plan;

import java.util.Map;
import java.util.Set;

public final class PlanFeatureMatrix {

    private PlanFeatureMatrix() {}

    public static final String LUCIEN_AI           = "LUCIEN_AI";
    public static final String ADVANCED_REPORTS    = "ADVANCED_REPORTS";
    public static final String CUSTOM_INTEGRATIONS = "CUSTOM_INTEGRATIONS";

    public static final Set<String> ALL_GATED_FLAGS = Set.of(
            LUCIEN_AI, ADVANCED_REPORTS, CUSTOM_INTEGRATIONS
    );

    private static final Map<Plan, Set<String>> MATRIX = Map.of(
            Plan.NONE,       Set.of(),
            Plan.STARTER,    Set.of(),
            Plan.GROWTH,     Set.of(LUCIEN_AI, ADVANCED_REPORTS),
            Plan.ENTERPRISE, Set.of(LUCIEN_AI, ADVANCED_REPORTS, CUSTOM_INTEGRATIONS)
    );

    /** MAX_USERS / MAX_ACTIVE_LOANS: seat and case-volume caps enforced by {@code EntitlementService}
     *  (Billing Ledger design doc §6). TASK 20.4 added the four usage-cost caps below (file
     *  uploads/month, total storage, report generations/month, Lucien AI monthly token budget --
     *  the direct-cost-driver resources TASK 20.4.a names). Values for all seven keys are
     *  placeholder defaults, not confirmed business numbers -- flagged for product sign-off before
     *  this enforcement goes live in production, same as every other placeholder default
     *  introduced in that design doc. LUCIEN_MONTHLY_TOKEN_LIMIT's GROWTH value (1,000,000) is the
     *  one exception: it's not a new guess, it's the flat default `LucienTokenBudgetServiceImpl`
     *  already shipped with pre-TASK-20.4 (see docs/SYSTEM-20-ENTITLEMENT.md), now made plan-aware
     *  instead of applying identically to every org regardless of plan. */
    public static final String MAX_USERS        = "MAX_USERS";
    public static final String MAX_ACTIVE_LOANS  = "MAX_ACTIVE_LOANS";
    public static final String FILE_UPLOADS_PER_MONTH      = "FILE_UPLOADS_PER_MONTH";
    public static final String STORAGE_BYTES                = "STORAGE_BYTES";
    public static final String REPORT_GENERATIONS_PER_MONTH = "REPORT_GENERATIONS_PER_MONTH";
    public static final String LUCIEN_MONTHLY_TOKEN_LIMIT   = "LUCIEN_MONTHLY_TOKEN_LIMIT";

    public static final Set<String> ALL_LIMIT_KEYS = Set.of(
            MAX_USERS, MAX_ACTIVE_LOANS,
            FILE_UPLOADS_PER_MONTH, STORAGE_BYTES, REPORT_GENERATIONS_PER_MONTH, LUCIEN_MONTHLY_TOKEN_LIMIT
    );

    private static final long GB = 1_073_741_824L;

    /** A plan with no entry for a given limit key -- or an explicit {@code null} value -- means
     *  unlimited under that plan (Enterprise). Absence is deliberate, not an oversight. */
    private static final Map<Plan, Map<String, Long>> LIMITS = Map.of(
            Plan.NONE,       Map.of(MAX_USERS, 0L,  MAX_ACTIVE_LOANS, 0L,
                    FILE_UPLOADS_PER_MONTH, 0L, STORAGE_BYTES, 0L,
                    REPORT_GENERATIONS_PER_MONTH, 0L, LUCIEN_MONTHLY_TOKEN_LIMIT, 0L),
            Plan.STARTER,    Map.of(MAX_USERS, 5L,  MAX_ACTIVE_LOANS, 2_000L,
                    FILE_UPLOADS_PER_MONTH, 50L, STORAGE_BYTES, 5 * GB,
                    REPORT_GENERATIONS_PER_MONTH, 20L, LUCIEN_MONTHLY_TOKEN_LIMIT, 0L),
            Plan.GROWTH,     Map.of(MAX_USERS, 25L, MAX_ACTIVE_LOANS, 25_000L,
                    FILE_UPLOADS_PER_MONTH, 500L, STORAGE_BYTES, 50 * GB,
                    REPORT_GENERATIONS_PER_MONTH, 200L, LUCIEN_MONTHLY_TOKEN_LIMIT, 1_000_000L),
            Plan.ENTERPRISE, Map.of()
    );

    public static boolean includes(Plan plan, String flagKey) {
        if (plan == null || flagKey == null) return false;
        return MATRIX.getOrDefault(plan, Set.of()).contains(flagKey);
    }

    /** Null return means unlimited under this plan. */
    public static Long limitFor(Plan plan, String limitKey) {
        if (plan == null || limitKey == null) return null;
        return LIMITS.getOrDefault(plan, Map.of()).get(limitKey);
    }

    public static String requiredPlanLabel(String flagKey) {
        return switch (flagKey) {
            case LUCIEN_AI, ADVANCED_REPORTS -> "Growth";
            case CUSTOM_INTEGRATIONS         -> "Enterprise";
            default                          -> "Growth";
        };
    }
}
