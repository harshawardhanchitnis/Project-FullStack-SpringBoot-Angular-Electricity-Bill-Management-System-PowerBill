package com.powerbill.tariff.internal;

import static java.math.BigDecimal.ZERO;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.powerbill.tariff.TariffPlan;
import com.powerbill.tariff.TariffPlan.Slab;
import com.powerbill.tariff.TariffPlan.TimeWindow;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("dev & !prod")
@ConditionalOnProperty(name = "gridiq.demo.enabled", havingValue = "true")
@org.springframework.core.annotation.Order(20)
class DemoTariffs implements ApplicationRunner {
    private static final LocalDate BEGIN = LocalDate.of(2000, 1, 1);
    private final TariffStore store;

    DemoTariffs(TariffStore store) {
        this.store = store;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seed("DOMESTIC", "85", "3.25", "1.10");
        seed("COMMERCIAL", "155", "7.10", "1.20");
        seed("INDUSTRIAL", "310", "6.60", "1.20");
    }

    private void seed(String category, String fixed, String firstRate, String peak) {
        store.lock(category);
        String code = "DEMO-" + category;
        if (store.nextVersion(code) > 1
                || store.overlaps(new UUID(0, 0), category, BEGIN, null)) {
            return;
        }
        BigDecimal rate = new BigDecimal(firstRate);
        List<Slab> slabs = List.of(new Slab(ZERO, BigDecimal.valueOf(100), rate),
                new Slab(BigDecimal.valueOf(100), BigDecimal.valueOf(300), rate.add(BigDecimal.valueOf(2))),
                new Slab(BigDecimal.valueOf(300), BigDecimal.valueOf(500), rate.add(BigDecimal.valueOf(4))),
                new Slab(BigDecimal.valueOf(500), null, rate.add(BigDecimal.valueOf(6))));
        var windows = List.of(new TimeWindow("NORMAL", 0, 9, BigDecimal.ONE),
                new TimeWindow("SOLAR_OFF_PEAK", 9, 17, new BigDecimal("0.80")),
                new TimeWindow("NORMAL", 17, 18, BigDecimal.ONE),
                new TimeWindow("EVENING_PEAK", 18, 22, new BigDecimal(peak)),
                new TimeWindow("NORMAL", 22, 24, BigDecimal.ONE));
        store.insert(new TariffPlan(UUID.randomUUID(), code, 1, "Illustrative " + category + " tariff",
                category, BEGIN, null, new BigDecimal(fixed), new BigDecimal("0.38"),
                new BigDecimal("10.0"), ZERO, ZERO, TariffPlan.SolarPolicy.CARRY_FORWARD,
                new BigDecimal("0.05"), BigDecimal.valueOf(5), 3, true, slabs, windows), null);
    }
}
