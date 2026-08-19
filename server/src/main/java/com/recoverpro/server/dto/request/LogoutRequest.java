package com.recoverpro.server.dto.request;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LogoutRequest {
    // Optional -- AuthController.logout() revokes just the access token when omitted. Bounded
    // rather than left unconstrained since it flows into a DB lookup.
    @Size(max = 2000)
    private String refreshToken;
}
