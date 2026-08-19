package com.recoverpro.server.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** SYSTEM 18 TASK 18.4: reason is required and lands in the audit trail -- an erasure is
 *  irreversible, so the record of why it happened matters as much as the record that it did. */
@Data
public class EraseUserRequest {
    @NotBlank
    private String reason;
}
