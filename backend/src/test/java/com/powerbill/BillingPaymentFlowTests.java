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

import com.powerbill.common.PdfKit;
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
        // The invoice must print the same itemized figures and total as the ledger, not just be a valid file.
        String invoice = pdfText(pdf.body());
        assertThat(invoice).contains(first.path("billNumber").asText(), "Invoice total",
                PdfKit.inr(detail.path("bill").path("totalAmount").decimalValue()), "Amount due");
        for (JsonNode line : detail.path("lineItems")) {
            assertThat(invoice).contains(PdfKit.inr(line.path("amount").decimalValue()));
        }
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

        // Warm the dashboard cache so the test proves a payment invalidates it, not just that it was empty.
        String overviewPath = "/analytics/consumer/overview?meterId=" + open.getFirst().path("meterId").asText();
        int openBefore = json(call("GET", overviewPath, null, customer, null)).path("receivables").path("openBills").asInt();

        String key = UUID.randomUUID().toString();
        var created = checkout(customer, request, key);
        assertThat(created.statusCode()).withFailMessage(created.body()).isEqualTo(201);
        var payment = json(created);
        assertThat(payment.path("paymentMethod").asText()).isEqualTo("UPI");
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
        assertThat(pdfText(receipt.body())).contains(payment.path("paymentReference").asText(),
                open.getFirst().path("billNumber").asText(), "Amount received",
                PdfKit.inr(payment.path("amount").decimalValue()));

        var forged = call("POST", "/webhooks/payments/sandbox",
                Map.of("id", "evt_forged", "type", "payment.succeeded", "data", Map.of("transactionId", paymentId)),
                null, null, Map.of("X-GridIQ-Signature", "t=1,v1=00"));
        assertThat(forged.statusCode()).isEqualTo(400);
        var ledger = json(call("GET", "/consumers/me/ledger", null, customer, null));
        assertThat(ledger.get(0).path("referenceType").asText()).isEqualTo("PAYMENT");

        // The settled bill leaves the dashboard straight away instead of after the cache expires.
        assertThat(awaitOpenBills(overviewPath, customer, openBefore - 1))
                .withFailMessage("The dashboard still showed the paid bill").isTrue();

        // Staff see which meter each bill belongs to.
        var opsBills = json(call("GET", "/ops/bills?size=5", null, demoToken("billing"), null)).path("content");
        assertThat(opsBills.get(0).path("meterSerial").asText()).startsWith("M-");

        // The receipt email and the in-app notification both survive: a mail problem must never roll back the inbox.
        assertThat(awaitPaymentNotification(paymentId)).withFailMessage("No payment notification was stored").isTrue();
        String billingEmail = json(call("GET", "/consumers/me", null, customer, null)).path("account")
                .path("billingEmail").asText();
        assertThat(mailArrived(billingEmail, "Payment received " + payment.path("paymentReference").asText()))
                .withFailMessage("No receipt email reached Mailpit").isTrue();
    }

    @Test
    void autoPaySettlesANewlyIssuedBillOnceThroughTheSignedGatewayPath() throws Exception {
        String customer = demoToken("customer12");
        var account = json(call("GET", "/consumers/me", null, customer, null)).path("account");
        long version = account.path("version").asLong();
        assertThat(call("PATCH", "/consumers/me/auto-pay", Map.of("enabled", true, "version", version + 5), customer,
                null).statusCode()).isEqualTo(409);
        var enabled = call("PATCH", "/consumers/me/auto-pay", Map.of("enabled", true, "version", version), customer, null);
        assertThat(enabled.statusCode()).withFailMessage(enabled.body()).isEqualTo(200);
        assertThat(json(enabled).path("autoPayEnabled").asBoolean()).isTrue();
        try {
            // A month with readings but no demo invoice yet; issuing it triggers the mandate.
            String meterId = json(call("GET", "/consumers/me/meters", null, customer, null)).get(0).path("id").asText();
            var month = YearMonth.now(ZoneId.of("Asia/Kolkata")).minusMonths(8);
            var issued = call("POST", "/ops/bills/generate", Map.of("meterId", meterId, "year", month.getYear(),
                    "month", month.getMonthValue()), demoToken("billing"), null);
            assertThat(issued.statusCode()).withFailMessage(issued.body()).isEqualTo(201);
            UUID billId = UUID.fromString(json(issued).path("bill").path("id").asText());

            assertThat(awaitBillStatus(billId, customer, "PAID")).withFailMessage("Auto-pay did not settle the bill")
                    .isTrue();
            assertThat(db.sql("""
                    SELECT count(*) FROM payment_transactions t JOIN payment_allocations a ON a.payment_transaction_id=t.id
                    WHERE a.bill_id=? AND t.payment_method='AUTO_PAY' AND t.status='SUCCEEDED' AND t.webhook_verified
                    """).param(billId).query(Long.class).single()).isEqualTo(1);

            // A manual sweep also settles the account's older open bills, but never charges this bill again.
            var sweep = call("POST", "/ops/payments/auto-pay/run", Map.of(), demoToken("billing"), null);
            assertThat(sweep.statusCode()).withFailMessage(sweep.body()).isEqualTo(200);
            assertThat(db.sql("SELECT count(*) FROM payment_allocations WHERE bill_id=?").param(billId)
                    .query(Long.class).single()).isEqualTo(1);
            assertThat(json(call("POST", "/ops/payments/auto-pay/run", Map.of(), demoToken("billing"), null))
                    .path("settledBills").asInt()).isZero();
            assertThat(call("POST", "/ops/payments/auto-pay/run", Map.of(), customer, null).statusCode()).isEqualTo(403);
        } finally {
            long current = json(call("GET", "/consumers/me", null, customer, null)).path("account").path("version").asLong();
            call("PATCH", "/consumers/me/auto-pay", Map.of("enabled", false, "version", current), customer, null);
        }
    }

    private boolean awaitBillStatus(UUID billId, String token, String status) throws Exception {
        for (int attempt = 0; attempt < 40; attempt++) {
            if (json(call("GET", "/bills/" + billId, null, token, null)).path("bill").path("status").asText()
                    .equals(status)) {
                return true;
            }
            Thread.sleep(250);
        }
        return false;
    }

    private boolean awaitOpenBills(String overviewPath, String token, int expected) throws Exception {
        for (int attempt = 0; attempt < 40; attempt++) {
            if (json(call("GET", overviewPath, null, token, null)).path("receivables").path("openBills").asInt()
                    == expected) {
                return true;
            }
            Thread.sleep(250);
        }
        return false;
    }

    private boolean awaitPaymentNotification(UUID paymentId) throws InterruptedException {
        for (int attempt = 0; attempt < 40; attempt++) {
            if (db.sql("SELECT count(*) FROM notifications WHERE category='PAYMENT' AND dedupe_key=?")
                    .param("payment:" + paymentId).query(Long.class).single() == 1) {
                return true;
            }
            Thread.sleep(250);
        }
        return false;
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
