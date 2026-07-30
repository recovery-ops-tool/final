package com.recoverpro.server.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

/**
 * The `data` JSON part of POST /api/v1/lucien/sessions/{id}/confirm-visit (multipart, with
 * image1/image2 file parts alongside it). Confirms or cancels the pending submit_visit_interview
 * action, carrying the GPS fix captured at the moment the FO taps Confirm — freshest possible,
 * matching the anti-fraud guarantee VisitSubmitPage's manual flow already has.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConfirmVisitActionRequest {

    @NotBlank(message = "actionId is required")
    private String actionId;

    private boolean confirmed;

    private Double latitude;
    private Double longitude;
    private Double gpsAccuracy;
    private Boolean mockLocationDetected;
}
