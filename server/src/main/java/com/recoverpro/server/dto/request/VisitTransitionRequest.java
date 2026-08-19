package com.recoverpro.server.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VisitTransitionRequest {

    // Optional -- VisitSessionController defaults to an empty request when the body is omitted
    // entirely. Constraints only fire when a value IS present.
    @DecimalMin("-90.0")
    @DecimalMax("90.0")
    private Double lat;

    @DecimalMin("-180.0")
    @DecimalMax("180.0")
    private Double lng;
}
