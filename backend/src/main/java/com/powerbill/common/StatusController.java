package com.powerbill.common;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class StatusController {

    @GetMapping("/api/v1/status")
    ServiceStatus status() {
        return new ServiceStatus("UP", "gridiq-core");
    }

    record ServiceStatus(String status, String service) {}
}
