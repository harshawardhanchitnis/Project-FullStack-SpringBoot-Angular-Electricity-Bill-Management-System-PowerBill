package com.powerbill.metering;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class MeteringEvents {
    private MeteringEvents() {}

    public record AnomalyFlagged(UUID anomalyId, UUID meterId, String anomalyType, String severity, BigDecimal score,
            String summary, Instant windowStart, String source) {}
}
