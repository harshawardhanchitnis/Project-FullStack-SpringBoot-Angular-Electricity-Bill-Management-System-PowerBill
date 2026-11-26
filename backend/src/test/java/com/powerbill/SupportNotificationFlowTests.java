package com.powerbill;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class SupportNotificationFlowTests extends IntegrationTestSupport {

    @Test
    void runsTheComplaintLifecycleWithSlaInternalNotesAndCsat() throws Exception {
        String customer = demoToken("customer01");
        String agent = demoToken("support");
        var bills = json(call("GET", "/bills?size=1", null, customer, null));
        String billId = bills.path("content").get(0).path("id").asText();
        var foreignBill = json(call("GET", "/bills?size=1", null, demoToken("customer10"), null))
                .path("content").get(0).path("id").asText();
        var invalid = call("POST", "/complaints", complaint("BILLING", foreignBill), customer, null);
        assertThat(invalid.statusCode()).isEqualTo(404);

        var created = call("POST", "/complaints", complaint("BILLING", billId), customer, null);
        assertThat(created.statusCode()).withFailMessage(created.body()).isEqualTo(201);
        var ticket = json(created).path("complaint");
        String id = ticket.path("id").asText();
        assertThat(ticket.path("priority").asText()).isEqualTo("MEDIUM");
        assertThat(ticket.path("status").asText()).isEqualTo("OPEN");
        assertThat(call("GET", "/complaints/" + id, null, demoToken("customer11"), null).statusCode()).isEqualTo(404);
        assertThat(call("GET", "/ops/complaints/" + id, null, customer, null).statusCode()).isEqualTo(403);

        String agentId = json(call("GET", "/auth/me", null, agent, null)).path("id").asText();
        var assigned = patch(id, agent, Map.of("assignedAgentId", agentId, "version", ticket.path("version").asLong()));
        assertThat(assigned.path("complaint").path("status").asText()).isEqualTo("ASSIGNED");
        var stale = call("PATCH", "/ops/complaints/" + id, Map.of("status", "IN_PROGRESS", "version", 0), agent, null);
        assertThat(stale.statusCode()).isEqualTo(409);

        call("POST", "/ops/complaints/" + id + "/comments", Map.of("body", "Check slab 3 crossing", "internal", true),
                agent, null);
        call("POST", "/ops/complaints/" + id + "/comments", Map.of("body", "We are reviewing your bill.",
                "internal", false), agent, null);
        var visible = json(call("GET", "/complaints/" + id, null, customer, null)).path("comments");
        assertThat(visible.size()).isEqualTo(1);
        assertThat(visible.get(0).path("body").asText()).isEqualTo("We are reviewing your bill.");
        assertThat(json(call("GET", "/ops/complaints/" + id, null, agent, null)).path("comments").size()).isEqualTo(2);

        long version = assigned.path("complaint").path("version").asLong();
        var closed = call("PATCH", "/ops/complaints/" + id, Map.of("status", "CLOSED", "version", version), agent, null);
        assertThat(closed.statusCode()).isEqualTo(409);
        var missingSummary = call("PATCH", "/ops/complaints/" + id, Map.of("status", "RESOLVED", "version", version),
                agent, null);
        assertThat(missingSummary.statusCode()).isEqualTo(400);
        var resolved = patch(id, agent, Map.of("status", "RESOLVED", "version", version,
                "resolutionSummary", "Usage crossed slab 3 during the heat wave. Bill is correct."));
        assertThat(resolved.path("complaint").path("status").asText()).isEqualTo("RESOLVED");
        var rated = call("POST", "/complaints/" + id + "/csat", Map.of("rating", 4, "feedback", "Clear answer"),
                customer, null);
        assertThat(json(rated).path("complaint").path("status").asText()).isEqualTo("CLOSED");
        assertThat(json(rated).path("history").size()).isEqualTo(4);

        assertThat(awaitNotification(customer, "COMPLAINT")).isTrue();
        var queue = json(call("GET", "/ops/complaints?status=OPEN&priority=CRITICAL", null, agent, null));
        assertThat(queue.path("totalElements").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(queue.path("content").get(0).path("slaBreached").asBoolean()).isTrue();
    }

    @Test
    void streamsNotificationsLiveAndAlertsCustomersInAnOutageArea() throws Exception {
        String customer = demoToken("customer15");
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/notifications/stream"))
                .header("Authorization", "Bearer " + customer).header("Accept", "text/event-stream").GET().build();
        var stream = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        var lines = new StringBuilder();
        var reader = CompletableFuture.runAsync(() -> {
            try (InputStream body = stream.get(10, TimeUnit.SECONDS).body();
                    var buffered = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                String line;
                while ((line = buffered.readLine()) != null) {
                    synchronized (lines) {
                        lines.append(line).append('\n');
                    }
                    if (line.contains("Transformer fire")) {
                        return;
                    }
                }
            } catch (Exception ignored) {
                // The stream ends when the test cancels it.
            }
        });
        Thread.sleep(500);
        var outage = call("POST", "/ops/outages", Map.of("regionZone", "Mumbai", "city", "Mumbai",
                "affectedPincodes", List.of("400001"), "title", "Transformer fire at Fort substation",
                "description", "Crews are isolating the faulty transformer.", "severity", "EMERGENCY"),
                demoToken("field"), null);
        assertThat(outage.statusCode()).withFailMessage(outage.body()).isEqualTo(201);
        reader.get(15, TimeUnit.SECONDS);
        synchronized (lines) {
            assertThat(lines.toString()).contains("event:ready", "event:notification", "Transformer fire");
        }
        assertThat(json(outage).path("status").asText()).isEqualTo("INVESTIGATING");
        var active = json(call("GET", "/outages/active", null, customer, null));
        assertThat(active.toString()).contains("Transformer fire").doesNotContain("Camp substation");
        assertThat(call("POST", "/ops/outages", Map.of(), customer, null).statusCode()).isEqualTo(403);

        var planned = call("POST", "/ops/outages", Map.of("regionZone", "Pune", "city", "Pune",
                "affectedPincodes", List.of("411002"), "title", "Feeder upgrade on Station Road",
                "description", "Supply pauses while the feeder is upgraded.", "severity", "PLANNED"),
                demoToken("field"), null);
        assertThat(planned.statusCode()).withFailMessage(planned.body()).isEqualTo(201);
        assertThat(json(planned).path("status").asText()).isEqualTo("SCHEDULED");
    }

    @Test
    void letsOnlySuperAdminsManageStaffAccessAndRevokesChangedSessions() throws Exception {
        String admin = demoToken("admin");
        assertThat(call("GET", "/ops/users", null, demoToken("field"), null).statusCode()).isEqualTo(403);
        String email = "agent-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
        var created = call("POST", "/ops/users", Map.of("email", email, "fullName", "Riya Sharma",
                "mobileNumber", "9812345678", "role", "SUPPORT_AGENT", "temporaryPassword", CUSTOMER_PASSWORD), admin, null);
        assertThat(created.statusCode()).withFailMessage(created.body()).isEqualTo(201);
        assertThat(created.body()).doesNotContain("password");
        String staffToken = token(login(email, CUSTOMER_PASSWORD));
        assertThat(call("GET", "/ops/complaints", null, staffToken, null).statusCode()).isEqualTo(200);
        var user = json(created);
        var disabled = call("PATCH", "/ops/users/" + user.path("id").asText(), Map.of("status", "DISABLED",
                "sessionVersion", user.path("sessionVersion").asInt()), admin, null);
        assertThat(json(disabled).path("status").asText()).isEqualTo("DISABLED");
        assertThat(call("GET", "/ops/complaints", null, staffToken, null).statusCode()).isEqualTo(401);
        var staff = json(call("GET", "/ops/staff", null, demoToken("support"), null));
        assertThat(staff.toString()).contains("support@demo.powerbill.invalid").doesNotContain(email);
    }

    @Test
    void servesConsumerAndExecutiveAnalyticsWithAPricedForecast() throws Exception {
        String customer = demoToken("customer14");
        var overview = call("GET", "/analytics/consumer/overview", null, customer, null);
        assertThat(overview.statusCode()).withFailMessage(overview.body()).isEqualTo(200);
        var data = json(overview);
        assertThat(data.path("monthly").size()).isGreaterThanOrEqualTo(6);
        assertThat(data.path("hourlyProfile").size()).isEqualTo(24);
        assertThat(data.path("slab").path("slabs").size()).isEqualTo(4);
        String meter = data.path("meter").path("id").asText();
        var forecast = json(call("GET", "/ai/forecast/" + meter, null, customer, null));
        var bill = forecast.path("projectedBillInr");
        assertThat(bill.path("p10").decimalValue()).isLessThanOrEqualTo(bill.path("p50").decimalValue());
        assertThat(bill.path("p50").decimalValue()).isLessThanOrEqualTo(bill.path("p90").decimalValue());
        assertThat(forecast.path("source").asText()).isEqualTo("fallback");
        assertThat(call("GET", "/ai/forecast/" + meter, null, demoToken("customer13"), null).statusCode())
                .isEqualTo(404);

        var executive = call("GET", "/ops/analytics/executive", null, demoToken("billing"), null);
        assertThat(executive.statusCode()).withFailMessage(executive.body()).isEqualTo(200);
        var kpis = json(executive);
        assertThat(kpis.path("revenue").size()).isEqualTo(12);
        assertThat(kpis.path("aging").size()).isEqualTo(5);
        assertThat(kpis.path("collectionEfficiencyPct").decimalValue().signum()).isPositive();
        assertThat(call("GET", "/ops/analytics/executive", null, customer, null).statusCode()).isEqualTo(403);
    }

    private Map<String, Object> complaint(String type, String billId) {
        return Map.of("complaintType", type, "subject", "Bill higher than expected",
                "description", "My bill increased sharply this month without any change in appliances.", "billId", billId);
    }

    private JsonNode patch(String id, String token, Map<String, Object> body) throws Exception {
        var response = call("PATCH", "/ops/complaints/" + id, body, token, null);
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        return json(response);
    }

    private boolean awaitNotification(String token, String category) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            var inbox = json(call("GET", "/notifications?limit=100", null, token, null));
            for (JsonNode item : inbox.path("items")) {
                if (item.path("category").asText().equals(category)) {
                    return true;
                }
            }
            Thread.sleep(250);
        }
        return false;
    }
}
