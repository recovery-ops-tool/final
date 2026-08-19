package com.recoverpro.server.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import lombok.Data;

@Data
public class AttendanceCheckInRequest {
    // All three are optional -- a check-in without GPS (permission denied, indoor signal loss)
    // is still valid, AttendanceController defaults to an empty request when none is sent at
    // all. The constraints only fire when a value IS present, rejecting garbage coordinates
    // rather than silently persisting them.
    @DecimalMin("-90.0")
    @DecimalMax("90.0")
    private Double lat;

    @DecimalMin("-180.0")
    @DecimalMax("180.0")
    private Double lng;

    @DecimalMin("0.0")
    private Double accuracy;
}
