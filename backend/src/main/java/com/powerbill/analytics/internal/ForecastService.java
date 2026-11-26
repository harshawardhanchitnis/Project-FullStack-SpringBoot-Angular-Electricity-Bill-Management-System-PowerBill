package com.powerbill.analytics.internal;

import static java.math.BigDecimal.ZERO;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.powerbill.consumer.AccountDirectory.ServicePoint;
import com.powerbill.metering.MeterUsage;
import com.powerbill.tariff.TariffPlan;
import com.powerbill.tariff.TariffPricing;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * Projects the current billing cycle. The ML service predicts daily consumption bands; this service turns each
 * band into hourly intervals using the meter's own load shape and prices them through the real tariff engine.
 */
@Service
class ForecastService {
    static final java.time.ZoneId INDIA = MeterUsage.BILLING_ZONE;
    private final MeterUsage usage;
    private final TariffPricing pricing;
    private final MlClient ml;
    private final JsonCache cache;
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<Forecast>>
            inFlight = new java.util.concurrent.ConcurrentHashMap<>();

    ForecastService(MeterUsage usage, TariffPricing pricing, MlClient ml, JsonCache cache) {
        this.usage = usage;
        this.pricing = pricing;
        this.ml = ml;
        this.cache = cache;
    }

    /** Forecasts are cached per meter and day for 20 minutes; dashboards, the copilot and alerts share them. */
    Forecast forecast(ServicePoint point, LocalDate today, String correlationId) {
        String key = "forecast:" + point.meterId() + ":" + today;
        var cached = cache.read(key, Forecast.class);
        if (cached.isPresent()) {
            return cached.get();
        }
        // Single flight: concurrent requests for the same meter and day share one model call.
        var mine = new java.util.concurrent.CompletableFuture<Forecast>();
        var running = inFlight.putIfAbsent(key, mine);
        if (running != null) {
            return running.join();
        }
        try {
            Forecast forecast = compute(point, today, correlationId);
            // Model forecasts are reused for 20 minutes; a fallback only briefly, so the model replaces it on recovery.
            cache.put(key, forecast, forecast.source().equals("ml")
                    ? java.time.Duration.ofMinutes(20) : java.time.Duration.ofMinutes(1));
            mine.complete(forecast);
            return forecast;
        } catch (RuntimeException error) {
            mine.completeExceptionally(error);
            throw error;
        } finally {
            inFlight.remove(key, mine);
        }
    }

    private Forecast compute(ServicePoint point, LocalDate today, String correlationId) {
        YearMonth cycle = YearMonth.from(today);
        LocalDate start = cycle.atDay(1);
        LocalDate end = cycle.plusMonths(1).atDay(1);
        int remainingDays = (int) ChronoUnit.DAYS.between(today, end);
        var history = usage.dailyUsage(point.meterId(), today.minusDays(400), today);
        var cycleDays = history.stream().filter(day -> !day.day().isBefore(start)).toList();
        BigDecimal toDateKwh = cycleDays.stream().map(MeterUsage.DailyUsage::importKwh).reduce(ZERO, BigDecimal::add);
        BigDecimal toDateExport = cycleDays.stream().map(MeterUsage.DailyUsage::exportKwh).reduce(ZERO, BigDecimal::add);

        Bands bands = remoteBands(point, history, today, end, correlationId);
        String source = "ml";
        if (bands == null) {
            bands = runRate(history, today, remainingDays);
            source = "fallback";
        }

        TariffPlan plan = pricing.applicablePlan(point.connectionType(), start);
        var actual = usage.intervalReadings(point.meterId(), start.atStartOfDay(INDIA).toInstant(),
                today.atStartOfDay(INDIA).toInstant()).stream().filter(reading -> !reading.quality().equals("FLAGGED"))
                .map(reading -> new TariffPricing.Interval(reading.start(), reading.importKwh(), reading.exportKwh()))
                .toList();
        var shape = usage.hourlyProfile(point.meterId(), today.minusDays(28), today);
        BigDecimal dailyExport = average(history.subList(Math.max(0, history.size() - 14), history.size()), true);
        var quotes = new LinkedHashMap<String, TariffPricing.Quote>();
        for (var band : List.of("p10", "p50", "p90")) {
            var intervals = new ArrayList<>(actual);
            for (var day : bands.daily()) {
                intervals.addAll(synthesize(day.day(), day.value(band), point.solarNetMetering() ? dailyExport : ZERO,
                        shape));
            }
            quotes.put(band, pricing.price(plan, point.sanctionedLoadKw(), point.solarNetMetering(), ZERO, intervals));
        }

        BigDecimal p50Kwh = toDateKwh.add(bands.total("p50"));
        Integer daysToNextSlab = null;
        BigDecimal nextSlabAt = null;
        for (var slab : plan.slabs()) {
            if (slab.untilKwh() != null && slab.untilKwh().compareTo(toDateKwh) > 0) {
                nextSlabAt = slab.untilKwh();
                break;
            }
        }
        if (nextSlabAt != null) {
            BigDecimal running = toDateKwh;
            for (int index = 0; index < bands.daily().size(); index++) {
                running = running.add(bands.daily().get(index).p50());
                if (running.compareTo(nextSlabAt) >= 0) {
                    daysToNextSlab = index;
                    break;
                }
            }
        }
        BigDecimal lastMonth = history.stream().filter(day -> YearMonth.from(day.day()).equals(cycle.minusMonths(1)))
                .map(MeterUsage.DailyUsage::importKwh).reduce(ZERO, BigDecimal::add);
        return new Forecast(point.meterId(), point.meterSerial(), cycle.toString(), today, (int) ChronoUnit.DAYS.between(
                start, today), cycle.lengthOfMonth(), scale(toDateKwh), scale(toDateExport),
                new Band(scale(toDateKwh.add(bands.total("p10"))), scale(p50Kwh), scale(toDateKwh.add(bands.total("p90")))),
                new Band(quotes.get("p10").totalInr(), quotes.get("p50").totalInr(), quotes.get("p90").totalInr()),
                scale(lastMonth), nextSlabAt, daysToNextSlab, bands.daily(), bands.model(), source, bands.trendPct(),
                quotes.get("p50").lineItems());
    }

    private Bands remoteBands(ServicePoint point, List<MeterUsage.DailyUsage> history, LocalDate today,
            LocalDate end, String correlationId) {
        if (history.size() < 14) {
            return null;
        }
        var days = history.stream().map(day -> Map.of("day", day.day().toString(), "importKwh", day.importKwh(),
                "exportKwh", day.exportKwh())).toList();
        Map<String, Object> request = Map.of("meterId", point.meterId().toString(), "asOf", today.toString(),
                "cycleEnd", end.toString(), "history", days);
        return ml.post("/ml/v1/forecast", request, correlationId).map(ForecastService::bands).orElse(null);
    }

    private static Bands bands(JsonNode response) {
        var daily = new ArrayList<Day>();
        for (JsonNode day : response.path("daily")) {
            daily.add(new Day(LocalDate.parse(day.path("day").asText()), positive(day.path("p10")),
                    positive(day.path("p50")), positive(day.path("p90"))));
        }
        return new Bands(daily, response.path("model").asText("ml"),
                response.hasNonNull("trendPct") ? response.path("trendPct").decimalValue() : null);
    }

    private static Bands runRate(List<MeterUsage.DailyUsage> history, LocalDate today, int remaining) {
        var recent = history.subList(Math.max(0, history.size() - 14), history.size());
        BigDecimal mean = average(recent, false);
        double variance = recent.stream().mapToDouble(day -> Math.pow(day.importKwh().doubleValue() - mean.doubleValue(), 2))
                .average().orElse(0);
        BigDecimal spread = BigDecimal.valueOf(1.2816 * Math.sqrt(variance)).setScale(4, RoundingMode.HALF_UP);
        var daily = new ArrayList<Day>();
        for (int index = 0; index < remaining; index++) {
            daily.add(new Day(today.plusDays(index), mean.subtract(spread).max(ZERO), mean, mean.add(spread)));
        }
        return new Bands(daily, "run-rate (14-day mean)", null);
    }

    private static List<TariffPricing.Interval> synthesize(LocalDate day, BigDecimal importKwh, BigDecimal exportKwh,
            List<MeterUsage.HourlyUsage> shape) {
        BigDecimal importTotal = shape.stream().map(MeterUsage.HourlyUsage::importKwh).reduce(ZERO, BigDecimal::add);
        BigDecimal exportTotal = shape.stream().map(MeterUsage.HourlyUsage::exportKwh).reduce(ZERO, BigDecimal::add);
        var byHour = new BigDecimal[24][2];
        shape.forEach(hour -> {
            byHour[hour.hour()][0] = hour.importKwh();
            byHour[hour.hour()][1] = hour.exportKwh();
        });
        var intervals = new ArrayList<TariffPricing.Interval>(24);
        for (int hour = 0; hour < 24; hour++) {
            BigDecimal importShare = importTotal.signum() == 0 || byHour[hour][0] == null
                    ? BigDecimal.ONE.divide(BigDecimal.valueOf(24), 8, RoundingMode.HALF_UP)
                    : byHour[hour][0].divide(importTotal, 8, RoundingMode.HALF_UP);
            BigDecimal exportShare = exportTotal.signum() == 0 || byHour[hour][1] == null ? ZERO
                    : byHour[hour][1].divide(exportTotal, 8, RoundingMode.HALF_UP);
            Instant start = day.atTime(hour, 0).atZone(INDIA).toInstant();
            intervals.add(new TariffPricing.Interval(start, importKwh.multiply(importShare).setScale(4, RoundingMode.HALF_UP),
                    exportKwh.multiply(exportShare).setScale(4, RoundingMode.HALF_UP)));
        }
        return intervals;
    }

    private static BigDecimal average(List<MeterUsage.DailyUsage> days, boolean export) {
        if (days.isEmpty()) {
            return ZERO;
        }
        return days.stream().map(day -> export ? day.exportKwh() : day.importKwh()).reduce(ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(days.size()), 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal positive(JsonNode value) {
        return value.decimalValue().max(ZERO).setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    record Day(LocalDate day, BigDecimal p10, BigDecimal p50, BigDecimal p90) {
        BigDecimal value(String band) {
            return switch (band) {
                case "p10" -> p10;
                case "p90" -> p90;
                default -> p50;
            };
        }
    }

    record Bands(List<Day> daily, String model, BigDecimal trendPct) {
        BigDecimal total(String band) {
            return daily.stream().map(day -> day.value(band)).reduce(ZERO, BigDecimal::add);
        }
    }

    record Band(BigDecimal p10, BigDecimal p50, BigDecimal p90) {}

    record Forecast(UUID meterId, String meterSerial, String cycle, LocalDate asOf, int daysElapsed, int daysInCycle,
            BigDecimal importKwhToDate, BigDecimal exportKwhToDate, Band projectedKwh, Band projectedBillInr,
            BigDecimal lastCycleKwh, BigDecimal nextSlabAtKwh, Integer daysToNextSlab, List<Day> trajectory,
            String model, String source, BigDecimal trendPct, List<TariffPricing.Line> projectedLineItems) {}
}
