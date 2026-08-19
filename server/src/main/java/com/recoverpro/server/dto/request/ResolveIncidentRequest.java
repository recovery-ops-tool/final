package com.recoverpro.server.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ResolveIncidentRequest {
    // Optional -- AgentFieldServiceImpl.resolveIncident() already accepts null notes
    // unconditionally (persisted as-is); kept that way here rather than newly requiring it,
    // which would be a behavior change beyond this task's scope.
    @Size(max = 2000)
    private String notes;
}
