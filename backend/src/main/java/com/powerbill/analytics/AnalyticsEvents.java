package com.powerbill.analytics;

import java.math.BigDecimal;
import java.util.UUID;

public final class AnalyticsEvents {
    private AnalyticsEvents() {}

    /** The bill-shock forecast projects the current cycle above the customer's budget or into a higher slab. */
    public record BudgetAlert(UUID userId, UUID accountId, UUID meterId, String meterSerial, String cycle,
            BigDecimal projectedBillInr, BigDecimal budgetInr, String reason) {}
}
