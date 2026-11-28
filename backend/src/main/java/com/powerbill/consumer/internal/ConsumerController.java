package com.powerbill.consumer.internal;

import java.util.List;
import java.util.UUID;

import com.powerbill.common.RequestContext;
import com.powerbill.consumer.MeterDirectory.Meter;
import com.powerbill.consumer.internal.ConsumerService.Actor;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class ConsumerController {
    private final ConsumerService consumers;

    ConsumerController(ConsumerService consumers) {
        this.consumers = consumers;
    }

    @PostMapping("/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    ConsumerService.Details register(@Valid @RequestBody ConsumerRequests.Registration input,
            HttpServletRequest request) {
        return consumers.register(input, "RESIDENTIAL", null, RequestContext.from(request));
    }

    @GetMapping("/consumers/me")
    ConsumerService.Details self(@AuthenticationPrincipal Jwt jwt) {
        return consumers.details(consumers.self(Actor.from(jwt).id()));
    }

    @PutMapping("/consumers/me")
    ConsumerStore.Account updateSelf(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ConsumerRequests.Update input, HttpServletRequest request) {
        Actor actor = Actor.from(jwt);
        return consumers.update(consumers.self(actor.id()), input, actor, RequestContext.from(request));
    }

    @PatchMapping("/consumers/me/auto-pay")
    ConsumerStore.Account autoPay(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ConsumerRequests.AutoPay input,
            HttpServletRequest request) {
        Actor actor = Actor.from(jwt);
        return consumers.autoPay(consumers.self(actor.id()), input, actor, RequestContext.from(request));
    }

    @GetMapping("/consumers/me/meters")
    List<Meter> meters(@AuthenticationPrincipal Jwt jwt) {
        return consumers.details(consumers.self(Actor.from(jwt).id())).meters();
    }

    @GetMapping("/meters/{meterId}")
    @PreAuthorize("hasRole('CUSTOMER')")
    Meter meter(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID meterId) {
        return consumers.customerMeter(Actor.from(jwt).id(), meterId);
    }

    @PostMapping("/consumers/me/premises")
    @ResponseStatus(HttpStatus.CREATED)
    ConsumerStore.Premise addPremise(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ConsumerRequests.Premise input, HttpServletRequest request) {
        Actor actor = Actor.from(jwt);
        return consumers.addPremise(consumers.self(actor.id()), input, actor, RequestContext.from(request));
    }

    @GetMapping("/ops/consumers")
    ConsumerStore.Page search(@RequestParam(defaultValue = "") String q,
            @RequestParam(required = false) String type, @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return consumers.search(q, type, status, page, size);
    }

    @PostMapping("/ops/consumers")
    @ResponseStatus(HttpStatus.CREATED)
    ConsumerService.Details opsRegister(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ConsumerRequests.OpsRegistration input, HttpServletRequest request) {
        return consumers.register(input.customer(), input.accountType(), Actor.from(jwt), RequestContext.from(request));
    }

    @GetMapping("/ops/consumers/{id}")
    ConsumerService.Details opsGet(@PathVariable UUID id) {
        return consumers.details(consumers.account(id));
    }

    @PutMapping("/ops/consumers/{id}")
    ConsumerStore.Account opsUpdate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody ConsumerRequests.Update input, HttpServletRequest request) {
        return consumers.update(consumers.account(id), input, Actor.from(jwt), RequestContext.from(request));
    }

    @PatchMapping("/ops/consumers/{id}/status")
    ConsumerStore.Account opsStatus(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody ConsumerRequests.StatusChange input, HttpServletRequest request) {
        return consumers.changeStatus(id, input, Actor.from(jwt), RequestContext.from(request));
    }

    @PostMapping("/ops/consumers/{id}/meters")
    @ResponseStatus(HttpStatus.CREATED)
    Meter opsAddMeter(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody ConsumerRequests.NewMeter input, HttpServletRequest request) {
        return consumers.addMeter(id, input, Actor.from(jwt), RequestContext.from(request));
    }
}
