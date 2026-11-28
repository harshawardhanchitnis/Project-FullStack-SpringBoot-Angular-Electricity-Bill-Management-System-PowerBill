package com.powerbill.metering;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

public interface MeterUsage {

    ZoneId BILLING_ZONE = ZoneId.of("Asia/Kolkata");

    List<Reading> intervalReadings(UUID meterId, Instant from, Instant to);

    /** Daily totals in Asia/Kolkata, excluding flagged intervals. {@code to} is exclusive. */
    List<DailyUsage> dailyUsage(UUID meterId, LocalDate from, LocalDate to);

    /** Imported and exported energy summed by local hour of day over a date range. */
    List<HourlyUsage> hourlyProfile(UUID meterId, LocalDate from, LocalDate to);

    record DailyUsage(LocalDate day, BigDecimal importKwh, BigDecimal exportKwh, long validIntervals,
            long flaggedIntervals) {}

    record HourlyUsage(int hour, BigDecimal importKwh, BigDecimal exportKwh) {}

    /** Import and export per meter over [from, to) in IST days, from the daily continuous aggregate. */
    java.util.Map<UUID, Totals> totalsByMeter(LocalDate from, LocalDate to);

    record Totals(BigDecimal importKwh, BigDecimal exportKwh) {}

    record Reading(Instant start, BigDecimal importKwh, BigDecimal exportKwh, BigDecimal voltage,
            BigDecimal powerFactor, String source, String quality) {}
}
