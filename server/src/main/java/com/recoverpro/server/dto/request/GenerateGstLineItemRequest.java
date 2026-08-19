package com.recoverpro.server.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class GenerateGstLineItemRequest {
    @NotBlank
    @Size(max = 500)
    private String description;

    @NotNull
    private Long taxableAmountMinorUnits;
}
