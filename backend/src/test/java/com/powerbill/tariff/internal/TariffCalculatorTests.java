package com.powerbill.tariff.internal;

import static java.math.BigDecimal.ZERO;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import com.powerbill.tariff.TariffPlan;
import com.powerbill.tariff.TariffPlan.Slab;
import com.powerbill.tariff.TariffPlan.SolarPolicy;
import com.powerbill.tariff.TariffPlan.TimeWindow;
import com.powerbill.tariff.TariffPricing.Interval;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TariffCalculatorTests {
    private final TariffCalculator calculator = new TariffCalculator();

    @ParameterizedTest
    @CsvSource({
        "0,0.00", "0.001,0.00", "0.002,0.01", "0.005,0.02", "0.3333,1.00", "1,3.00", "45.7,137.10",
        "99.9999,300.00", "100,300.00", "100.001,300.01", "100.1,300.50", "101,305.00", "250,1050.00",
        "299.9,1299.50", "300,1300.00", "301,1307.00", "350,1650.00", "499.9,2699.30",
        "500,2700.00", "500.01,2700.09", "501,2709.00", "720,4680.00"
    })
    void chargesOnlyTheUnitsInEachSlab(String kwh, String rupees) {
        var quote = calculator.price(plan(SolarPolicy.CARRY_FORWARD), ZERO, false, ZERO,
                List.of(reading(4, kwh, "0")));
        assertThat(quote.totalInr()).isEqualByComparingTo(rupees);
    }

    @Test
    void appliesTheLocalTimeWindowsAndChronologicalSlabs() {
        var quote = calculator.price(plan(SolarPolicy.CARRY_FORWARD), ZERO, false, ZERO,
                List.of(reading(18, "20", "0"), reading(9, "90", "0"), reading(17, "5", "0")));
        // 90 solar units at 3 * .8, 5 normal units at 3, 5 peak units at 3 * 1.2 and 15 at 5 * 1.2.
        assertThat(quote.totalInr()).isEqualByComparingTo("339.00");
    }

    @Test
    void carriesKwhSurplusForwardAndUsesItInTheFollowingCycle() {
        var first = calculator.price(plan(SolarPolicy.CARRY_FORWARD), ZERO, true, ZERO,
                List.of(reading(4, "1", "5")));
        assertThat(first.carryForwardKwh()).isEqualByComparingTo("4");
        var second = calculator.price(plan(SolarPolicy.CARRY_FORWARD), ZERO, true, first.carryForwardKwh(),
                List.of(reading(4, "5", "0")));
        assertThat(second.totalInr()).isEqualByComparingTo("3.00");
        assertThat(second.carryForwardKwh()).isEqualByComparingTo("0");
    }

    @Test
    void keepsSolarCashBeyondTheBillInABalance() {
        var original = plan(SolarPolicy.CASH_CREDIT);
        var plan = new TariffPlan(original.id(), original.code(), 1, original.name(), "DOMESTIC",
                original.effectiveFrom(), null, new BigDecimal("10"), ZERO, ZERO, ZERO, new BigDecimal("2"),
                SolarPolicy.CASH_CREDIT, ZERO, ZERO, 0, true, original.slabs(), original.windows());
        var quote = calculator.price(plan, BigDecimal.ONE, true, ZERO, List.of(reading(4, "0", "6")));
        assertThat(quote.totalInr()).isEqualByComparingTo("0.00");
        assertThat(quote.balancedCreditInr()).isEqualByComparingTo("2.00");
        assertThat(quote.carryForwardKwh()).isEqualByComparingTo("0");
    }

    @Test
    void totalsFixedFacAndBothTaxesAtPaisePrecision() {
        var original = plan(SolarPolicy.CARRY_FORWARD);
        var plan = new TariffPlan(original.id(), original.code(), 1, original.name(), "DOMESTIC",
                original.effectiveFrom(), null, new BigDecimal("15.5"), new BigDecimal("0.3849"),
                new BigDecimal("10"), new BigDecimal("5"), ZERO, SolarPolicy.CARRY_FORWARD, ZERO, ZERO, 0,
                true, original.slabs(), original.windows());
        var quote = calculator.price(plan, new BigDecimal("2.5"), false, ZERO, List.of(reading(4, "10.01", "0")));
        // 30.03 energy + 38.75 fixed + 3.85 FAC, plus 7.26 duty and 3.63 GST.
        assertThat(quote.totalInr()).isEqualByComparingTo("83.52");
    }

    private static Interval reading(int hour, String imported, String exported) {
        return new Interval(ZonedDateTime.of(2026, 1, 1, hour, 0, 0, 0, TariffCalculator.INDIA).toInstant(),
                new BigDecimal(imported), new BigDecimal(exported));
    }

    private static TariffPlan plan(SolarPolicy policy) {
        return new TariffPlan(UUID.randomUUID(), "DEMO", 1, "Tariff", "DOMESTIC", LocalDate.of(2020,1,1), null,
                ZERO, ZERO, ZERO, ZERO, ZERO, policy, ZERO, ZERO, 0, true,
                List.of(new Slab(ZERO, BigDecimal.valueOf(100), BigDecimal.valueOf(3)),
                        new Slab(BigDecimal.valueOf(100), BigDecimal.valueOf(300), BigDecimal.valueOf(5)),
                        new Slab(BigDecimal.valueOf(300), BigDecimal.valueOf(500), BigDecimal.valueOf(7)),
                        new Slab(BigDecimal.valueOf(500), null, BigDecimal.valueOf(9))),
                List.of(new TimeWindow("NORMAL", 0, 9, BigDecimal.ONE),
                        new TimeWindow("SOLAR_OFF_PEAK", 9, 17, new BigDecimal("0.8")),
                        new TimeWindow("NORMAL", 17, 18, BigDecimal.ONE),
                        new TimeWindow("EVENING_PEAK", 18, 22, new BigDecimal("1.2")),
                        new TimeWindow("NORMAL", 22, 24, BigDecimal.ONE)));
    }
}
