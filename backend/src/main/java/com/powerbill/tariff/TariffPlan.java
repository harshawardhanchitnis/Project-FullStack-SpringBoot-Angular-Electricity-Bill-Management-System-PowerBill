package com.powerbill.tariff;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record TariffPlan(UUID id, String code, int version, String name, String connectionType,
        LocalDate effectiveFrom, LocalDate effectiveTo, BigDecimal fixedChargePerKw, BigDecimal facPerKwh,
        BigDecimal electricityDutyPct, BigDecimal gstPct, BigDecimal solarExportRatePerKwh, SolarPolicy solarPolicy,
        BigDecimal lateFeeDailyPct, BigDecimal lateFeeCapPct, int graceDays, boolean active,
        List<Slab> slabs, List<TimeWindow> windows) {

    public TariffPlan {
        slabs = List.copyOf(slabs);
        windows = List.copyOf(windows);
    }

    public enum SolarPolicy { CARRY_FORWARD, CASH_CREDIT }
    public record Slab(BigDecimal fromKwh, BigDecimal untilKwh, BigDecimal ratePerKwh) {}
    public record TimeWindow(String name, int startHour, int endHour, BigDecimal rateMultiplier) {}
}
