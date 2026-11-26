package com.powerbill.tariff.internal;

import static java.math.BigDecimal.ZERO;
import static java.math.RoundingMode.HALF_UP;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import com.powerbill.tariff.TariffPlan;
import com.powerbill.tariff.TariffPricing.Interval;
import com.powerbill.tariff.TariffPricing.Line;
import com.powerbill.tariff.TariffPricing.Quote;
import org.springframework.stereotype.Component;

@Component
class TariffCalculator {

    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    Quote price(TariffPlan plan, BigDecimal load, boolean netMetering, BigDecimal previousCredit,
            List<Interval> usage) {
        if (load.signum() < 0 || previousCredit.signum() < 0 || usage.stream().anyMatch(interval ->
                interval.importKwh().signum() < 0 || interval.exportKwh().signum() < 0)) {
            throw new IllegalArgumentException("Load, credits, and interval energy must be nonnegative.");
        }
        var intervals = usage.stream().sorted(Comparator.comparing(Interval::start)).toList();
        BigDecimal imports = intervals.stream().map(Interval::importKwh).reduce(ZERO, BigDecimal::add);
        BigDecimal exports = intervals.stream().map(Interval::exportKwh).reduce(ZERO, BigDecimal::add);
        BigDecimal availableCredit = netMetering ? previousCredit.add(exports) : ZERO;
        BigDecimal billable = imports.subtract(availableCredit).max(ZERO);
        BigDecimal surplus = availableCredit.subtract(imports).max(ZERO);
        BigDecimal carry = netMetering && plan.solarPolicy() == TariffPlan.SolarPolicy.CARRY_FORWARD ? surplus : ZERO;
        BigDecimal cash = netMetering && plan.solarPolicy() == TariffPlan.SolarPolicy.CASH_CREDIT
                ? money(surplus.multiply(plan.solarExportRatePerKwh())) : money(ZERO);

        var lines = new ArrayList<Line>();
        BigDecimal tod = allocateEnergy(plan, intervals, imports, billable, lines);
        lines.add(line("TOD", "Time-of-day adjustment", null, null, tod));
        lines.add(line("FIXED", "Fixed charge per kW", load, plan.fixedChargePerKw(),
                load.multiply(plan.fixedChargePerKw())));
        lines.add(line("FAC", "Fuel adjustment charge", billable, plan.facPerKwh(),
                billable.multiply(plan.facPerKwh())));
        BigDecimal taxBase = sum(lines).max(ZERO);
        lines.add(line("DUTY", "Electricity duty", taxBase, plan.electricityDutyPct(),
                taxBase.multiply(plan.electricityDutyPct()).movePointLeft(2)));
        lines.add(line("GST", "GST", taxBase, plan.gstPct(), taxBase.multiply(plan.gstPct()).movePointLeft(2)));
        BigDecimal beforeSettlement = sum(lines);
        BigDecimal appliedCash = cash.min(beforeSettlement.max(ZERO));
        if (appliedCash.signum() > 0) {
            lines.add(line("SOLAR_CREDIT", "Rooftop solar export credit", surplus,
                    plan.solarExportRatePerKwh(), appliedCash.negate()));
        }
        return new Quote(imports, exports, billable, carry, cash.subtract(appliedCash),
                sum(lines), List.copyOf(lines));
    }

    private BigDecimal allocateEnergy(TariffPlan plan, List<Interval> intervals, BigDecimal imports,
            BigDecimal billable, List<Line> lines) {
        if (billable.signum() == 0) {
            return ZERO;
        }
        BigDecimal[] usageBySlab = new BigDecimal[plan.slabs().size()];
        Arrays.fill(usageBySlab, ZERO);
        BigDecimal total = ZERO;
        BigDecimal grossSoFar = ZERO;
        BigDecimal tod = ZERO;
        int band = 0;
        for (Interval interval : intervals) {
            grossSoFar = grossSoFar.add(interval.importKwh());
            // Allocate offsets proportionally while preserving the exact total at the last interval.
            BigDecimal target = grossSoFar.multiply(billable).divide(imports, 12, RoundingMode.DOWN);
            if (grossSoFar.compareTo(imports) == 0) {
                target = billable;
            }
            BigDecimal adjustment = window(plan, interval).rateMultiplier().subtract(BigDecimal.ONE);
            while (total.compareTo(target) < 0) {
                TariffPlan.Slab slab = plan.slabs().get(band);
                BigDecimal end = slab.untilKwh() == null ? target : target.min(slab.untilKwh());
                BigDecimal used = end.subtract(total);
                usageBySlab[band] = usageBySlab[band].add(used);
                tod = tod.add(used.multiply(slab.ratePerKwh()).multiply(adjustment));
                total = end;
                if (slab.untilKwh() != null && total.compareTo(slab.untilKwh()) == 0) {
                    band++;
                }
            }
        }
        for (int i = 0; i < usageBySlab.length; i++) {
            if (usageBySlab[i].signum() > 0) {
                BigDecimal rate = plan.slabs().get(i).ratePerKwh();
                lines.add(line("SLAB", "Energy slab " + (i + 1), usageBySlab[i], rate, usageBySlab[i].multiply(rate)));
            }
        }
        return tod;
    }

    private TariffPlan.TimeWindow window(TariffPlan plan, Interval interval) {
        int hour = interval.start().atZone(INDIA).getHour();
        return plan.windows().stream().filter(item -> hour >= item.startHour() && hour < item.endHour())
                .findFirst().orElseThrow();
    }

    private static BigDecimal sum(List<Line> lines) {
        return lines.stream().map(Line::amountInr).reduce(money(ZERO), BigDecimal::add);
    }

    private static Line line(String category, String description, BigDecimal units, BigDecimal rate, BigDecimal amount) {
        return new Line(category, description, units, rate, money(amount));
    }

    private static BigDecimal money(BigDecimal amount) {
        return amount.setScale(2, HALF_UP);
    }
}
