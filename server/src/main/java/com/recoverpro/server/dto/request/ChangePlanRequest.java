package com.recoverpro.server.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Shared by both PlatformSubscriptionController and SubscriptionController's changePlan
 *  endpoints -- same {@code {"plan": "..."}} shape in both. Kept as a raw String rather than a
 *  {@code Plan}-typed field so each controller's existing {@code Plan.valueOf(...)} try/catch
 *  keeps producing its specific "Unknown plan: X" message instead of a generic deserialization
 *  error. */
@Data
public class ChangePlanRequest {
    @NotBlank
    private String plan;
}
