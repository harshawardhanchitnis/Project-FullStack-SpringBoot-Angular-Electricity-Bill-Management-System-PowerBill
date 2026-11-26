package com.powerbill.analytics.internal;

import static java.math.BigDecimal.ZERO;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import com.powerbill.billing.BillingEvents;
import com.powerbill.billing.BillingLedger;
import com.powerbill.billing.BillingStatistics;
import com.powerbill.consumer.AccountDirectory;
import com.powerbill.consumer.AccountDirectory.ServicePoint;
import com.powerbill.metering.AnomalyRegistry;
import com.powerbill.metering.MeterUsage;
import com.powerbill.payment.PaymentEvents;
import com.powerbill.support.SupportDirectory;
import com.powerbill.tariff.TariffPlan;
import com.powerbill.tariff.TariffPricing;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;

@Service
class AnalyticsService {
    private static final String EXECUTIVE = "executive";
    private final AccountDirectory accounts;
    private final MeterUsage usage;
    private final BillingLedger billing;
    private final BillingStatistics statistics;
    private final SupportDirectory support;
    private final AnomalyRegistry anomalies;
    private final TariffPricing pricing;
    private final JsonCache cache;

    AnalyticsService(AccountDirectory accounts, MeterUsage usage, BillingLedger billing, BillingStatistics statistics,
            SupportDirectory support, AnomalyRegistry anomalies, TariffPricing pricing, JsonCache cache) {
        this.accounts = accounts;
        this.usage = usage;
        this.billing = billing;
        this.statistics = statistics;
        this.support = support;
        this.anomalies = anomalies;
        this.pricing = pricing;
        this.cache = cache;
    }

    ConsumerOverview overview(AccountDirectory.Account account, ServicePoint point, LocalDate today) {
        return cache.get("consumer:" + point.meterId() + ":" + today, Duration.ofSeconds(60), ConsumerOverview.class,
                () -> computeOverview(account, point, today));
    }

    private ConsumerOverview computeOverview(AccountDirectory.Account account, ServicePoint point, LocalDate today) {
        YearMonth cycle = YearMonth.from(today);
        var daily = usage.dailyUsage(point.meterId(), cycle.minusMonths(11).atDay(1), today);
        var monthly = new TreeMap<String, BigDecimal[]>();
        for (var day : daily) {
            var totals = monthly.computeIfAbsent(YearMonth.from(day.day()).toString(), key -> new BigDecimal[] {ZERO, ZERO});
            totals[0] = totals[0].add(day.importKwh());
            totals[1] = totals[1].add(day.exportKwh());
        }
        var billed = new LinkedHashMap<String, BigDecimal>();
        billing.recentBills(account.id(), 24).stream().filter(bill -> bill.meterId().equals(point.meterId()))
                .forEach(bill -> billed.put(bill.period().toString(), bill.totalAmount()));
        var months = new ArrayList<MonthPoint>();
        monthly.forEach((month, totals) -> months.add(new MonthPoint(month, scale(totals[0]), scale(totals[1]),
                billed.get(month))));

        LocalDate cycleStart = cycle.atDay(1);
        int elapsed = today.getDayOfMonth() - 1;
        BigDecimal cycleKwh = sum(daily, cycleStart, today, false);
        BigDecimal cycleExport = sum(daily, cycleStart, today, true);
        LocalDate previousStart = cycle.minusMonths(1).atDay(1);
        BigDecimal previousSameDays = sum(daily, previousStart, previousStart.plusDays(elapsed), false);
        BigDecimal change = previousSameDays.signum() == 0 ? null
                : cycleKwh.subtract(previousSameDays).multiply(BigDecimal.valueOf(100))
                        .divide(previousSameDays, 1, RoundingMode.HALF_UP);

        TariffPlan plan = pricing.applicablePlan(point.connectionType(), cycleStart);
        Slab slab = slab(plan, cycleKwh);
        var shape = usage.hourlyProfile(point.meterId(), today.minusDays(30), today);
        var hours = new ArrayList<HourPoint>();
        var windows = new LinkedHashMap<String, BigDecimal>();
        for (var hour : shape) {
            String window = plan.windows().stream().filter(item -> item.startHour() <= hour.hour()
                    && item.endHour() > hour.hour()).findFirst().map(TariffPlan.TimeWindow::name).orElse("NORMAL");
            hours.add(new HourPoint(hour.hour(), scale(hour.importKwh()), scale(hour.exportKwh()), window));
            windows.merge(window, hour.importKwh(), BigDecimal::add);
        }
        windows.replaceAll((key, value) -> scale(value));
        var last30 = daily.stream().filter(day -> !day.day().isBefore(today.minusDays(30)))
                .map(day -> new DayPoint(day.day(), scale(day.importKwh()), scale(day.exportKwh()))).toList();
        var outages = support.activeOutages(accounts.pincodes(account.id()));
        return new ConsumerOverview(new MeterView(point.meterId(), point.meterSerial(), point.connectionType(),
                point.sanctionedLoadKw(), point.solarNetMetering(), point.solarCapacityKw(), point.premiseLabel(),
                plan.name()), new CycleView(cycle.toString(), elapsed, cycle.lengthOfMonth(), scale(cycleKwh),
                scale(cycleExport), scale(previousSameDays), change), slab, billing.receivables(account.id()),
                support.openComplaints(account.id()), outages, months, last30, hours, windows,
                account.monthlyBudgetInr(), anomalies.forMeter(point.meterId(), 3).stream()
                        .filter(item -> item.status().equals("OPEN") || item.status().equals("INVESTIGATING")).count());
    }

    Executive executive() {
        return cache.get(EXECUTIVE, Duration.ofSeconds(90), Executive.class, () -> {
            var revenue = statistics.revenueByMonth(12);
            BigDecimal billed = revenue.stream().map(BillingStatistics.MonthlyRevenue::billedInr).reduce(ZERO, BigDecimal::add);
            BigDecimal collected = revenue.stream().map(BillingStatistics.MonthlyRevenue::collectedInr)
                    .reduce(ZERO, BigDecimal::add);
            var aging = statistics.aging();
            BigDecimal outstanding = aging.stream().map(BillingStatistics.AgingBucket::outstandingInr)
                    .reduce(ZERO, BigDecimal::add);
            var points = accounts.billableServicePoints();
            long consumers = points.stream().map(point -> point.account().id()).distinct().count();
            return new Executive(revenue, scale(billed), scale(collected), billed.signum() == 0 ? null
                    : collected.multiply(BigDecimal.valueOf(100)).divide(billed, 1, RoundingMode.HALF_UP),
                    scale(outstanding), aging, statistics.slabDistribution(3), anomalies.statistics(),
                    support.slaStatistics(), (int) consumers, points.size());
        });
    }

    @ApplicationModuleListener
    void on(BillingEvents.BillIssued event) {
        cache.evict(EXECUTIVE);
    }

    @ApplicationModuleListener
    void on(PaymentEvents.PaymentSucceeded event) {
        cache.evict(EXECUTIVE);
    }

    static Slab slab(TariffPlan plan, BigDecimal kwh) {
        for (int index = 0; index < plan.slabs().size(); index++) {
            var slab = plan.slabs().get(index);
            if (slab.untilKwh() == null || kwh.compareTo(slab.untilKwh()) < 0) {
                BigDecimal next = index + 1 < plan.slabs().size() ? plan.slabs().get(index + 1).ratePerKwh() : null;
                return new Slab(index + 1, slab.fromKwh(), slab.untilKwh(), slab.ratePerKwh(), next,
                        slab.untilKwh() == null ? null : scale(slab.untilKwh().subtract(kwh)), scale(kwh),
                        plan.slabs().stream().map(item -> new SlabEdge(item.fromKwh(), item.untilKwh(), item.ratePerKwh()))
                                .toList());
            }
        }
        throw new IllegalStateException("Tariff has no open slab");
    }

    private static BigDecimal sum(List<MeterUsage.DailyUsage> days, LocalDate from, LocalDate to, boolean export) {
        return days.stream().filter(day -> !day.day().isBefore(from) && day.day().isBefore(to))
                .map(day -> export ? day.exportKwh() : day.importKwh()).reduce(ZERO, BigDecimal::add);
    }

    static BigDecimal scale(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    record MeterView(UUID id, String serial, String connectionType, BigDecimal sanctionedLoadKw,
            boolean solarNetMetering, BigDecimal solarCapacityKw, String premiseLabel, String tariffName) {}
    record CycleView(String month, int daysElapsed, int daysInCycle, BigDecimal importKwhToDate,
            BigDecimal exportKwhToDate, BigDecimal previousCycleSameDaysKwh, BigDecimal changePct) {}
    record SlabEdge(BigDecimal fromKwh, BigDecimal untilKwh, BigDecimal ratePerKwh) {}
    record Slab(int index, BigDecimal fromKwh, BigDecimal untilKwh, BigDecimal ratePerKwh, BigDecimal nextRatePerKwh,
            BigDecimal kwhToNextSlab, BigDecimal currentKwh, List<SlabEdge> slabs) {}
    record MonthPoint(String month, BigDecimal importKwh, BigDecimal exportKwh, BigDecimal billedInr) {}
    record DayPoint(LocalDate day, BigDecimal importKwh, BigDecimal exportKwh) {}
    record HourPoint(int hour, BigDecimal importKwh, BigDecimal exportKwh, String window) {}
    record ConsumerOverview(MeterView meter, CycleView cycle, Slab slab, BillingLedger.Receivables receivables,
            int openComplaints, List<SupportDirectory.Outage> outages, List<MonthPoint> monthly, List<DayPoint> daily,
            List<HourPoint> hourlyProfile, Map<String, BigDecimal> todShareKwh, BigDecimal monthlyBudgetInr,
            long openAnomalies) {}
    record Executive(List<BillingStatistics.MonthlyRevenue> revenue, BigDecimal billedLast12Inr,
            BigDecimal collectedLast12Inr, BigDecimal collectionEfficiencyPct, BigDecimal outstandingInr,
            List<BillingStatistics.AgingBucket> aging, List<BillingStatistics.SlabBand> slabDistribution,
            AnomalyRegistry.Statistics anomalies, SupportDirectory.SlaStatistics sla, int consumers, int meters) {}
}
