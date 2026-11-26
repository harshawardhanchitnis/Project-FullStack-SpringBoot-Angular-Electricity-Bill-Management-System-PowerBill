package com.powerbill.consumer;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public interface MeterDirectory {
    Meter customerMeter(UUID userId, UUID meterId);

    record Meter(UUID id, UUID premiseId, String meterSerial, String connectionType,
            BigDecimal sanctionedLoadKw, boolean solarNetMetering, BigDecimal solarCapacityKw,
            UUID tariffPlanId, String status, OffsetDateTime installedAt, long version) {}
}
