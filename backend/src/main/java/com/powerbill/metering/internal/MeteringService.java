package com.powerbill.metering.internal;

import static java.math.BigDecimal.ZERO;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.powerbill.audit.AuditAction;
import com.powerbill.common.RequestContext;
import com.powerbill.consumer.MeterDirectory;
import com.powerbill.consumer.MeterDirectory.Meter;
import com.powerbill.metering.MeterUsage;
import jakarta.validation.constraints.NotNull;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
class MeteringService implements MeterUsage {

    private final ReadingStore store;
    private final MeterDirectory directory;
    private final ApplicationEventPublisher events;
    private final ObjectMapper json;

    MeteringService(ReadingStore store, MeterDirectory directory, ApplicationEventPublisher events, ObjectMapper json) {
        this.store = store;
        this.directory = directory;
        this.events = events;
        this.json = json;
    }

    @Transactional
    Ingestion ingest(UUID id, List<Input> batch, String source, Jwt actor, RequestContext request) {
        Meter meter = directory.meter(id);
        if (!meter.status().equals("ACTIVE")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The meter is not active.");
        }
        if (batch.isEmpty() || batch.size() > 2000) {
            throw invalid("Supply between 1 and 2000 hourly readings.");
        }
        batch.forEach(MeteringService::checkInput);
        List<Input> readings = batch.stream().sorted(Comparator.comparing(Input::start)).toList();
        if (readings.stream().map(Input::start).distinct().count() != readings.size()) {
            throw invalid("Each interval can appear only once in a batch.");
        }
        int written = 0;
        int flagged = 0;
        for (Input input : readings) {
            var reasons = vee(meter, input);
            String quality = !reasons.isEmpty() ? "FLAGGED"
                    : source.equals("GREEN_BUTTON") && input.estimated() ? "ESTIMATED" : "VALID";
            int count = store.write(id, new Reading(input.start(), input.importKwh(), input.exportKwh(),
                    input.voltage(), input.powerFactor(), source, quality));
            if (count == 1) {
                written++;
                if (!reasons.isEmpty()) {
                    flagged++;
                    reasons.forEach(reason -> store.exception(id, input.start(), reason));
                    store.anomaly(id, input.start(), anomalyType(reasons.getFirst()), "HIGH", 75,
                            json.writeValueAsString(reasons));
                }
            }
        }
        audit("METER_READINGS_INGESTED", "METER", id, actor, request,
                Map.of("inserted", written, "flagged", flagged, "source", source));
        return new Ingestion(written, readings.size() - written, flagged);
    }

    @Override
    public List<Reading> intervalReadings(UUID meterId, Instant from, Instant to) {
        validateRange(from, to);
        return store.readings(meterId, from, to);
    }

    @Transactional
    Reading estimate(UUID id, Instant start, Jwt actor, RequestContext request) {
        if (start == null) {
            throw invalid("Specify the start of the missing hour.");
        }
        Meter meter = directory.meter(id);
        checkInput(new Input(start, ZERO, ZERO, null, null));
        BigDecimal imported = store.historicalMean(id, start, false);
        BigDecimal exported = meter.solarNetMetering() ? store.historicalMean(id, start, true) : ZERO;
        if (imported == null || exported == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "At least three valid readings from the same hour in the past seven days are required.");
        }
        var result = new Reading(start, imported, exported, null, null, "MANUAL", "ESTIMATED");
        if (store.write(id, result) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A reading already exists for this hour.");
        }
        audit("METER_READING_ESTIMATED", "METER", id, actor, request, Map.of("start", start.toString()));
        return result;
    }

    @Transactional
    Reading correct(UUID id, Input input, String note, Jwt actor, RequestContext request) {
        if (input == null || note == null || note.isBlank() || note.length() > 500) {
            throw invalid("Supply the revised reading and a short reason.");
        }
        Meter meter = directory.meter(id);
        checkInput(input);
        if (!vee(meter, input).isEmpty()) {
            throw invalid("The revised reading still fails data quality checks.");
        }
        Reading old = store.readings(id, input.start(), input.start().plusSeconds(3600)).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Reading was not found."));
        var result = new Reading(input.start(), input.importKwh(), input.exportKwh(), input.voltage(),
                input.powerFactor(), "MANUAL", "VALID");
        store.correct(id, old, result, UUID.fromString(actor.getSubject()), note.strip(),
                json.writeValueAsString(old), json.writeValueAsString(result));
        audit("METER_READING_CORRECTED", "METER", id, actor, request, Map.of("start", input.start().toString()));
        return result;
    }

    Object customerReadings(UUID customer, UUID meter, Instant from, Instant to, String mode) {
        directory.customerMeter(customer, meter);
        validateRange(from, to);
        return switch (mode) {
            case "hourly" -> intervalReadings(meter, from, to);
            case "daily", "monthly" -> {
                if (!from.atZone(BILLING_ZONE).toLocalTime().equals(java.time.LocalTime.MIDNIGHT)
                        || !to.atZone(BILLING_ZONE).toLocalTime().equals(java.time.LocalTime.MIDNIGHT)) {
                    throw invalid("Daily and monthly ranges must begin and end at midnight in Asia/Kolkata.");
                }
                yield store.totals(meter, from, to, mode);
            }
            default -> throw invalid("Select hourly, daily, or monthly granularity.");
        };
    }

    List<Reading> export(UUID customer, UUID meter, Instant from, Instant to) {
        directory.customerMeter(customer, meter);
        return intervalReadings(meter, from, to);
    }

    List<ReadingStore.Anomaly> anomalies(String status, int page, int size) {
        if (page < 0 || size < 1 || size > 100 || page > Integer.MAX_VALUE / size
                || (status != null && !status.matches(ANOMALY_STATUS))) {
            throw invalid("The status, page, or size is invalid.");
        }
        return store.anomalies(status, size, page * size);
    }

    @Transactional
    ReadingStore.Anomaly resolve(UUID id, String status, String notes, Jwt actor, RequestContext request) {
        if (!status.matches(ANOMALY_STATUS) || status.equals("OPEN") || (notes != null && notes.length() > 1000)) {
            throw invalid("Select an investigation outcome and use at most 1000 characters in the notes.");
        }
        var anomaly = store.resolve(id, UUID.fromString(actor.getSubject()), status, notes);
        audit("METER_ANOMALY_UPDATED", "METER_ANOMALY", id, actor, request, Map.of("status", status));
        return anomaly;
    }

    private void audit(String action, String type, UUID id, Jwt actor, RequestContext request,
            Map<String, Object> changes) {
        events.publishEvent(AuditAction.record(action, type, id, UUID.fromString(actor.getSubject()),
                actor.getClaimAsString("role"), request, changes));
    }

    private static void validateRange(Instant from, Instant to) {
        if (!from.isBefore(to) || Duration.between(from, to).compareTo(Duration.ofDays(370)) > 0) {
            throw invalid("Use an increasing range of at most 370 days.");
        }
    }

    private static void checkInput(Input value) {
        if (value.start() == null || value.importKwh() == null || value.exportKwh() == null) {
            throw invalid("Supply a timestamp and import and export energy for each reading.");
        }
        var local = value.start().atZone(BILLING_ZONE);
        if (local.getMinute() != 0 || local.getSecond() != 0 || local.getNano() != 0
                || value.start().isAfter(Instant.now())
                || local.toLocalDate().isBefore(LocalDate.of(2000, 1, 1))) {
            throw invalid("Reading timestamps must begin at a past Asia/Kolkata hour.");
        }
        checkDecimal(value.importKwh(), ZERO, new BigDecimal("99999999.9999"), 4);
        checkDecimal(value.exportKwh(), ZERO, new BigDecimal("99999999.9999"), 4);
        if (value.voltage() != null) {
            checkDecimal(value.voltage(), ZERO, new BigDecimal("9999.99"), 2);
        }
        if (value.powerFactor() != null) {
            checkDecimal(value.powerFactor(), ZERO, BigDecimal.ONE, 3);
        }
    }

    private static void checkDecimal(BigDecimal value, BigDecimal min, BigDecimal max, int places) {
        if (value.compareTo(min) < 0 || value.compareTo(max) > 0 || value.stripTrailingZeros().scale() > places) {
            throw invalid("A reading value is outside its supported range or precision.");
        }
    }

    private static List<String> vee(Meter meter, Input input) {
        List<String> reasons = new ArrayList<>();
        if (input.importKwh().compareTo(meter.sanctionedLoadKw().multiply(new BigDecimal("1.5"))) > 0) {
            reasons.add("ABOVE_SANCTIONED_LOAD");
        }
        if (!meter.solarNetMetering() && input.exportKwh().signum() > 0) {
            reasons.add("UNEXPECTED_EXPORT");
        }
        if (input.voltage() != null && (input.voltage().compareTo(BigDecimal.valueOf(180)) < 0
                || input.voltage().compareTo(BigDecimal.valueOf(270)) > 0)) {
            reasons.add("VOLTAGE_OUTSIDE_RANGE");
        }
        if (input.powerFactor() != null && input.powerFactor().compareTo(new BigDecimal("0.70")) < 0) {
            reasons.add("LOW_POWER_FACTOR");
        }
        return reasons;
    }

    private static String anomalyType(String reason) {
        return switch (reason) {
            case "ABOVE_SANCTIONED_LOAD" -> "SPIKE";
            case "UNEXPECTED_EXPORT" -> "TAMPER_SUSPECTED";
            case "VOLTAGE_OUTSIDE_RANGE" -> "VOLTAGE_FAULT";
            default -> "POWER_FACTOR_DROP";
        };
    }

    private static ResponseStatusException invalid(String detail) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, detail);
    }

    static final String ANOMALY_STATUS = "OPEN|INVESTIGATING|CONFIRMED_FAULT|CONFIRMED_TAMPER|FALSE_POSITIVE";
    record Input(@NotNull Instant start, @NotNull BigDecimal importKwh, @NotNull BigDecimal exportKwh,
            BigDecimal voltage, BigDecimal powerFactor, Boolean estimated) {
        Input {
            estimated = Boolean.TRUE.equals(estimated);
        }

        Input(Instant start, BigDecimal importKwh, BigDecimal exportKwh, BigDecimal voltage, BigDecimal powerFactor) {
            this(start, importKwh, exportKwh, voltage, powerFactor, false);
        }
    }
    record Ingestion(int inserted, int duplicates, int flagged) {}
}
