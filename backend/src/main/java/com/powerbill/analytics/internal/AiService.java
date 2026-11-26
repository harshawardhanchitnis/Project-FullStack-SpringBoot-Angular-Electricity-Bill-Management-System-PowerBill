package com.powerbill.analytics.internal;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.powerbill.analytics.AnalyticsEvents;
import com.powerbill.billing.BillingLedger;
import com.powerbill.consumer.AccountDirectory;
import com.powerbill.consumer.AccountDirectory.ServicePoint;
import com.powerbill.metering.AnomalyRegistry;
import com.powerbill.metering.MeterUsage;
import com.powerbill.support.SupportDirectory;
import com.powerbill.tariff.TariffPricing;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

/** Grounded AI features. All figures sent to the model come from the ledger, tariff engine and meter data. */
@Service
class AiService {
    private static final Logger LOG = LoggerFactory.getLogger(AiService.class);
    private static final int AI_REQUESTS_PER_MINUTE = 20;
    private final AccountDirectory accounts;
    private final MeterUsage usage;
    private final BillingLedger billing;
    private final SupportDirectory support;
    private final AnomalyRegistry anomalies;
    private final TariffPricing pricing;
    private final ForecastService forecasts;
    private final MlClient ml;
    private final StringRedisTemplate redis;
    private final ApplicationEventPublisher events;
    private final Counter detected;

    AiService(AccountDirectory accounts, MeterUsage usage, BillingLedger billing, SupportDirectory support,
            AnomalyRegistry anomalies, TariffPricing pricing, ForecastService forecasts, MlClient ml,
            StringRedisTemplate redis, ApplicationEventPublisher events, MeterRegistry metrics) {
        this.accounts = accounts;
        this.usage = usage;
        this.billing = billing;
        this.support = support;
        this.anomalies = anomalies;
        this.pricing = pricing;
        this.forecasts = forecasts;
        this.ml = ml;
        this.redis = redis;
        this.events = events;
        this.detected = Counter.builder("powerbill.anomalies.detected").register(metrics);
    }

    void throttle(UUID user) {
        long minute = Instant.now().getEpochSecond() / 60;
        String key = "gridiq:ai:limit:" + user + ":" + minute;
        Long count;
        try {
            count = redis.opsForValue().increment(key);
            redis.expire(key, Duration.ofSeconds(70));
        } catch (DataAccessException error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The assistant is temporarily unavailable.");
        }
        if (count != null && count > AI_REQUESTS_PER_MINUTE) {
            long retry = 60 - Instant.now().getEpochSecond() % 60;
            throw new TooManyRequests(retry);
        }
    }

    JsonNode chat(AccountDirectory.Account account, ServicePoint point, String message, List<Map<String, String>> history,
            String correlationId) {
        var context = customerContext(account, point, correlationId);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("message", message);
        request.put("audience", "CUSTOMER");
        request.put("history", history == null ? List.of() : history.subList(Math.max(0, history.size() - 6),
                history.size()));
        request.put("context", context);
        return ml.post("/ml/v1/copilot/respond", request, correlationId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The energy assistant is unavailable."));
    }

    JsonNode triage(UUID complaintId, String correlationId) {
        var complaint = support.complaint(complaintId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Complaint was not found."));
        var account = accounts.findAccount(complaint.accountId()).orElseThrow();
        ServicePoint point = complaint.meterId() != null ? accounts.servicePoint(complaint.meterId())
                : accounts.servicePoints(account.id()).stream().findFirst().orElse(null);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("complaint", complaint);
        request.put("context", point == null ? Map.of() : customerContext(account, point, correlationId));
        if (complaint.billId() != null) {
            billing.header(complaint.billId()).ifPresent(bill -> request.put("linkedBill",
                    Map.of("bill", bill, "lineItems", billing.lineItems(bill.id()))));
        }
        return ml.post("/ml/v1/copilot/triage", request, correlationId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The triage assistant is unavailable."));
    }

    Map<String, Object> customerContext(AccountDirectory.Account account, ServicePoint point, String correlationId) {
        LocalDate today = LocalDate.now(ForecastService.INDIA);
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("today", today.toString());
        context.put("account", Map.of("name", account.fullName(), "consumerNumber", account.consumerNumber(),
                "type", account.accountType(), "monthlyBudgetInr", account.monthlyBudgetInr() == null ? "not set"
                        : account.monthlyBudgetInr()));
        context.put("meter", Map.of("serial", point.meterSerial(), "connectionType", point.connectionType(),
                "sanctionedLoadKw", point.sanctionedLoadKw(), "solarNetMetering", point.solarNetMetering(),
                "premise", point.premiseLabel() + ", " + point.city()));
        var bills = new ArrayList<Map<String, Object>>();
        for (var bill : billing.recentBills(account.id(), 6)) {
            if (bill.meterId().equals(point.meterId()) && bills.size() < 3) {
                bills.add(Map.of("billNumber", bill.billNumber(), "period", bill.period().toString(),
                        "importKwh", bill.importKwh(), "exportKwh", bill.exportKwh(), "totalInr", bill.totalAmount(),
                        "outstandingInr", bill.outstanding(), "status", bill.status(), "dueDate", bill.dueDate().toString(),
                        "lineItems", billing.lineItems(bill.id())));
            }
        }
        context.put("bills", bills);
        context.put("receivables", billing.receivables(account.id()));
        var plan = pricing.applicablePlan(point.connectionType(), today.withDayOfMonth(1));
        context.put("tariff", Map.of("name", plan.name(), "slabs", plan.slabs(), "timeWindows", plan.windows(),
                "fixedChargePerKw", plan.fixedChargePerKw(), "electricityDutyPct", plan.electricityDutyPct(),
                "facPerKwh", plan.facPerKwh(), "lateFeeDailyPct", plan.lateFeeDailyPct(),
                "solarPolicy", plan.solarPolicy()));
        try {
            var forecast = forecasts.forecast(point, today, correlationId);
            context.put("forecast", Map.of("cycle", forecast.cycle(), "importKwhToDate", forecast.importKwhToDate(),
                    "projectedKwh", forecast.projectedKwh(), "projectedBillInr", forecast.projectedBillInr(),
                    "daysToNextSlab", forecast.daysToNextSlab() == null ? "none" : forecast.daysToNextSlab(),
                    "nextSlabAtKwh", forecast.nextSlabAtKwh() == null ? "none" : forecast.nextSlabAtKwh(),
                    "model", forecast.model()));
        } catch (RuntimeException error) {
            LOG.debug("Forecast context unavailable ({})", error.getClass().getSimpleName());
        }
        var shape = usage.hourlyProfile(point.meterId(), today.minusDays(30), today);
        BigDecimal total = shape.stream().map(MeterUsage.HourlyUsage::importKwh).reduce(BigDecimal.ZERO, BigDecimal::add);
        var windows = new LinkedHashMap<String, BigDecimal>();
        for (var hour : shape) {
            plan.windows().stream().filter(item -> item.startHour() <= hour.hour() && item.endHour() > hour.hour())
                    .findFirst().ifPresent(window -> windows.merge(window.name(), hour.importKwh(), BigDecimal::add));
        }
        windows.replaceAll((key, value) -> AnalyticsService.scale(value));
        context.put("last30DaysTodKwh", windows);
        context.put("last30DaysImportKwh", AnalyticsService.scale(total));
        context.put("openComplaints", support.openComplaints(account.id()));
        context.put("activeOutages", support.activeOutages(accounts.pincodes(account.id())).stream()
                .map(AiService::outageContext).toList());
        context.put("meterAnomalies", anomalies.forMeter(point.meterId(), 3));
        return context;
    }

    /** Sends recent hourly telemetry for each meter to the ML detector and records explainable anomalies. */
    int scanAnomalies(String correlationId) {
        if (!ml.enabled()) {
            return 0;
        }
        int recorded = 0;
        Instant end = LocalDate.now(ForecastService.INDIA).atStartOfDay(ForecastService.INDIA).toInstant();
        Instant start = end.minus(Duration.ofDays(45));
        for (ServicePoint point : accounts.billableServicePoints()) {
            var readings = usage.intervalReadings(point.meterId(), start, end).stream()
                    .filter(reading -> !reading.quality().equals("FLAGGED"))
                    .map(reading -> Map.of("start", reading.start().toString(), "importKwh", reading.importKwh(),
                            "exportKwh", reading.exportKwh()))
                    .toList();
            if (readings.size() < 24 * 21) {
                continue;
            }
            var response = ml.post("/ml/v1/anomalies/detect", Map.of("meterId", point.meterId().toString(),
                    "solarNetMetering", point.solarNetMetering(), "readings", readings), correlationId);
            if (response.isEmpty()) {
                return recorded;
            }
            for (JsonNode anomaly : response.get().path("anomalies")) {
                var reasons = new ArrayList<AnomalyRegistry.Reason>();
                anomaly.path("reasons").forEach(reason -> reasons.add(new AnomalyRegistry.Reason(
                        reason.path("code").asText(), reason.path("detail").asText())));
                var id = anomalies.record(point.meterId(), Instant.parse(anomaly.path("windowStart").asText()),
                        Instant.parse(anomaly.path("windowEnd").asText()), anomaly.path("anomalyType").asText(),
                        anomaly.path("severity").asText(), anomaly.path("score").decimalValue(), reasons, "ML");
                if (id.isPresent()) {
                    recorded++;
                    detected.increment();
                }
            }
        }
        return recorded;
    }

    /** Publishes bill-shock alerts for accounts whose projected bill exceeds the budget. */
    int budgetAlerts(String correlationId) {
        int alerts = 0;
        LocalDate today = LocalDate.now(ForecastService.INDIA);
        for (ServicePoint point : accounts.billableServicePoints()) {
            var account = point.account();
            ForecastService.Forecast forecast;
            try {
                // Warms the forecast cache for every meter, then alerts only customers with a budget.
                forecast = forecasts.forecast(point, today, correlationId);
            } catch (RuntimeException error) {
                LOG.debug("Skipped a forecast ({})", error.getClass().getSimpleName());
                continue;
            }
            if (account.monthlyBudgetInr() == null || account.monthlyBudgetInr().signum() == 0
                    || today.getDayOfMonth() < 5) {
                continue;
            }
            try {
                BigDecimal projected = forecast.projectedBillInr().p50();
                String reason = null;
                if (projected.compareTo(account.monthlyBudgetInr()) > 0) {
                    reason = "That is above your monthly budget of ₹" + account.monthlyBudgetInr().toPlainString() + ".";
                } else if (forecast.daysToNextSlab() != null && forecast.daysToNextSlab() <= 5) {
                    reason = "You are likely to enter the next tariff slab at " + forecast.nextSlabAtKwh()
                            .stripTrailingZeros().toPlainString() + " kWh within " + forecast.daysToNextSlab() + " days.";
                }
                if (reason != null) {
                    events.publishEvent(new AnalyticsEvents.BudgetAlert(account.userId(), account.id(),
                            point.meterId(), point.meterSerial(), forecast.cycle(), projected,
                            account.monthlyBudgetInr(), reason));
                    alerts++;
                }
            } catch (RuntimeException error) {
                LOG.debug("Skipped a budget forecast ({})", error.getClass().getSimpleName());
            }
        }
        return alerts;
    }

    /** Sends the restoration estimate as an ISO-8601 instant only when one is known; the ML service formats it. */
    private static Map<String, Object> outageContext(SupportDirectory.Outage outage) {
        var item = new LinkedHashMap<String, Object>();
        item.put("title", outage.title());
        item.put("status", outage.status());
        if (outage.estimatedRestorationAt() != null) {
            item.put("estimatedRestorationAt", outage.estimatedRestorationAt().toInstant().toString());
        }
        return item;
    }

    private static final class TooManyRequests extends ResponseStatusException {
        private final HttpHeaders headers = new HttpHeaders();

        TooManyRequests(long seconds) {
            super(HttpStatus.TOO_MANY_REQUESTS, "Too many assistant requests. Try again shortly.");
            headers.set(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }
    }
}
