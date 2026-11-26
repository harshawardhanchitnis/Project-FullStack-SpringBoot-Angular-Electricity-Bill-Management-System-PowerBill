package com.powerbill;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class IdentityFlowTests extends IntegrationTestSupport {

    @Test
    void registersOwnsAndUpdatesOnlyTheAuthenticatedCustomerAccount() throws Exception {
        String email = "ananya@example.test";
        var bad = call("POST", "/auth/register", registration(email, "123"), null, null);
        assertThat(bad.statusCode()).isEqualTo(400);
        assertThat(json(bad).path("fieldErrors").has("mobileNumber")).isTrue();

        var registered = call("POST", "/auth/register", registration(email, "9876543210"), null, null);
        assertThat(registered.statusCode()).withFailMessage(registered.body()).isEqualTo(201);
        assertThat(registered.body()).doesNotContain(CUSTOMER_PASSWORD, "passwordHash", "password_hash");
        var data = json(registered);
        assertThat(data.path("account").path("accountType").asText()).isEqualTo("RESIDENTIAL");
        assertThat(data.path("premises").size()).isEqualTo(1);
        assertThat(data.path("meters").size()).isEqualTo(1);
        String meter = data.path("meters").get(0).path("id").asText();
        String owner = token(login(email, CUSTOMER_PASSWORD));
        String other = token(login("customer01@demo.powerbill.invalid", DEMO_PASSWORD));

        assertThat(call("GET", "/meters/" + meter, null, owner, null).statusCode()).isEqualTo(200);
        assertThat(call("GET", "/meters/" + meter, null, other, null).statusCode()).isEqualTo(404);
        assertThat(call("GET", "/meters/" + meter, null, null, null).statusCode()).isEqualTo(401);
        assertThat(call("GET", "/ops/consumers", null, owner, null).statusCode()).isEqualTo(403);
        assertThat(json(call("GET", "/consumers/me/meters", null, owner, null)).size()).isEqualTo(1);

        var update = Map.of("billingEmail", "billing@example.test", "mobileNumber", "9876543210",
                "monthlyBudgetInr", 1300.25, "version", 0);
        assertThat(call("PUT", "/consumers/me", update, owner, null).statusCode()).isEqualTo(200);
        assertThat(call("PUT", "/consumers/me", update, owner, null).statusCode()).isEqualTo(409);

        String hash = db.sql("SELECT password_hash FROM users WHERE email=?").param(email).query(String.class).single();
        assertThat(hash).startsWith("$argon2id$").doesNotContain(CUSTOMER_PASSWORD);
        String confirmation = mailToken(email, "Welcome to PowerBill GridIQ");
        assertThat(call("POST", "/auth/verify-email", Map.of("token", confirmation), null, null).statusCode())
                .isEqualTo(204);
        assertThat(json(call("GET", "/auth/me", null, owner, null)).path("emailVerified").asBoolean()).isTrue();
    }

    @Test
    void rotatesCookiesAndRevokesTheSessionFamilyOnReuse() throws Exception {
        var initial = login("customer02@demo.powerbill.invalid", DEMO_PASSWORD);
        String originalCookie = cookie(initial);
        assertThat(initial.headers().firstValue("Set-Cookie").orElseThrow()).contains("HttpOnly", "SameSite=Lax");
        assertThat(initial.body()).doesNotContain("refreshToken", originalCookie.substring(originalCookie.indexOf('=') + 1));
        var replacement = call("POST", "/auth/refresh", null, null, originalCookie);
        assertThat(replacement.statusCode()).withFailMessage(replacement.body()).isEqualTo(200);
        String newCookie = cookie(replacement);
        assertThat(newCookie).isNotEqualTo(originalCookie);

        var reused = call("POST", "/auth/refresh", null, null, originalCookie);
        assertThat(reused.statusCode()).isEqualTo(401);
        assertThat(call("POST", "/auth/refresh", null, null, newCookie).statusCode()).isEqualTo(401);
        assertThat(call("GET", "/auth/me", null, token(replacement), null).statusCode()).isEqualTo(401);
        assertThat(call("POST", "/auth/logout", null, null, null).statusCode()).isEqualTo(403);

        var fresh = login("customer02@demo.powerbill.invalid", DEMO_PASSWORD);
        assertThat(call("POST", "/auth/logout", null, null, cookie(fresh)).statusCode()).isEqualTo(204);
        assertThat(call("GET", "/auth/me", null, token(fresh), null).statusCode()).isEqualTo(401);
    }

    @Test
    void filtersStaffDirectoryAndAuditsMutationsWithRoleChecks() throws Exception {
        String support = token(login("support@demo.powerbill.invalid", DEMO_PASSWORD));
        String field = token(login("field@demo.powerbill.invalid", DEMO_PASSWORD));
        String admin = token(login("admin@demo.powerbill.invalid", DEMO_PASSWORD));
        var search = call("GET", "/ops/consumers?q=demo&status=ACTIVE&type=COMMERCIAL&size=2",
                null, support, null);
        assertThat(search.statusCode()).withFailMessage(search.body()).isEqualTo(200);
        var page = json(search);
        assertThat(page.path("totalElements").asInt()).isEqualTo(4);
        assertThat(page.path("content").size()).isEqualTo(2);
        assertThat(call("GET", "/ops/consumers", null, field, null).statusCode()).isEqualTo(403);
        assertThat(call("GET", "/ops/audit", null, support, null).statusCode()).isEqualTo(403);

        var account = page.path("content").get(0);
        String path = "/ops/consumers/" + account.path("id").asText();
        var changed = call("PATCH", path + "/status",
                Map.of("status", "SUSPENDED", "version", account.path("version").asLong()), support, null);
        assertThat(changed.statusCode()).withFailMessage(changed.body()).isEqualTo(200);
        assertThat(json(changed).path("status").asText()).isEqualTo("SUSPENDED");
        for (int i = 0; i < 25; i++) {
            var audit = call("GET", "/ops/audit?size=100", null, admin, null);
            assertThat(audit.statusCode()).isEqualTo(200);
            if (audit.body().contains("CONSUMER_STATUS_CHANGED")) {
                assertThat(audit.body()).contains("phase2-integration");
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("The audit event never reached the audit log");
    }

    @Test
    void ingestsHourlyDataAndPricesAFullMeterHistory() throws Exception {
        String email = "metering@example.test";
        var registration = call("POST", "/auth/register", registration(email, "9876543210"), null, null);
        assertThat(registration.statusCode()).withFailMessage(registration.body()).isEqualTo(201);
        String testMeter = json(registration).path("meters").get(0).path("id").asText();
        String customer = token(login(email, CUSTOMER_PASSWORD));
        String billing = token(login("billing@demo.powerbill.invalid", DEMO_PASSWORD));
        String other = token(login("customer05@demo.powerbill.invalid", DEMO_PASSWORD));
        var start = LocalDate.of(2025, 5, 1).atStartOfDay(ZoneId.of("Asia/Kolkata")).toInstant();
        var end = start.plus(Duration.ofDays(1));
        var readings = java.util.List.of(
                Map.of("start", start.toString(), "importKwh", 1.25, "exportKwh", 0),
                Map.of("start", start.plusSeconds(3600).toString(), "importKwh", 2.75, "exportKwh", 0),
                Map.of("start", end.minusSeconds(3600).toString(), "importKwh", 15, "exportKwh", 0),
                Map.of("start", end.toString(), "importKwh", 2, "exportKwh", 0));
        String path = "/meters/" + testMeter;
        assertThat(call("POST", path + "/readings", readings, customer, null).statusCode()).isEqualTo(403);
        var ingest = call("POST", path + "/readings", readings, billing, null);
        assertThat(ingest.statusCode()).withFailMessage(ingest.body()).isEqualTo(200);
        assertThat(json(ingest).path("inserted").asInt()).isEqualTo(4);
        assertThat(json(ingest).path("flagged").asInt()).isEqualTo(1);
        assertThat(json(call("POST", path + "/readings", readings, billing, null)).path("duplicates").asInt())
                .isEqualTo(4);

        String range = "?from=" + start + "&to=" + end;
        var daily = call("GET", path + "/readings" + range + "&granularity=daily", null, customer, null);
        assertThat(daily.statusCode()).withFailMessage(daily.body()).isEqualTo(200);
        JsonNode totals = json(daily).get(0);
        assertThat(totals.path("importKwh").decimalValue()).isEqualByComparingTo("4");
        assertThat(totals.path("flaggedIntervals").asInt()).isEqualTo(1);
        var xml = call("GET", path + "/export/green-button" + range, null, customer, null);
        assertThat(xml.statusCode()).isEqualTo(200);
        assertThat(xml.body()).contains("http://naesb.org/espi", "<espi:value>12500</espi:value>")
                .doesNotContain(testMeter, "<espi:value>150000</espi:value>");
        assertThat(call("GET", path + "/export/csv" + range, null, other, null).statusCode()).isEqualTo(404);
        var csv = call("GET", path + "/export/csv" + range, null, customer, null);
        assertThat(csv.body().lines().count()).isEqualTo(3);
        assertThat(call("GET", "/ops/anomalies?status=OPEN", null, billing, null).body()).contains("SPIKE");

        var meters = json(call("GET", "/consumers/me/meters", null, other, null));
        var demo = meters.get(0).path("id").asText();
        var month = YearMonth.now(ZoneId.of("Asia/Kolkata")).minusMonths(1).toString();
        var input = Map.of("meterId", demo, "month", month, "loadShiftPct", 30, "rooftopSolarKw", 1.5,
                "installedCostInr", 50000);
        var simulated = call("POST", "/tariffs/simulate", input, other, null);
        assertThat(simulated.statusCode()).withFailMessage(simulated.body()).isEqualTo(200);
        var quote = json(simulated);
        assertThat(quote.path("projected").path("totalInr").decimalValue())
                .isLessThan(quote.path("baseline").path("totalInr").decimalValue());
        assertThat(quote.path("annualizedSavingsInr").decimalValue()).isGreaterThan(BigDecimal.ZERO);
        assertThat(call("POST", "/tariffs/simulate", input, customer, null).statusCode()).isEqualTo(404);
    }

    @Test
    void limitsRepeatedLoginAttemptsAndKeepsTheResetResponseGeneric() throws Exception {
        String email = "customer03@demo.powerbill.invalid";
        for (int i = 0; i < 5; i++) {
            var rejected = call("POST", "/auth/login", Map.of("email", email, "password", "incorrect"), null, null);
            assertThat(rejected.statusCode()).withFailMessage(rejected.body()).isEqualTo(401);
        }
        var throttled = call("POST", "/auth/login", Map.of("email", email, "password", DEMO_PASSWORD), null, null);
        assertThat(throttled.statusCode()).withFailMessage(throttled.body()).isEqualTo(429);
        assertThat(throttled.headers().firstValue("Retry-After")).isPresent();

        String resetEmail = "customer04@demo.powerbill.invalid";
        var old = login(resetEmail, DEMO_PASSWORD);
        for (String target : new String[]{ resetEmail, "unregistered@example.test" }) {
            var requested = call("POST", "/auth/forgot-password", Map.of("email", target), null, null);
            assertThat(requested.statusCode()).isEqualTo(202);
            assertThat(requested.body()).isEmpty();
        }
        var reset = Map.of("token", mailToken(resetEmail, "Reset your GridIQ password"), "password", CUSTOMER_PASSWORD);
        assertThat(call("POST", "/auth/reset-password", reset, null, null).statusCode()).isEqualTo(204);
        assertThat(call("POST", "/auth/reset-password", reset, null, null).statusCode()).isEqualTo(400);
        assertThat(call("GET", "/auth/me", null, token(old), null).statusCode()).isEqualTo(401);
        assertThat(login(resetEmail, CUSTOMER_PASSWORD).statusCode()).isEqualTo(200);
    }
}
