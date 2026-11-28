package com.powerbill.billing;

import java.math.BigDecimal;
import java.util.List;

/** Portfolio-level receivable and revenue figures for the executive dashboard. */
public interface BillingStatistics {

    List<MonthlyRevenue> revenueByMonth(int months);

    List<AgingBucket> aging();

    List<SlabBand> slabDistribution(int months);

    record MonthlyRevenue(String month, int bills, BigDecimal billedInr, BigDecimal collectedInr,
            BigDecimal billedKwh) {}

    record AgingBucket(String bucket, int bills, BigDecimal outstandingInr) {}

    record SlabBand(String band, int bills) {}

    /**
     * Billing per meter for bills of the months {@code from} to {@code to} inclusive, plus overdue principal on any
     * open bill past its due date. Amounts exclude late fees.
     */
    List<MeterBilling> billingByMeter(java.time.YearMonth from, java.time.YearMonth to);

    record MeterBilling(java.util.UUID meterId, BigDecimal billedKwh, BigDecimal billedInr, BigDecimal collectedInr,
            BigDecimal overdueInr) {}
}
