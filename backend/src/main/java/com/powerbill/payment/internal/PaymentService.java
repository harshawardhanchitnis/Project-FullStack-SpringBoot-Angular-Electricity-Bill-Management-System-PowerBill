package com.powerbill.payment.internal;

import static java.math.BigDecimal.ZERO;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.powerbill.audit.AuditAction;
import com.powerbill.billing.BillingLedger;
import com.powerbill.common.RequestContext;
import com.powerbill.consumer.AccountDirectory;

import com.powerbill.payment.PaymentEvents;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
class PaymentService {
    static final Duration CHECKOUT_TTL = Duration.ofMinutes(30);
    /** Methods a customer can choose; AUTO_PAY is used only by the auto-pay service. */
    private static final String METHODS = "UPI|CREDIT_CARD|DEBIT_CARD|NET_BANKING|AUTO_PAY";
    private final PaymentStore store;
    private final BillingLedger billing;
    private final AccountDirectory accounts;
    private final PaymentGateways gateways;
    private final ApplicationEventPublisher events;

    PaymentService(PaymentStore store, BillingLedger billing, AccountDirectory accounts, PaymentGateways gateways,
            ApplicationEventPublisher events) {
        this.store = store;
        this.billing = billing;
        this.accounts = accounts;
        this.gateways = gateways;
        this.events = events;
    }

    @Transactional
    Checkout checkout(AccountDirectory.Account account, UUID actor, List<UUID> billIds, String method, UUID key,
            LocalDate asOf, Instant now, RequestContext request) {
        if (method == null || !method.matches(METHODS)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select UPI, card or net banking.");
        }
        if (!account.status().equals("ACTIVE")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Payments are unavailable for this account.");
        }
        String hash = WebhookSigner.sha256(billIds.stream().map(UUID::toString).sorted().toList() + "|" + method);
        var replay = replay(account.id(), key, hash);
        if (replay != null) {
            return replay;
        }
        var payables = billing.lockPayables(account.id(), billIds, asOf);
        replay = replay(account.id(), key, hash);
        if (replay != null) {
            return replay;
        }
        if (store.billHasOpenPayment(billIds, now)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A payment for one of these bills is already in progress. Complete it or try again later.");
        }
        BigDecimal amount = payables.stream().map(BillingLedger.Payable::totalDue).reduce(ZERO, BigDecimal::add);
        UUID id = UUID.randomUUID();
        String reference = "PAY-" + token(12);
        var gateway = gateways.checkout();
        var transaction = new PaymentStore.Transaction(id, reference, key, hash, account.id(), actor, gateway.provider(),
                gateway.createOrder(id, amount, "INR"), null, method, amount, "INR", "INITIATED", null, false,
                OffsetDateTime.ofInstant(now.plus(CHECKOUT_TTL), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(now, ZoneOffset.UTC), null, 0);
        store.insert(transaction);
        for (var payable : payables) {
            store.allocate(id, payable.billId(), payable.principalDue(), payable.lateFeeDue());
        }
        events.publishEvent(AuditAction.record("PAYMENT_INITIATED", "PAYMENT", id, actor, "CUSTOMER", request,
                Map.of("reference", reference, "amount", amount.toPlainString(), "bills", payables.size())));
        return new Checkout(view(store.find(id, false).orElseThrow()), false);
    }

    private Checkout replay(UUID accountId, UUID key, String hash) {
        return store.byKey(accountId, key).map(existing -> {
            if (!existing.requestHash().equals(hash)) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "This Idempotency-Key was already used for a different payment request.");
            }
            return new Checkout(view(existing), true);
        }).orElse(null);
    }

    PaymentStore.Transaction owned(UUID accountId, UUID id) {
        return store.find(id, false).filter(payment -> payment.consumerAccountId().equals(accountId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment was not found."));
    }

    Payment view(PaymentStore.Transaction transaction) {
        return new Payment(transaction.id(), transaction.paymentReference(), transaction.status(),
                transaction.amount(), transaction.currency(), transaction.paymentMethod(), transaction.gatewayProvider(),
                transaction.gatewayOrderId(), transaction.gatewayPaymentId(), transaction.webhookVerified(),
                transaction.failureReason(), transaction.createdAt(), transaction.expiresAt(),
                transaction.completedAt(), store.allocations(transaction.id()));
    }

    @Scheduled(fixedDelayString = "${gridiq.payments.expiry-sweep:PT5M}")
    void expireStaleCheckouts() {
        store.expireStale(Instant.now());
    }

    static String token(int length) {
        return UUID.randomUUID().toString().replace("-", "").substring(0, length).toUpperCase(Locale.ROOT);
    }

    record Checkout(Payment payment, boolean replayed) {}

    record Payment(UUID id, String paymentReference, String status, BigDecimal amount, String currency,
            String paymentMethod, String gatewayProvider, String gatewayOrderId, String gatewayPaymentId,
            boolean webhookVerified, String failureReason, OffsetDateTime createdAt, OffsetDateTime expiresAt, OffsetDateTime completedAt,
            List<PaymentStore.Allocation> allocations) {}
}

/** Handles signed gateway webhooks. The built-in sandbox posts through exactly the same path. */
@Service
class PaymentSettlement {
    private final PaymentStore store;
    private final PaymentService payments;
    private final BillingLedger billing;
    private final AccountDirectory accounts;
    private final PaymentGateways gateways;
    private final ObjectMapper json;
    private final ApplicationEventPublisher events;
    private final Counter settled;
    private final Counter settledAmount;

    PaymentSettlement(PaymentStore store, PaymentService payments, BillingLedger billing, AccountDirectory accounts,
            PaymentGateways gateways, ObjectMapper json, ApplicationEventPublisher events, MeterRegistry metrics) {
        this.store = store;
        this.payments = payments;
        this.billing = billing;
        this.accounts = accounts;
        this.gateways = gateways;
        this.json = json;
        this.events = events;
        this.settled = Counter.builder("powerbill.payments.settled").register(metrics);
        this.settledAmount = Counter.builder("powerbill.payments.settled.amount").baseUnit("inr").register(metrics);
    }

    @Transactional
    PaymentService.Payment handle(String provider, String body, String signature) {
        var gateway = gateways.byWebhook(provider);
        if (body.length() > 20_000 || !gateway.verify(body, signature, Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The webhook signature is invalid.");
        }
        JsonNode event;
        UUID id;
        try {
            event = json.readTree(body);
            id = UUID.fromString(event.path("data").path("transactionId").asText());
        } catch (JacksonException | IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The webhook payload is invalid.");
        }
        String eventId = event.path("id").asText();
        String type = event.path("type").asText();
        if (!eventId.matches("[A-Za-z0-9_-]{1,80}") || !type.matches("payment\\.(succeeded|failed)")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The webhook payload is invalid.");
        }
        var payment = store.find(id, true).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment was not found."));
        if (!store.recordWebhook(eventId, provider, id, WebhookSigner.sha256(body))
                || !(payment.status().equals("INITIATED") || payment.status().equals("PENDING"))) {
            return payments.view(store.find(id, false).orElseThrow());
        }
        var data = event.path("data");
        BigDecimal amount;
        Instant occurredAt;
        try {
            amount = new BigDecimal(data.path("amount").asText());
            occurredAt = data.hasNonNull("occurredAt") ? Instant.parse(data.path("occurredAt").asText()) : Instant.now();
        } catch (NumberFormatException | java.time.format.DateTimeParseException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The webhook payload is invalid.");
        }
        UUID userId = accounts.findAccount(payment.consumerAccountId()).map(AccountDirectory.Account::userId)
                .orElse(payment.initiatedBy());
        if (amount.compareTo(payment.amount()) != 0) {
            fail(payment, userId, "The gateway amount did not match the checkout amount.");
        } else if (type.equals("payment.failed")) {
            fail(payment, userId, truncate(data.path("failureReason").asText("Payment declined")));
        } else {
            var allocations = store.allocations(id).stream()
                    .sorted(Comparator.comparing(PaymentStore.Allocation::billId))
                    .map(item -> new BillingLedger.Allocation(item.billId(), item.principalAmount(), item.lateFeeAmount()))
                    .toList();
            try {
                Instant settledAt = occurredAt.isAfter(Instant.now()) ? Instant.now() : occurredAt;
                billing.settle(payment.consumerAccountId(), id, payment.paymentReference(), allocations, settledAt);
                store.succeed(id, truncate(data.path("gatewayPaymentId").asText()), settledAt);
                settled.increment();
                settledAmount.increment(amount.doubleValue());
                events.publishEvent(new PaymentEvents.PaymentSucceeded(id, payment.paymentReference(),
                        payment.consumerAccountId(), userId, amount, payment.paymentMethod(),
                        store.allocations(id).stream().map(PaymentStore.Allocation::billNumber).toList(), settledAt));
                events.publishEvent(AuditAction.record("PAYMENT_SETTLED", "PAYMENT", id, userId, "CUSTOMER",
                        new RequestContext("webhook:" + eventId, null, "gateway"),
                        Map.of("reference", payment.paymentReference(), "amount", amount.toPlainString())));
            } catch (BillingLedger.SettlementRejected rejected) {
                fail(payment, userId, truncate(rejected.getMessage() + " The captured amount must be refunded."));
            }
        }
        return payments.view(store.find(id, false).orElseThrow());
    }

    private void fail(PaymentStore.Transaction payment, UUID userId, String reason) {
        store.fail(payment.id(), "FAILED", reason, true);
        events.publishEvent(new PaymentEvents.PaymentFailed(payment.id(), payment.paymentReference(),
                payment.consumerAccountId(), userId, payment.amount(), reason));
    }

    private static String truncate(String value) {
        return value.length() > 200 ? value.substring(0, 200) : value;
    }

    static LocalDate today() {
        return LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
    }
}

/** The built-in sandbox gateway. It signs events like a real gateway and posts them through the webhook path. */
@org.springframework.stereotype.Component
class SandboxGateway {
    private final PaymentService payments;
    private final PaymentSettlement settlement;
    private final WebhookSigner signer;
    private final ObjectMapper json;

    SandboxGateway(PaymentService payments, PaymentSettlement settlement, WebhookSigner signer, ObjectMapper json) {
        this.payments = payments;
        this.settlement = settlement;
        this.signer = signer;
        this.json = json;
    }

    /** Builds and signs the event a gateway would send, then processes it as an inbound webhook. */
    PaymentService.Payment confirm(UUID accountId, UUID id, String outcome, Instant occurredAt) {
        var payment = payments.owned(accountId, id);
        if (!payment.gatewayProvider().equals("SANDBOX")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This payment does not use the sandbox gateway.");
        }
        if (!payment.status().equals("INITIATED")) {
            return payments.view(payment);
        }
        if (!payment.expiresAt().toInstant().isAfter(occurredAt)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This checkout has expired. Start a new payment.");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("transactionId", id.toString());
        data.put("paymentReference", payment.paymentReference());
        data.put("gatewayOrderId", payment.gatewayOrderId());
        data.put("gatewayPaymentId", "sbx_pay_" + PaymentService.token(16));
        data.put("amount", payment.amount().toPlainString());
        data.put("occurredAt", occurredAt.toString());
        if (outcome.equals("FAILED")) {
            data.put("failureReason", "Declined by the sandbox issuer");
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", "evt_" + PaymentService.token(24).toLowerCase(Locale.ROOT));
        event.put("type", outcome.equals("SUCCEEDED") ? "payment.succeeded" : "payment.failed");
        event.put("data", data);
        String body = json.writeValueAsString(event);
        return settlement.handle("sandbox", body, signer.sign(body, Instant.now()));
    }

}
