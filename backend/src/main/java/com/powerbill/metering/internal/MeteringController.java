package com.powerbill.metering.internal;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.powerbill.common.RequestContext;
import com.powerbill.metering.MeterUsage.Reading;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1")
class MeteringController {
    private static final String STAFF = "hasAnyRole('BILLING_OFFICER','FIELD_ENGINEER','SUPER_ADMIN')";
    private static final String CUSTOMER = "hasRole('CUSTOMER')";
    private static final String CSV_HEADER = "start,import_kwh,export_kwh,voltage,power_factor";
    private final MeteringService metering;
    private final GreenButton greenButton;
    private final AmiSimulator simulator;
    private final ObjectMapper json;

    MeteringController(MeteringService metering, GreenButton greenButton, AmiSimulator simulator, ObjectMapper json) {
        this.metering = metering;
        this.greenButton = greenButton;
        this.simulator = simulator;
        this.json = json;
    }

    @GetMapping("/meters/{id}/readings")
    @PreAuthorize(CUSTOMER)
    Object readings(@PathVariable UUID id, @RequestParam OffsetDateTime from, @RequestParam OffsetDateTime to,
            @RequestParam(defaultValue = "daily") String granularity, @AuthenticationPrincipal Jwt jwt) {
        return metering.customerReadings(user(jwt), id, from.toInstant(), to.toInstant(), granularity);
    }

    @PostMapping(path = "/meters/{id}/readings", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(STAFF)
    MeteringService.Ingestion ingest(@PathVariable UUID id, @RequestBody JsonNode raw,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        List<MeteringService.Input> rows = new ArrayList<>();
        try {
            if (raw.isArray()) {
                if (raw.size() > 2000) {
                    throw badInput("A batch can contain at most 2000 readings.");
                }
                for (JsonNode item : raw) {
                    rows.add(row(item));
                }
            } else {
                rows.add(row(raw));
            }
        } catch (JacksonException error) {
            throw badInput("Supply one reading or an array of hourly readings.");
        }
        return metering.ingest(id, rows, "AMI_STREAM", jwt, RequestContext.from(request));
    }

    private MeteringService.Input row(JsonNode value) {
        if (!value.isObject()) {
            throw badInput("A reading must be a JSON object.");
        }
        return json.treeToValue(value, MeteringService.Input.class);
    }

    @PostMapping(path = "/meters/{id}/import/csv", consumes = "text/csv")
    @PreAuthorize(STAFF)
    MeteringService.Ingestion csvImport(@PathVariable UUID id, @RequestBody String csv,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        if (csv.length() > 500_000) {
            throw badInput("The CSV exceeds the upload limit.");
        }
        String[] lines = csv.split("\\R");
        if (lines.length < 2 || lines.length > 2001 || !lines[0].equals(CSV_HEADER)) {
            throw badInput("Use the GridIQ CSV header and at most 2000 hourly records.");
        }
        List<MeteringService.Input> readings = new ArrayList<>();
        try {
            for (int i = 1; i < lines.length; i++) {
                String[] fields = lines[i].split(",", -1);
                if (fields.length != 5) {
                    throw badInput("CSV rows must contain five fields.");
                }
                readings.add(new MeteringService.Input(Instant.parse(fields[0]), new BigDecimal(fields[1]),
                        new BigDecimal(fields[2]), nullableDecimal(fields[3]), nullableDecimal(fields[4])));
            }
        } catch (IllegalArgumentException | java.time.DateTimeException invalid) {
            throw badInput("The CSV contains an invalid timestamp or number.");
        }
        return metering.ingest(id, readings, "CSV", jwt, RequestContext.from(request));
    }

    @PostMapping("/meters/{id}/readings/estimate")
    @PreAuthorize(STAFF)
    Reading estimate(@PathVariable UUID id, @RequestBody Estimation input,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        return metering.estimate(id, input.start(), jwt, RequestContext.from(request));
    }

    @PatchMapping("/meters/{id}/readings")
    @PreAuthorize(STAFF)
    Reading revise(@PathVariable UUID id, @RequestBody Revision input,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        return metering.correct(id, input.reading(), input.reason(), jwt, RequestContext.from(request));
    }

    @PostMapping(path = "/meters/{id}/import/green-button", consumes = {"application/atom+xml", "application/xml"})
    @PreAuthorize(STAFF)
    MeteringService.Ingestion espiImport(@PathVariable UUID id, @RequestBody String xml,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        return metering.ingest(id, greenButton.parse(xml), "GREEN_BUTTON", jwt, RequestContext.from(request));
    }

    @GetMapping("/meters/{id}/export/csv")
    @PreAuthorize(CUSTOMER)
    ResponseEntity<String> csv(@PathVariable UUID id, @RequestParam OffsetDateTime from,
            @RequestParam OffsetDateTime to, @AuthenticationPrincipal Jwt jwt) {
        var readings = metering.export(user(jwt), id, from.toInstant(), to.toInstant());
        var csv = new StringBuilder(CSV_HEADER).append('\n');
        for (var reading : readings) {
            if (!reading.quality().equals("FLAGGED")) {
                csv.append(reading.start()).append(',').append(reading.importKwh().toPlainString()).append(',')
                        .append(reading.exportKwh().toPlainString()).append(',').append(text(reading.voltage()))
                        .append(',').append(text(reading.powerFactor())).append('\n');
            }
        }
        return download("usage.csv", "text/csv", csv.toString());
    }

    @GetMapping("/meters/{id}/export/green-button")
    @PreAuthorize(CUSTOMER)
    ResponseEntity<String> espi(@PathVariable UUID id, @RequestParam OffsetDateTime from,
            @RequestParam OffsetDateTime to, @AuthenticationPrincipal Jwt jwt) {
        return download("usage.xml", "application/atom+xml",
                greenButton.export(metering.export(user(jwt), id, from.toInstant(), to.toInstant())));
    }

    @GetMapping("/ops/metering/meters")
    @PreAuthorize(STAFF)
    List<com.powerbill.consumer.MeterDirectory.Meter> searchMeters(@RequestParam(defaultValue = "") String q) {
        if (q.strip().length() < 2 || q.length() > 40) {
            throw badInput("Type at least two characters of a meter serial.");
        }
        return metering.searchMeters(q.strip(), 20);
    }

    @GetMapping("/ops/metering/meters/{id}/intervals")
    @PreAuthorize(STAFF)
    MeteringService.Intervals intervals(@PathVariable UUID id, @RequestParam OffsetDateTime from,
            @RequestParam OffsetDateTime to) {
        return metering.staffIntervals(id, from.toInstant(), to.toInstant());
    }

    @PostMapping("/ops/metering/simulate")
    @PreAuthorize(STAFF)
    Map<String, Integer> simulate(@RequestBody Simulation request) {
        if (request.meterId() == null || request.months() < 1 || request.months() > 12) {
            throw badInput("Select a demo meter and 1 to 12 months.");
        }
        return Map.of("inserted", simulator.simulate(request.meterId(), request.months()));
    }

    @GetMapping("/ops/anomalies")
    @PreAuthorize(STAFF)
    List<ReadingStore.Anomaly> anomalies(@RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return metering.anomalies(status, page, size);
    }

    @PatchMapping("/ops/anomalies/{id}")
    @PreAuthorize(STAFF)
    ReadingStore.Anomaly resolve(@PathVariable UUID id, @RequestBody Investigation body,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        if (body.status() == null) {
            throw badInput("Select an investigation status.");
        }
        return metering.resolve(id, body.status(), body.notes(), jwt, RequestContext.from(request));
    }

    private static UUID user(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    private static ResponseEntity<String> download(String name, String type, String contents) {
        var contentType = new MediaType(MediaType.parseMediaType(type), StandardCharsets.UTF_8);
        return ResponseEntity.ok().contentType(contentType).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .body(contents);
    }

    private static String text(BigDecimal value) {
        return value == null ? "" : value.toPlainString();
    }

    private static BigDecimal nullableDecimal(String value) {
        return value.isEmpty() ? null : new BigDecimal(value);
    }

    private static ResponseStatusException badInput(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    record Simulation(UUID meterId, int months) {}
    record Estimation(Instant start) {}
    record Revision(MeteringService.Input reading, String reason) {}
    record Investigation(String status, String notes) {}
}
