package com.powerbill.analytics;

import java.math.BigDecimal;
import java.util.UUID;

public final class AnalyticsEvents {
    private AnalyticsEvents() {}

    /**
     * The bill-shock forecast projects the current cycle above the customer's budget ({@code BUDGET}) or into a
     * higher tariff slab within a few days ({@code SLAB}). {@code budgetInr} is null when the customer set no budget.
     */
    public record BudgetAlert(UUID userId, UUID accountId, UUID meterId, String meterSerial, String cycle,
            BigDecimal projectedBillInr, BigDecimal budgetInr, String reason, String kind) {}
}
