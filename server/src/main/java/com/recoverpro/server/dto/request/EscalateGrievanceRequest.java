package com.recoverpro.server.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class EscalateGrievanceRequest {

    /** Optional context, logged (not persisted to a new column -- see design spec). */
    @Size(max = 2000)
    private String remarks;
}
