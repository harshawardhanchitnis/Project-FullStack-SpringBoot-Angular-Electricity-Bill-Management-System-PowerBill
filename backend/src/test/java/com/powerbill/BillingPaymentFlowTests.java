package com.powerbill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class BillingPaymentFlowTests extends IntegrationTestSupport {

    @Test
    void issuesItemizedImmutableInvoicesFromSimulatedTelemetry() throws Exception {
        String customer = demoToken("customer06");
        var page = json(call("GET", "/bills?size=50", null, customer, null));
        assertThat(page.path("totalElements").asInt()).isGreaterThanOrEqualTo(6);
        JsonNode first = page.path("content").get(0);
        var detail = json(call("GET", "/bills/" + first.path("id").asText(), null, customer, null));
        BigDecimal lines = BigDecimal.ZERO;
        for (JsonNode line : detail.path("lineItems")) {
            lines = lines.add(line.path("amount").decimalValue());
        }
        assertThat(lines).isEqualByComparingTo(detail.path("bill").path("totalAmount").decimalValue());
        assertThat(detail.path("lineItems").toString()).contains("SLAB", "FIXED", "DUTY");

        var pdf = bytes("/bills/" + first.path("id").asText() + "/pdf", customer);
        assertThat(pdf.statusCode()).isEqualTo(200);
        assertThat(pdf.headers().firstValue("Content-Type").orElseThrow()).contains("application/pdf");
        assertThat(new String(pdf.body(), 0, 5)).isEqualTo("%PDF-");
        assertThat(bytes("/bills/" + first.path("id").asText() + "/pdf", demoToken("customer07")).statusCode())
                .isEqualTo(404);

        UUID billId = UUID.fromString(first.path("id").asText());
        assertThatThrownBy(() -> db.sql("UPDATE bills SET total_amount=total_amount+1 WHERE id=?").param(billId).update())
                .hasMessageContaining("immutable");
        assertThat(db.sql("""
                SELECT count(*) FROM (SELECT journal_id FROM ledger_entries GROUP BY journal_id
                HAVING sum(CASE WHEN entry_type='DEBIT' THEN amount ELSE -amount END) <> 0) unbalanced
                """).query(Long.class).single()).isZero();
        assertThat(call("GET", "/ops/bills", null, customer, null).statusCode()).isEqualTo(403);
    }

    @Test
    void checksOutIdempotentlyAndSettlesConcurrentConfirmationsOnce() throws Exception {
        String customer = demoToken("customer08");
        var open = openBills(customer);
        assertThat(open).isNotEmpty();
        String billId = open.getFirst().path("id").asText();
        var request = Map.of("billIds", List.of(billId), "paymentMethod", "UPI");
        assertThat(call("POST", "/payments/checkout", request, customer, null).statusCode()).isEqualTo(400);

        String key = UUID.randomUUID().toString();
        var created = checkout(customer, request, key);
        assertThat(created.statusCode()).withFailMessage(created.body()).isEqualTo(201);
        var payment = json(created);
        assertThat(payment.path("amount").decimalValue())
                .isEqualByComparingTo(open.getFirst().path("totalDue").decimalValue());
        var replay = checkout(customer, request, key);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(json(replay).path("id").asText()).isEqualTo(payment.path("id").asText());
        assertThat(checkout(customer, Map.of("billIds", List.of(billId), "paymentMethod", "NET_BANKING"), key)
                .statusCode()).isEqualTo(422);
        assertThat(checkout(customer, request, UUID.randomUUID().toString()).statusCode()).isEqualTo(409);

        String confirm = "/payments/sandbox/" + payment.path("id").asText() + "/confirm";
        var pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                Callable<Integer> task = () -> call("POST", confirm, Map.of("outcome", "SUCCEEDED"), customer, null)
                        .statusCode();
                results.add(pool.submit(task));
            }
            for (var result : results) {
                assertThat(result.get()).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }
        UUID paymentId = UUID.fromString(payment.path("id").asText());
        assertThat(db.sql("SELECT count(*) FROM ledger_journals WHERE reference_type='PAYMENT' AND reference_id=?")
                .param(paymentId).query(Long.class).single()).isEqualTo(1);
        // Every confirmation is a distinct signed delivery; only the first one settles the bills.
        assertThat(db.sql("SELECT count(*) FROM payment_webhook_events WHERE payment_transaction_id=?")
                .param(paymentId).query(Long.class).single()).isBetween(1L, 4L);
        assertThat(db.sql("SELECT count(*) FROM ledger_journals WHERE reference_type IN ('PAYMENT','LATE_FEE') "
                + "AND reference_id IN (?, ?)").params(paymentId, UUID.fromString(billId)).query(Long.class).single())
                .isLessThanOrEqualTo(2);
        var settled = json(call("GET", "/payments/" + paymentId, null, customer, null));
        assertThat(settled.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(settled.path("webhookVerified").asBoolean()).isTrue();
        assertThat(json(call("GET", "/bills/" + billId, null, customer, null)).path("bill").path("status").asText())
                .isEqualTo("PAID");
        var receipt = bytes("/payments/" + paymentId + "/receipt/pdf", customer);
        assertThat(new String(receipt.body(), 0, 5)).isEqualTo("%PDF-");

        var forged = call("POST", "/webhooks/payments/sandbox",
                Map.of("id", "evt_forged", "type", "payment.succeeded", "data", Map.of("transactionId", paymentId)),
                null, null, Map.of("X-GridIQ-Signature", "t=1,v1=00"));
        assertThat(forged.statusCode()).isEqualTo(400);
        var ledger = json(call("GET", "/consumers/me/ledger", null, customer, null));
        assertThat(ledger.get(0).path("referenceType").asText()).isEqualTo("PAYMENT");
    }

    @Test
    void rejectsAMultiBillPaymentAtomicallyWhenABillChangesAfterCheckout() throws Exception {
        String customer = demoToken("customer09");
        String billing = demoToken("billing");
        var open = openBills(customer);
        assertThat(open.size()).withFailMessage("customer09 needs two open bills").isGreaterThanOrEqualTo(2);
        var ids = List.of(open.get(0).path("id").asText(), open.get(1).path("id").asText());
        var created = checkout(customer, Map.of("billIds", ids, "paymentMethod", "CREDIT_CARD"),
                UUID.randomUUID().toString());
        assertThat(created.statusCode()).withFailMessage(created.body()).isEqualTo(201);

        var note = call("POST", "/ops/bills/" + ids.get(1) + "/adjustments",
                Map.of("noteType", "CREDIT", "amount", 1.00, "reason", "Meter reading goodwill credit"), billing, null);
        assertThat(note.statusCode()).withFailMessage(note.body()).isEqualTo(201);
        assertThat(json(note).path("notes").size()).isEqualTo(1);

        var result = call("POST", "/payments/sandbox/" + json(created).path("id").asText() + "/confirm",
                Map.of("outcome", "SUCCEEDED"), customer, null);
        assertThat(json(result).path("status").asText()).isEqualTo("FAILED");
        assertThat(json(result).path("failureReason").asText()).contains("changed after checkout");
        for (String id : ids) {
            assertThat(json(call("GET", "/bills/" + id, null, customer, null)).path("bill").path("status").asText())
                    .isNotEqualTo("PAID");
        }
        var retry = checkout(customer, Map.of("billIds", ids, "paymentMethod", "CREDIT_CARD"),
                UUID.randomUUID().toString());
        assertThat(retry.statusCode()).withFailMessage(retry.body()).isEqualTo(201);
        var paid = call("POST", "/payments/sandbox/" + json(retry).path("id").asText() + "/confirm",
                Map.of("outcome", "SUCCEEDED"), customer, null);
        assertThat(json(paid).path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(json(call("GET", "/bills/summary", null, customer, null)).path("openBills").asInt())
                .isEqualTo(open.size() - 2);
    }

    @Test
    void runsIdempotentBillingCyclesForStaffOnly() throws Exception {
        String billing = demoToken("billing");
        var month = YearMonth.now(ZoneId.of("Asia/Kolkata")).minusMonths(1);
        var body = Map.of("year", month.getYear(), "month", month.getMonthValue(), "dryRun", false);
        assertThat(call("POST", "/ops/bills/runs", body, billing, null).statusCode()).isEqualTo(400);
        String key = UUID.randomUUID().toString();
        var run = call("POST", "/ops/bills/runs", body, billing, null, Map.of("Idempotency-Key", key));
        assertThat(run.statusCode()).withFailMessage(run.body()).isEqualTo(200);
        String runId = json(run).path("run").path("id").asText();
        assertThat(json(run).path("run").path("status").asText()).isIn("COMPLETED", "PARTIAL");
        var again = call("POST", "/ops/bills/runs", body, billing, null, Map.of("Idempotency-Key", key));
        assertThat(json(again).path("run").path("id").asText()).isEqualTo(runId);
        var preview = call("POST", "/ops/bills/runs", Map.of("year", month.getYear(), "month",
                month.getMonthValue(), "dryRun", true), billing, null);
        assertThat(json(preview).path("preview").size()).isZero();
        assertThat(call("POST", "/ops/bills/runs", body, demoToken("support"), null,
                Map.of("Idempotency-Key", UUID.randomUUID().toString())).statusCode()).isEqualTo(403);
        assertThat(call("GET", "/ops/bills?status=OVERDUE", null, demoToken("support"), null).statusCode())
                .isEqualTo(200);
    }

    private List<JsonNode> openBills(String token) throws Exception {
        var page = json(call("GET", "/bills?size=50", null, token, null));
        var open = new ArrayList<JsonNode>();
        for (JsonNode bill : page.path("content")) {
            if (bill.path("principalDue").decimalValue().signum() > 0) {
                open.add(bill);
            }
        }
        return open;
    }

    private java.net.http.HttpResponse<String> checkout(String token, Object body, String key) throws Exception {
        return call("POST", "/payments/checkout", body, token, null, Map.of("Idempotency-Key", key));
    }
}
