package com.recoverpro.server.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.util.UUID;

@Data
public class RemoveDispatchCaseRequest {
    @NotNull
    private UUID agentId;

    @NotNull
    private LocalDate date;

    @NotNull
    private UUID allocationId;
}
