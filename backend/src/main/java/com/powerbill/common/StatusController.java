package com.powerbill.common;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class StatusController {
    private final boolean demoMode;

    StatusController(Environment environment) {
        demoMode = environment.acceptsProfiles(Profiles.of("dev & !prod"))
                && environment.getProperty("gridiq.demo.enabled", Boolean.class, false);
    }

    @GetMapping("/api/v1/status")
    ServiceStatus status() {
        return new ServiceStatus("UP", "gridiq-core", demoMode);
    }

    record ServiceStatus(String status, String service, boolean demoMode) {}
}
