package com.recoverpro.server.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ExecuteErasureRequest {

    @Size(max = 2000)
    private String complianceNotes;
}
