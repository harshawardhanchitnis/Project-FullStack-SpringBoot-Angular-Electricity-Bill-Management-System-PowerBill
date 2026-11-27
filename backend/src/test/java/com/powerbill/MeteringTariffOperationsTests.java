package com.powerbill;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class MeteringTariffOperationsTests extends IntegrationTestSupport {
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    @Test
    void pricesAMeterWithItsTariffOverrideWhenOneIsSet() throws Exception {
        String customer = demoToken("customer05");
        var meter = json(call("GET", "/consumers/me/meters", null, customer, null)).get(0);
        UUID meterId = UUID.fromString(meter.path("id").asText());
        assertThat(meter.path("connectionType").asText()).isEqualTo("DOMESTIC");
        UUID commercial = db.sql("SELECT id FROM tariff_plans WHERE connection_type='COMMERCIAL' AND is_active "
                + "ORDER BY effective_from DESC LIMIT 1").query(UUID.class).single();
        var input = Map.of("meterId", meterId.toString(),
                "month", java.time.YearMonth.now(INDIA).minusMonths(1).toString(), "loadShiftPct", 0, "rooftopSolarKw", 0);
        try {
            db.sql("UPDATE smart_meters SET tariff_plan_id=? WHERE id=?").params(commercial, meterId).update();
            var priced = call("POST", "/tariffs/simulate", input, customer, null);
            assertThat(priced.statusCode()).withFailMessage(priced.body()).isEqualTo(200);
            assertThat(json(priced).path("tariffPlanId").asText()).isEqualTo(commercial.toString());
        } finally {
            db.sql("UPDATE smart_meters SET tariff_plan_id=NULL WHERE id=?").param(meterId).update();
        }
        var standard = json(call("POST", "/tariffs/simulate", input, customer, null));
        assertThat(standard.path("tariffPlanId").asText()).isNotEqualTo(commercial.toString());
    }

    @Test
    void plantsATrueZeroReadFlatlineOnTheSolarDemoMeter() {
        // The detector reports ZERO_READ only when import and export are both zero for 24+ hours, so the planted
        // flatline must also suppress solar export; otherwise it surfaces as a tamper suspicion instead.
        var flatline = db.sql("""
                SELECT count(*) AS hours, max(m.solar_capacity_kw) AS solar,
                    extract(epoch FROM max(r.recorded_at) - min(r.recorded_at)) / 3600 AS span
                FROM meter_readings r JOIN smart_meters m ON m.id = r.meter_id
                WHERE m.meter_serial = 'M-DEMO-12-1' AND r.import_kwh = 0 AND r.export_kwh = 0
                """).query().singleRow();
        assertThat(((Number) flatline.get("solar")).doubleValue()).isPositive();
        assertThat(((Number) flatline.get("hours")).longValue()).isEqualTo(48);
        assertThat(((Number) flatline.get("span")).doubleValue()).isEqualTo(47.0);
    }

    @Test
    void importsCorrectsEstimatesAndInvestigatesMeterData() throws Exception {
        String email = "vee-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
        var registered = json(call("POST", "/auth/register", registration(email, "9876501234"), null, null));
        String meter = registered.path("meters").get(0).path("id").asText();
        String customer = token(login(email, CUSTOMER_PASSWORD));
        String billing = demoToken("billing");
        String field = demoToken("field");
        String path = "/meters/" + meter;

        var csv = new StringBuilder("start,import_kwh,export_kwh,voltage,power_factor\n");
        for (int day = 1; day <= 3; day++) {
            for (int hour = 9; hour <= 11; hour++) {
                csv.append(at(day, hour).toInstant()).append(",1.2").append(day).append(",0,231.5,0.95\n");
            }
        }
        var imported = send("POST", path + "/import/csv", csv.toString(), "text/csv", billing);
        assertThat(imported.statusCode()).withFailMessage(imported.body()).isEqualTo(200);
        assertThat(json(imported).path("inserted").asInt()).isEqualTo(9);
        assertThat(send("POST", path + "/import/csv", "wrong,header\n", "text/csv", billing).statusCode()).isEqualTo(400);

        String range = "?from=" + at(1, 0).toOffsetDateTime() + "&to=" + at(4, 0).toOffsetDateTime();
        String xml = call("GET", path + "/export/green-button" + range.replace("+", "%2B"), null, customer, null).body();
        assertThat(xml).contains("<espi:IntervalReading>");
        var replay = send("POST", path + "/import/green-button", xml, "application/atom+xml", billing);
        assertThat(replay.statusCode()).withFailMessage(replay.body()).isEqualTo(200);
        assertThat(json(replay).path("duplicates").asInt()).isEqualTo(9);
        assertThat(send("POST", path + "/import/green-button", "<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><x>&e;</x>",
                "application/xml", billing).statusCode()).isEqualTo(400);

        var faulty = Map.of("start", at(4, 9).toInstant().toString(), "importKwh", 1.1, "exportKwh", 0, "voltage", 300);
        var flagged = call("POST", path + "/readings", faulty, billing, null);
        assertThat(json(flagged).path("flagged").asInt()).isEqualTo(1);

        // Staff tools: find the meter by serial, then see its flagged and missing hours for the day.
        String serial = registered.path("meters").get(0).path("meterSerial").asText();
        var found = call("GET", "/ops/metering/meters?q=" + serial.substring(2), null, field, null);
        assertThat(found.statusCode()).withFailMessage(found.body()).isEqualTo(200);
        assertThat(found.body()).contains(meter);
        assertThat(call("GET", "/ops/metering/meters?q=" + serial, null, customer, null).statusCode()).isEqualTo(403);
        String day = "?from=" + at(4, 0).toOffsetDateTime() + "&to=" + at(5, 0).toOffsetDateTime();
        var intervals = json(call("GET", "/ops/metering/meters/" + meter + "/intervals" + day.replace("+", "%2B"),
                null, field, null));
        assertThat(intervals.path("flaggedCount").asInt()).isEqualTo(1);
        assertThat(intervals.path("readings").size()).isEqualTo(1);
        assertThat(intervals.path("missing").toString()).contains(at(4, 10).toInstant().toString());
        assertThat(call("GET", "/ops/metering/meters/" + meter + "/intervals?from=" + at(1, 0).toOffsetDateTime()
                .toString().replace("+", "%2B") + "&to=" + at(12, 0).toOffsetDateTime().toString().replace("+", "%2B"),
                null, field, null).statusCode()).isEqualTo(400);
        var corrected = call("PATCH", path + "/readings", Map.of("reason", "Check meter confirmed 230 V",
                "reading", Map.of("start", faulty.get("start"), "importKwh", 1.1, "exportKwh", 0, "voltage", 230)),
                billing, null);
        assertThat(corrected.statusCode()).withFailMessage(corrected.body()).isEqualTo(200);
        assertThat(json(corrected).path("quality").asText()).isEqualTo("VALID");

        var estimated = call("POST", path + "/readings/estimate", Map.of("start", at(4, 10).toInstant().toString()),
                billing, null);
        assertThat(estimated.statusCode()).withFailMessage(estimated.body()).isEqualTo(200);
        assertThat(json(estimated).path("quality").asText()).isEqualTo("ESTIMATED");
        assertThat(json(estimated).path("importKwh").decimalValue()).isEqualByComparingTo("1.2200");

        JsonNode anomaly = null;
        for (JsonNode item : json(call("GET", "/ops/anomalies?status=OPEN&size=100", null, field, null))) {
            if (item.path("meterId").asText().equals(meter) && item.path("anomalyType").asText().equals("VOLTAGE_FAULT")) {
                anomaly = item;
            }
        }
        assertThat(anomaly).isNotNull();
        assertThat(anomaly.path("reasonCodes").asText()).contains("VOLTAGE_OUTSIDE_RANGE");
        var resolved = call("PATCH", "/ops/anomalies/" + anomaly.path("id").asText(),
                Map.of("status", "CONFIRMED_FAULT", "notes", "Loose neutral fixed on site"), field, null);
        assertThat(json(resolved).path("status").asText()).isEqualTo("CONFIRMED_FAULT");
        assertThat(call("POST", "/ops/metering/simulate", Map.of("meterId", meter, "months", 1), field, null).statusCode())
                .isEqualTo(409);
        assertThat(call("GET", "/ops/anomalies", null, customer, null).statusCode()).isEqualTo(403);
    }

    @Test
    void draftsValidatesAndActivatesAFutureTariffVersion() throws Exception {
        String billing = demoToken("billing");
        JsonNode current = null;
        for (JsonNode plan : json(call("GET", "/ops/tariffs", null, billing, null))) {
            if (plan.path("code").asText().equals("DEMO-INDUSTRIAL") && plan.path("active").asBoolean()) {
                current = plan;
            }
        }
        assertThat(current).isNotNull();
        LocalDate start = LocalDate.now(INDIA).plusMonths(2).withDayOfMonth(1);
        var draft = new java.util.LinkedHashMap<String, Object>();
        for (String field : List.of("code", "connectionType", "fixedChargePerKw", "facPerKwh", "electricityDutyPct",
                "gstPct", "solarExportRatePerKwh", "solarPolicy", "lateFeeDailyPct", "lateFeeCapPct", "graceDays")) {
            draft.put(field, json.treeToValue(current.path(field), Object.class));
        }
        draft.put("name", "Industrial tariff FY27");
        draft.put("effectiveFrom", start.toString());
        draft.put("slabs", json.treeToValue(current.path("slabs"), Object.class));
        draft.put("windows", json.treeToValue(current.path("windows"), Object.class));

        var broken = new java.util.LinkedHashMap<>(draft);
        broken.put("slabs", List.of(Map.of("fromKwh", 10, "ratePerKwh", 5)));
        assertThat(call("POST", "/ops/tariffs", broken, billing, null).statusCode()).isEqualTo(400);

        var created = call("POST", "/ops/tariffs", draft, billing, null);
        assertThat(created.statusCode()).withFailMessage(created.body()).isEqualTo(201);
        var version = json(created);
        assertThat(version.path("version").asInt()).isEqualTo(current.path("version").asInt() + 1);
        assertThat(version.path("active").asBoolean()).isFalse();

        draft.put("name", "Industrial tariff FY27 (revised)");
        var updated = call("PUT", "/ops/tariffs/" + version.path("id").asText(), draft, billing, null);
        assertThat(json(updated).path("name").asText()).isEqualTo("Industrial tariff FY27 (revised)");
        var activated = call("PATCH", "/ops/tariffs/" + version.path("id").asText() + "/activate", null, billing, null);
        assertThat(activated.statusCode()).withFailMessage(activated.body()).isEqualTo(200);
        assertThat(json(activated).path("active").asBoolean()).isTrue();
        assertThat(call("PUT", "/ops/tariffs/" + version.path("id").asText(), draft, billing, null).statusCode())
                .isEqualTo(409);

        var plans = json(call("GET", "/tariffs", null, demoToken("customer13"), null));
        var codes = new ArrayList<String>();
        plans.forEach(plan -> codes.add(plan.path("code").asText() + ":" + plan.path("version").asInt()));
        assertThat(codes).contains("DEMO-INDUSTRIAL:" + current.path("version").asInt());
        assertThat(call("POST", "/ops/tariffs", draft, demoToken("support"), null).statusCode()).isEqualTo(403);
    }

    @Test
    void reportsTheCopilotAsUnavailableWhenTheMlServiceIsOff() throws Exception {
        var response = call("POST", "/ai/copilot/chat", Map.of("message", "Why is my bill high?"),
                demoToken("customer14"), null);
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(json(response).path("detail").asText()).contains("assistant is unavailable");
    }

    private ZonedDateTime at(int day, int hour) {
        return LocalDate.of(2025, 6, day).atTime(hour, 0).atZone(INDIA);
    }

    private HttpResponse<String> send(String method, String path, String body, String type, String bearer)
            throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + bearer)
                .header("Content-Type", type).method(method, HttpRequest.BodyPublishers.ofString(body)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
