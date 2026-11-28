package com.powerbill.billing;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Receivables owned by the billing module. Payments settle bills only through this contract so that the
 * invoice, its late fee and the double-entry journals stay consistent in one database transaction.
 */
public interface BillingLedger {

    /** Locks the bills in a stable order and prices what is due on {@code asOf}, including any accrued late fee. */
    List<Payable> lockPayables(UUID accountId, List<UUID> billIds, LocalDate asOf);

    /**
     * Validates every allocation first and then settles them together. Throws {@link SettlementRejected}
     * before changing anything when any bill can no longer accept the allocation.
     */
    void settle(UUID accountId, UUID paymentId, String paymentReference, List<Allocation> allocations,
            Instant settledAt);

    Optional<BillHeader> header(UUID billId);

    List<BillHeader> recentBills(UUID accountId, int limit);

    Receivables receivables(UUID accountId);

    List<LineItem> lineItems(UUID billId);

    /** Solar kWh credit carried into a billing cycle: the closing credit on the meter's previous month's bill. */
    BigDecimal openingCredit(UUID meterId, YearMonth cycle);

    record LineItem(String category, String description, BigDecimal units, BigDecimal rate, BigDecimal amount) {}

    record Payable(UUID billId, String billNumber, String status, BigDecimal principalDue, BigDecimal lateFeeDue,
            LocalDate dueDate) {
        public BigDecimal totalDue() {
            return principalDue.add(lateFeeDue);
        }
    }

    record Allocation(UUID billId, BigDecimal principal, BigDecimal lateFee) {}

    record BillHeader(UUID id, String billNumber, UUID accountId, UUID meterId, YearMonth period,
            BigDecimal importKwh, BigDecimal exportKwh, BigDecimal totalAmount, BigDecimal outstanding,
            String status, LocalDate dueDate) {}

    record Receivables(int openBills, int overdueBills, BigDecimal principalDue, BigDecimal lateFeesDue,
            LocalDate nextDueDate) {
        public BigDecimal totalDue() {
            return principalDue.add(lateFeesDue);
        }
    }

    final class SettlementRejected extends RuntimeException {
        public SettlementRejected(String message) {
            super(message);
        }
    }
}
