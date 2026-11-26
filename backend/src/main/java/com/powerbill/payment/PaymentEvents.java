package com.powerbill.payment;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public final class PaymentEvents {
    private PaymentEvents() {}

    public record PaymentSucceeded(UUID paymentId, String paymentReference, UUID accountId, UUID userId,
            BigDecimal amount, String method, List<String> billNumbers) {}

    public record PaymentFailed(UUID paymentId, String paymentReference, UUID accountId, UUID userId,
            BigDecimal amount, String reason) {}
}
