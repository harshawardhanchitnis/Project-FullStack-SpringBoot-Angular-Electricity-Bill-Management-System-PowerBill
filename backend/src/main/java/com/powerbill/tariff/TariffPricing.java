package com.powerbill.tariff;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public interface TariffPricing {

    TariffPlan applicablePlan(String connectionType, LocalDate date);

    Quote price(TariffPlan plan, BigDecimal sanctionedLoadKw, boolean netMetering,
            BigDecimal previousCreditKwh, List<Interval> intervals);

    record Interval(Instant start, BigDecimal importKwh, BigDecimal exportKwh) {}
    record Line(String category, String description, BigDecimal units, BigDecimal rate, BigDecimal amountInr) {}
    record Quote(BigDecimal importedKwh, BigDecimal exportedKwh, BigDecimal netBillableKwh,
            BigDecimal carryForwardKwh, BigDecimal balancedCreditInr, BigDecimal totalInr, List<Line> lineItems) {}
}
