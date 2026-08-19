package com.recoverpro.server.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** SYSTEM 18 TASK 18.2.a: platform-admin trial extension. {@code additionalDays} is added to
 *  whichever is later of "now" or the current trialEndsAt, so extending twice compounds rather
 *  than clobbering an earlier extension. */
@Data
public class ExtendTrialRequest {
    @Min(1)
    private int additionalDays;

    @NotBlank
    private String reason;
}
