package com.recoverpro.server.dto.response;

import lombok.Builder;
import lombok.Data;

/**
 * TASK 28.3: activation checklist for a fresh org (28.3.a's own suggested steps). Computed live
 * against each step's real backing table on every read, not a maintained set of boolean columns
 * that could drift from reality -- same reasoning as {@code EntitlementServiceImpl}'s class
 * javadoc (a live check is correct by construction; a separately-maintained flag isn't).
 */
@Data
@Builder
public class OnboardingChecklistResponse {
    private boolean teamInvited;
    private boolean columnSchemaConfigured;
    private boolean firstFileImported;
    private boolean firstAssignmentRun;
    private boolean allComplete;

    /** TASK 28.3.d: minutes between org creation and the first completed import. Null until
     *  {@link #firstFileImported} is true. A plain numeric field rather than an ISO-8601 Duration
     *  -- simpler for any future frontend consumer to format without duration-parsing logic. */
    private Long timeToFirstImportMinutes;
}
