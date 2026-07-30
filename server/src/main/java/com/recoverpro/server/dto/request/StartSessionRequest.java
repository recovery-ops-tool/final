package com.recoverpro.server.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StartSessionRequest {

    @NotNull
    private UUID agentId;

    @NotBlank
    private String agentFirstName;

    /** Optional — when set, starts a visit-interview session bound to this case instead of
     * the general assistant. Ownership/org-membership is verified server-side. */
    private UUID allocationId;
}
