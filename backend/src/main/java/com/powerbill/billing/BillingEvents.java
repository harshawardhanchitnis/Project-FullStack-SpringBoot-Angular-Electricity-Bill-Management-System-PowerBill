package com.powerbill.billing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public final class BillingEvents {
    private BillingEvents() {}

    public record BillIssued(UUID billId, String billNumber, UUID accountId, UUID userId, String billingEmail,
            String period, BigDecimal totalAmount, LocalDate dueDate) {}

    public record BillOverdue(UUID billId, String billNumber, UUID accountId, UUID userId, LocalDate dueDate) {}

    public record BillSettled(UUID billId, String billNumber, UUID accountId, UUID userId, BigDecimal amountPaid) {}

    /** A credit or debit note changed what is owed on a bill. */
    public record BillAdjusted(UUID billId, String billNumber, UUID accountId, String noteNumber, String noteType,
            BigDecimal amount) {}
}
