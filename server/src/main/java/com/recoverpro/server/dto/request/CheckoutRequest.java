package com.recoverpro.server.dto.request;

import lombok.Data;

@Data
public class CheckoutRequest {
    // Optional -- SubscriptionController.checkout() already defaults to STARTER when omitted;
    // the field initializer preserves that (Lombok's generated no-args constructor runs it
    // before Jackson overwrites whatever key IS present in the request body).
    private String plan = "STARTER";
}
