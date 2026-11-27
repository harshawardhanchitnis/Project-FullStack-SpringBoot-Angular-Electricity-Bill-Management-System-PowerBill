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
import java.util.Locale;
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
import com.powerbill.support.SupportEvents;
import com.powerbill.tariff.TariffPlan;
import com.powerbill.tariff.TariffPricing;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;

@Service
class AnalyticsService {
    private static final String EXECUTIVE = "executive";
    private static final String AREA_GENERATION = "consumer:area";
    private static final java.time.ZoneId INDIA = java.time.ZoneId.of("Asia/Kolkata");
    static final int MIN_PEERS = 4;
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
        // The key embeds the account's and the area-wide generations, so an event invalidates it immediately.
        String key = "consumer:" + point.meterId() + ":" + today + ":" + cache.generation(accountGeneration(account.id()))
                + ":" + cache.generation(AREA_GENERATION);
        return cache.get(key, Duration.ofSeconds(60), ConsumerOverview.class, () -> computeOverview(account, point, today));
    }

    private static String accountGeneration(UUID accountId) {
        return "consumer:" + accountId;
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

        TariffPlan plan = pricing.applicablePlan(point.tariffPlanId(), point.connectionType(), cycleStart);
        // Slabs apply to net billable energy: net-metered meters offset imports with exports and carried credit.
        Slab slab = slab(plan, netBillable(point, cycleKwh, cycleExport,
                point.solarNetMetering() ? billing.openingCredit(point.meterId(), cycle) : ZERO));
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
                        .filter(item -> item.status().equals("OPEN") || item.status().equals("INVESTIGATING")).count(),
                peer(point, today));
    }

    /**
     * Compares the meter's last 30 days with meters of the same connection type and load band. Only aggregates
     * leave this method, and nothing is returned when fewer than {@value #MIN_PEERS} other meters are in the group.
     */
    PeerBenchmark peer(ServicePoint point, LocalDate today) {
        var totals = usage.totalsByMeter(today.minusDays(30), today);
        var own = totals.get(point.meterId());
        if (own == null || own.importKwh().signum() == 0) {
            return null;
        }
        String band = loadBand(point.sanctionedLoadKw());
        var peers = accounts.billableServicePoints().stream()
                .filter(other -> !other.meterId().equals(point.meterId())
                        && other.connectionType().equals(point.connectionType())
                        && loadBand(other.sanctionedLoadKw()).equals(band))
                .map(other -> totals.get(other.meterId()))
                .filter(item -> item != null && item.importKwh().signum() > 0)
                .map(MeterUsage.Totals::importKwh).sorted().toList();
        if (peers.size() < MIN_PEERS) {
            return null;
        }
        BigDecimal median = percentile(peers, 50);
        long heavier = peers.stream().filter(kwh -> kwh.compareTo(own.importKwh()) > 0).count();
        BigDecimal difference = own.importKwh().subtract(median).multiply(BigDecimal.valueOf(100))
                .divide(median, 1, RoundingMode.HALF_UP);
        String type = point.connectionType().charAt(0) + point.connectionType().substring(1).toLowerCase(Locale.ROOT);
        return new PeerBenchmark(type + " · " + band, peers.size(), scale(own.importKwh()), scale(median),
                scale(percentile(peers, 25)), scale(percentile(peers, 75)), difference,
                (int) Math.round(heavier * 100.0 / peers.size()));
    }

    static String loadBand(BigDecimal kw) {
        if (kw.compareTo(BigDecimal.valueOf(5)) <= 0) {
            return "up to 5 kW";
        }
        return kw.compareTo(BigDecimal.TEN) <= 0 ? "5–10 kW" : "over 10 kW";
    }

    /** Linear-interpolated percentile of a sorted list. */
    static BigDecimal percentile(List<BigDecimal> sorted, int pct) {
        double rank = (sorted.size() - 1) * pct / 100.0;
        int low = (int) Math.floor(rank);
        int high = (int) Math.ceil(rank);
        BigDecimal fraction = BigDecimal.valueOf(rank - low);
        return sorted.get(low).add(sorted.get(high).subtract(sorted.get(low)).multiply(fraction));
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
                    support.slaStatistics(), (int) consumers, points.size(), regions(points, LocalDate.now(INDIA)));
        });
    }

    /**
     * Per region and city over the last three complete months: net metered energy against billed energy (a proxy
     * for commercial and data-quality losses), collection efficiency, overdue principal and open anomalies.
     */
    List<Region> regions(List<ServicePoint> points, LocalDate today) {
        YearMonth last = YearMonth.from(today).minusMonths(1);
        YearMonth first = last.minusMonths(2);
        var energy = usage.totalsByMeter(first.atDay(1), last.plusMonths(1).atDay(1));
        var bills = new java.util.HashMap<UUID, BillingStatistics.MeterBilling>();
        statistics.billingByMeter(first, last).forEach(item -> bills.put(item.meterId(), item));
        var open = anomalies.openByMeter();
        var groups = new TreeMap<String, List<ServicePoint>>();
        points.forEach(point -> groups.computeIfAbsent(point.regionZone() + "|" + point.city(),
                key -> new ArrayList<>()).add(point));
        var regions = new ArrayList<Region>();
        groups.forEach((key, members) -> {
            BigDecimal metered = ZERO;
            BigDecimal billedKwh = ZERO;
            BigDecimal billedInr = ZERO;
            BigDecimal collectedInr = ZERO;
            BigDecimal overdue = ZERO;
            int openAnomalies = 0;
            for (ServicePoint point : members) {
                var totals = energy.get(point.meterId());
                if (totals != null) {
                    metered = metered.add(totals.importKwh().subtract(totals.exportKwh()).max(ZERO));
                }
                var billing = bills.get(point.meterId());
                if (billing != null) {
                    billedKwh = billedKwh.add(billing.billedKwh());
                    billedInr = billedInr.add(billing.billedInr());
                    collectedInr = collectedInr.add(billing.collectedInr());
                    overdue = overdue.add(billing.overdueInr());
                }
                openAnomalies += open.getOrDefault(point.meterId(), 0);
            }
            BigDecimal loss = metered.signum() == 0 ? null : metered.subtract(billedKwh).max(ZERO)
                    .multiply(BigDecimal.valueOf(100)).divide(metered, 1, RoundingMode.HALF_UP);
            BigDecimal efficiency = billedInr.signum() == 0 ? null : collectedInr.multiply(BigDecimal.valueOf(100))
                    .divide(billedInr, 1, RoundingMode.HALF_UP);
            regions.add(new Region(members.getFirst().regionZone(), members.getFirst().city(), members.size(),
                    scale(metered), scale(billedKwh), loss, scale(billedInr), scale(collectedInr), efficiency,
                    scale(overdue), openAnomalies));
        });
        return regions;
    }

    @ApplicationModuleListener
    void on(BillingEvents.BillIssued event) {
        cache.evict(EXECUTIVE);
        cache.bump(accountGeneration(event.accountId()));
    }

    @ApplicationModuleListener
    void on(BillingEvents.BillAdjusted event) {
        cache.evict(EXECUTIVE);
        cache.bump(accountGeneration(event.accountId()));
    }

    @ApplicationModuleListener
    void on(BillingEvents.BillOverdue event) {
        cache.evict(EXECUTIVE);
        cache.bump(accountGeneration(event.accountId()));
    }

    @ApplicationModuleListener
    void on(PaymentEvents.PaymentSucceeded event) {
        cache.evict(EXECUTIVE);
        cache.bump(accountGeneration(event.accountId()));
    }

    @ApplicationModuleListener
    void on(SupportEvents.ComplaintUpdated event) {
        cache.evict(EXECUTIVE);
        if (event.accountId() != null) {
            cache.bump(accountGeneration(event.accountId()));
        }
    }

    /** Outages cover whole pincodes, so they invalidate every consumer overview at once. */
    @ApplicationModuleListener
    void on(SupportEvents.OutageChanged event) {
        cache.bump(AREA_GENERATION);
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

    /** Energy the tariff charges for: imports minus exports and opening credit, never below zero, when net-metered. */
    static BigDecimal netBillable(ServicePoint point, BigDecimal importKwh, BigDecimal exportKwh, BigDecimal openingCredit) {
        return point.solarNetMetering() ? importKwh.subtract(exportKwh).subtract(openingCredit).max(ZERO) : importKwh;
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
            long openAnomalies, PeerBenchmark peers) {}
    /** Anonymous comparison with similar meters; {@code moreEfficientThanPct} is the share of peers using more. */
    record PeerBenchmark(String group, int peers, BigDecimal yourKwh, BigDecimal medianKwh, BigDecimal p25Kwh,
            BigDecimal p75Kwh, BigDecimal differencePct, int moreEfficientThanPct) {}
    record Region(String regionZone, String city, int meters, BigDecimal meteredKwh, BigDecimal billedKwh,
            BigDecimal lossProxyPct, BigDecimal billedInr, BigDecimal collectedInr, BigDecimal collectionEfficiencyPct,
            BigDecimal overdueInr, int openAnomalies) {}
    record Executive(List<BillingStatistics.MonthlyRevenue> revenue, BigDecimal billedLast12Inr,
            BigDecimal collectedLast12Inr, BigDecimal collectionEfficiencyPct, BigDecimal outstandingInr,
            List<BillingStatistics.AgingBucket> aging, List<BillingStatistics.SlabBand> slabDistribution,
            AnomalyRegistry.Statistics anomalies, SupportDirectory.SlaStatistics sla, int consumers, int meters,
            List<Region> regions) {}
}
