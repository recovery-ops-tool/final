package com.recoverpro.server.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateOrganizationRequest {

    @NotBlank
    @Size(max = 255)
    private String name;

    /** SYSTEM 08 TASK 8.3: org-admin MFA-required toggle. Null means "leave unchanged" -- callers
     *  updating just the name don't need to resend the current policy value. */
    private Boolean mfaRequired;
}
