package com.powerbill.analytics.internal;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.powerbill.common.RequestContext;
import com.powerbill.consumer.AccountDirectory;
import com.powerbill.consumer.AccountDirectory.ServicePoint;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1")
class AnalyticsController {
    private final AnalyticsService analytics;
    private final ForecastService forecasts;
    private final AiService ai;
    private final AccountDirectory accounts;
    private final AiThrottle throttle;

    AnalyticsController(AnalyticsService analytics, ForecastService forecasts, AiService ai, AccountDirectory accounts,
            AiThrottle throttle) {
        this.analytics = analytics;
        this.forecasts = forecasts;
        this.ai = ai;
        this.accounts = accounts;
        this.throttle = throttle;
    }

    @GetMapping("/analytics/consumer/overview")
    AnalyticsService.ConsumerOverview overview(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) UUID meterId) {
        var account = self(jwt);
        return analytics.overview(account, meter(account, meterId), today());
    }

    @GetMapping("/ai/forecast/{meterId}")
    ForecastService.Forecast forecast(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID meterId,
            HttpServletRequest request) {
        throttle.acquire("forecast", UUID.fromString(jwt.getSubject()), AiThrottle.FORECAST_PER_MINUTE);
        var account = self(jwt);
        return forecasts.forecast(meter(account, meterId), today(), RequestContext.from(request).correlationId());
    }

    @PostMapping("/ai/copilot/chat")
    JsonNode chat(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Chat input, HttpServletRequest request) {
        UUID user = UUID.fromString(jwt.getSubject());
        throttle.acquire("assistant", user, AiThrottle.ASSISTANT_PER_MINUTE);
        var account = self(jwt);
        return ai.chat(account, meter(account, input.meterId()), input.message().strip(), input.history(),
                RequestContext.from(request).correlationId());
    }

    @GetMapping("/ops/analytics/executive")
    AnalyticsService.Executive executive() {
        return analytics.executive();
    }

    @PostMapping("/ops/ai/complaints/{id}/triage")
    JsonNode triage(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, HttpServletRequest request) {
        throttle.acquire("assistant", UUID.fromString(jwt.getSubject()), AiThrottle.ASSISTANT_PER_MINUTE);
        return ai.triage(id, RequestContext.from(request).correlationId());
    }

    private AccountDirectory.Account self(Jwt jwt) {
        return accounts.accountForUser(UUID.fromString(jwt.getSubject()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Consumer account was not found."));
    }

    private ServicePoint meter(AccountDirectory.Account account, UUID meterId) {
        var points = accounts.servicePoints(account.id());
        return points.stream().filter(point -> meterId == null || point.meterId().equals(meterId)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Meter was not found."));
    }

    private static LocalDate today() {
        return LocalDate.now(ForecastService.INDIA);
    }

    record Chat(@NotBlank @Size(max = 1000) String message, UUID meterId,
            @Size(max = 20) List<Map<String, String>> history) {}
}

/** Periodic ML anomaly scanning and bill-shock alerts. */
@Component
class AiJobs {
    private static final Logger LOG = LoggerFactory.getLogger(AiJobs.class);
    private final AiService ai;

    AiJobs(AiService ai) {
        this.ai = ai;
    }

    @Async
    @EventListener(ApplicationReadyEvent.class)
    void afterStartup() {
        scan();
        alerts();
    }

    @Scheduled(cron = "${gridiq.ml.scan-cron:0 0 */6 * * *}", zone = "Asia/Kolkata")
    void scan() {
        try {
            int found = ai.scanAnomalies("gridiq-anomaly-scan");
            LOG.info("Anomaly scan recorded {} new anomalies", found);
        } catch (RuntimeException error) {
            LOG.warn("Anomaly scan failed ({})", error.getClass().getSimpleName());
        }
    }

    @Scheduled(cron = "${gridiq.ml.budget-cron:0 30 7 * * *}", zone = "Asia/Kolkata")
    void alerts() {
        try {
            int alerts = ai.budgetAlerts("gridiq-budget-watch");
            LOG.info("Budget watch published {} alerts", alerts);
        } catch (RuntimeException error) {
            LOG.warn("Budget watch failed ({})", error.getClass().getSimpleName());
        }
    }
}
