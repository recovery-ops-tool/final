package com.recoverpro.server.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RefundInvoiceRequest {
    @NotNull
    private Long amountMinorUnits;

    // Optional -- PlatformSubscriptionController.refundInvoice() already passes a null reason
    // straight through to RefundService unconditionally; kept that way rather than newly
    // requiring it.
    @Size(max = 2000)
    private String reason;
}
