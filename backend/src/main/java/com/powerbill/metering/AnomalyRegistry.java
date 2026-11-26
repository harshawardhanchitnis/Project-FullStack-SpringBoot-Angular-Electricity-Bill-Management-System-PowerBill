package com.powerbill.metering;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Records anomalies found by the VEE rules or the ML detector and summarizes the investigation queue. */
public interface AnomalyRegistry {

    Optional<UUID> record(UUID meterId, Instant windowStart, Instant windowEnd, String anomalyType, String severity,
            BigDecimal score, List<Reason> reasons, String source);

    List<Summary> forMeter(UUID meterId, int limit);

    Statistics statistics();

    record Reason(String code, String detail) {}

    record Summary(UUID id, String anomalyType, String severity, BigDecimal score, String status,
            OffsetDateTime windowStart, OffsetDateTime windowEnd, String reasons) {}

    record Count(String key, int count) {}

    record Statistics(int open, int investigating, int confirmedTamper, int confirmedFault, int falsePositive,
            List<Count> openByType, List<Count> openBySeverity) {}
}
