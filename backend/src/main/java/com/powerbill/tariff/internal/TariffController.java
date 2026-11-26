package com.powerbill.tariff.internal;

import java.util.List;
import java.util.UUID;

import com.powerbill.common.RequestContext;
import com.powerbill.tariff.TariffPlan;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class TariffController {
    private final TariffService tariffs;

    TariffController(TariffService tariffs) {
        this.tariffs = tariffs;
    }

    @GetMapping("/tariffs")
    List<TariffPlan> active() {
        return tariffs.active();
    }

    @PostMapping("/tariffs/simulate")
    @PreAuthorize("hasRole('CUSTOMER')")
    TariffService.SimulationResult simulate(@Valid @RequestBody TariffRequests.Simulation input,
            @AuthenticationPrincipal Jwt jwt) {
        return tariffs.simulate(UUID.fromString(jwt.getSubject()), input);
    }

    @GetMapping("/ops/tariffs")
    List<TariffPlan> all() {
        return tariffs.all();
    }

    @PostMapping("/ops/tariffs")
    @ResponseStatus(HttpStatus.CREATED)
    TariffPlan create(@Valid @RequestBody TariffRequests.Draft input, @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request) {
        return tariffs.create(input, jwt, RequestContext.from(request));
    }

    @PutMapping("/ops/tariffs/{id}")
    TariffPlan update(@PathVariable UUID id, @Valid @RequestBody TariffRequests.Draft input,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        return tariffs.update(id, input, jwt, RequestContext.from(request));
    }

    @PatchMapping("/ops/tariffs/{id}/activate")
    TariffPlan activate(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        return tariffs.activate(id, jwt, RequestContext.from(request));
    }
}
