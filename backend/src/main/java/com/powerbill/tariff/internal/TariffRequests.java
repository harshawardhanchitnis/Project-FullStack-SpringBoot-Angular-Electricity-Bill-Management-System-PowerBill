package com.powerbill.tariff.internal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import com.powerbill.tariff.TariffPlan;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

final class TariffRequests {
    record Draft(@NotBlank @Pattern(regexp = "[A-Z0-9][A-Z0-9_-]{1,79}") String code,
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Pattern(regexp = "DOMESTIC|COMMERCIAL|INDUSTRIAL") String connectionType,
            @NotNull LocalDate effectiveFrom, LocalDate effectiveTo,
            @NotNull @PositiveOrZero @Digits(integer = 8, fraction = 4) BigDecimal fixedChargePerKw,
            @NotNull @Digits(integer = 8, fraction = 4) BigDecimal facPerKwh,
            @NotNull @PositiveOrZero @DecimalMax("100") @Digits(integer = 3, fraction = 4) BigDecimal electricityDutyPct,
            @NotNull @PositiveOrZero @DecimalMax("100") @Digits(integer = 3, fraction = 4) BigDecimal gstPct,
            @NotNull @PositiveOrZero @Digits(integer = 8, fraction = 4) BigDecimal solarExportRatePerKwh,
            @NotNull TariffPlan.SolarPolicy solarPolicy,
            @NotNull @PositiveOrZero @DecimalMax("100") @Digits(integer = 3, fraction = 4) BigDecimal lateFeeDailyPct,
            @NotNull @PositiveOrZero @DecimalMax("100") @Digits(integer = 3, fraction = 4) BigDecimal lateFeeCapPct,
            @Min(0) @Max(365) int graceDays,
            @NotNull @Size(min = 1, max = 30) List<@NotNull @Valid Slab> slabs,
            @NotNull @Size(min = 1, max = 24) List<@NotNull @Valid Window> windows) {

        TariffPlan create(UUID id, int version, boolean active) {
            return new TariffPlan(id, code, version, name.strip(), connectionType, effectiveFrom,
                    effectiveTo, fixedChargePerKw, facPerKwh, electricityDutyPct, gstPct, solarExportRatePerKwh,
                    solarPolicy, lateFeeDailyPct, lateFeeCapPct, graceDays, active,
                    slabs.stream().map(s -> new TariffPlan.Slab(s.fromKwh(), s.untilKwh(), s.ratePerKwh())).toList(),
                    windows.stream().map(w -> new TariffPlan.TimeWindow(w.name(), w.startHour(), w.endHour(),
                            w.rateMultiplier())).toList());
        }
    }

    record Slab(@NotNull @PositiveOrZero @Digits(integer = 8, fraction = 4) BigDecimal fromKwh,
            @PositiveOrZero @Digits(integer = 8, fraction = 4) BigDecimal untilKwh,
            @NotNull @PositiveOrZero @Digits(integer = 8, fraction = 4) BigDecimal ratePerKwh) {}

    record Window(@NotBlank @Pattern(regexp = "SOLAR_OFF_PEAK|NORMAL|EVENING_PEAK") String name,
            @Min(0) @Max(23) int startHour, @Min(1) @Max(24) int endHour,
            @NotNull @PositiveOrZero @Digits(integer = 1, fraction = 4) BigDecimal rateMultiplier) {}

    record Simulation(@NotNull UUID meterId, YearMonth month, @PositiveOrZero @DecimalMax("100") BigDecimal loadShiftPct,
            @PositiveOrZero @DecimalMax("10000") BigDecimal rooftopSolarKw,
            @PositiveOrZero @Digits(integer = 10, fraction = 2) BigDecimal installedCostInr) {}
}
