package com.recoverpro.server.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class GrantCompRequest {
    @NotBlank
    private String plan;

    // Required so the next admin who finds this grant can tell why it exists.
    @NotBlank
    @Size(max = 2000)
    private String reason;

    // ISO-8601 instant, optional -- omit for an open-ended grant. Kept as a raw String rather
    // than an Instant field so the existing DateTimeParseException handling can keep producing
    // its specific "until must be an ISO-8601 instant, e.g. ..." message instead of the generic
    // HttpMessageNotReadableException one a mistyped Instant field would trigger.
    private String until;
}
