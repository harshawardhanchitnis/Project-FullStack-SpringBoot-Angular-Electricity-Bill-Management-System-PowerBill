package com.powerbill.metering;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

public interface MeterUsage {

    ZoneId BILLING_ZONE = ZoneId.of("Asia/Kolkata");

    List<Reading> intervalReadings(UUID meterId, Instant from, Instant to);

    record Reading(Instant start, BigDecimal importKwh, BigDecimal exportKwh, BigDecimal voltage,
            BigDecimal powerFactor, String source, String quality) {}
}
