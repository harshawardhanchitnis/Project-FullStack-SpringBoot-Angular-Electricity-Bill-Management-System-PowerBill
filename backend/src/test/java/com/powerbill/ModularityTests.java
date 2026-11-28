package com.powerbill;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTests {

    @Test
    void modulesHaveNoIllegalDependencies() {
        ApplicationModules modules = ApplicationModules.of(PowerBillApplication.class);
        assertThat(modules.stream()
                .map(ApplicationModule::getIdentifier)
                .map(Object::toString))
                .containsExactlyInAnyOrder(
                        "analytics", "audit", "billing", "common", "consumer", "identity",
                        "metering", "notification", "payment", "support", "tariff");
        modules.verify();
    }
}
