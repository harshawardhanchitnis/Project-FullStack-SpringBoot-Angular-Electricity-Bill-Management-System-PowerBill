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
}
