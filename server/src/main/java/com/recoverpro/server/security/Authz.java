package com.recoverpro.server.security;

/**
 * SYSTEM 09 TASK 9.3: centralizes the role-expression literals that were previously duplicated
 * (often with the roles in a different order, or an accidentally-repeated role name within one
 * expression -- both semantically identical to the canonical form, just not caught by a literal
 * grep) across dozens of controllers, each under its own locally-named constant (ADMINS, READERS,
 * LEADS, ADMIN_ROLES, ...). {@code @PreAuthorize} requires a compile-time constant, so a plain
 * {@code public static final String} works directly as the annotation value or as the
 * initializer for a controller's own local, more contextually-named constant
 * (e.g. {@code private static final String READERS = Authz.LEADS;}) -- both forms are used across
 * the codebase, deliberately: the per-controller local name stays more readable at each
 * {@code @PreAuthorize} call site than {@code Authz.LEADS} would be, while the actual role-list
 * text exists exactly once, here.
 *
 * <p>Pure consolidation -- every constant's value is unchanged from whichever of its duplicate
 * spellings was already in the codebase (roles reordered/de-duplicated where they differed only
 * in that, not in which roles they actually named).
 */
public final class Authz {

    private Authz() {}

    /** Platform or org admin only. */
    public static final String ADMINS = "hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN')";

    /** Admins plus anyone leading a team (MANAGER, TL). */
    public static final String LEADS = "hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN','MANAGER','TL')";

    /** Admins and managers, no team leads. */
    public static final String MANAGERS_AND_ABOVE = "hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN','MANAGER')";

    /** Leads plus field-facing agent roles (FO). */
    public static final String LEADS_AND_FO = "hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN','MANAGER','TL','FO')";

    /** Every operational staff role in the app. */
    public static final String ALL_STAFF =
            "hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN','MANAGER','TL','FO','CALLER','TRACER')";

    /** All staff except TRACER -- SettlementOfferController's one narrower variant. */
    public static final String ALL_STAFF_NO_TRACER =
            "hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN','MANAGER','TL','FO','CALLER')";

    /** Admins, or anyone explicitly granted the ROLE_ASSIGN authority. */
    public static final String ADMINS_OR_ROLE_ASSIGN =
            "hasAnyRole('PLATFORM_ADMIN', 'ORG_ADMIN') or hasAuthority('ROLE_ASSIGN')";

    /** Admins plus the field-facing FO role, no MANAGER/TL -- AgentFieldController's/DocumentController's variant. */
    public static final String FO_AND_ADMINS = "hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN','FO')";
}
