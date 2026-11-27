package com.powerbill.payment.internal;

import static com.powerbill.common.PdfKit.inr;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.powerbill.common.PdfKit;
import com.powerbill.common.RequestContext;
import com.powerbill.consumer.AccountDirectory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
class PaymentController {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm 'IST'", Locale.ENGLISH)
            .withZone(ZoneId.of("Asia/Kolkata"));
    private final PaymentService payments;
    private final SandboxGateway sandbox;
    private final PaymentSettlement settlement;
    private final PaymentStore store;
    private final AccountDirectory accounts;
    private final AutoPayService autoPay;

    PaymentController(PaymentService payments, SandboxGateway sandbox, PaymentSettlement settlement, PaymentStore store,
            AccountDirectory accounts, AutoPayService autoPay) {
        this.payments = payments;
        this.sandbox = sandbox;
        this.settlement = settlement;
        this.store = store;
        this.accounts = accounts;
        this.autoPay = autoPay;
    }

    /** Runs the auto-pay sweep now instead of waiting for the daily schedule (billing officers and admins). */
    @PostMapping("/ops/payments/auto-pay/run")
    Map<String, Integer> runAutoPay() {
        return Map.of("settledBills", autoPay.run());
    }

    @PostMapping("/payments/checkout")
    ResponseEntity<PaymentService.Payment> checkout(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody CheckoutRequest input, HttpServletRequest request) {
        UUID idempotency;
        try {
            idempotency = UUID.fromString(key == null ? "" : key);
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Send a UUID Idempotency-Key header.");
        }
        var account = self(jwt);
        var result = payments.checkout(account, UUID.fromString(jwt.getSubject()), input.billIds(),
                input.paymentMethod(), idempotency, PaymentSettlement.today(), Instant.now(),
                RequestContext.from(request));
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore()).body(result.payment());
    }

    @PostMapping("/payments/sandbox/{id}/confirm")
    PaymentService.Payment confirm(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody SandboxOutcome input) {
        return sandbox.confirm(self(jwt).id(), id, input.outcome(), Instant.now());
    }

    @PostMapping(path = "/webhooks/payments/{provider}", consumes = MediaType.APPLICATION_JSON_VALUE)
    PaymentService.Payment webhook(@PathVariable String provider, @RequestBody String body,
            @RequestHeader(name = WebhookSigner.HEADER, required = false) String signature) {
        return settlement.handle(provider, body, signature);
    }

    @GetMapping("/payments")
    PaymentStore.Page history(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return store.page(self(jwt).id(), null, checkPage(page, size), size);
    }

    @GetMapping("/payments/{id}")
    PaymentService.Payment payment(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return payments.view(payments.owned(self(jwt).id(), id));
    }

    @GetMapping("/payments/{id}/receipt/pdf")
    ResponseEntity<byte[]> receipt(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        var account = self(jwt);
        var payment = payments.view(payments.owned(account.id(), id));
        if (!payment.status().equals("SUCCEEDED")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A receipt is available after the payment succeeds.");
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(payment.paymentReference() + ".pdf").build().toString())
                .body(render(account, payment));
    }

    @GetMapping("/ops/payments")
    PaymentStore.Page opsPayments(@RequestParam(required = false) UUID accountId,
            @RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (status != null && !status.matches("INITIATED|PENDING|SUCCEEDED|FAILED|EXPIRED")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown payment status.");
        }
        return store.page(accountId, status, checkPage(page, size), size);
    }

    private static byte[] render(AccountDirectory.Account account, PaymentService.Payment payment) {
        return PdfKit.render("Payment receipt " + payment.paymentReference(), "Electricity payment receipt", page -> {
            page.qr("GRIDIQ-RECEIPT|" + payment.paymentReference() + "|" + payment.amount().toPlainString(), 72);
            page.heading("PowerBill GridIQ · Electricity distribution", "Payment receipt");
            var details = new LinkedHashMap<String, String>();
            details.put("Receipt number", payment.paymentReference());
            details.put("Paid on", STAMP.format(payment.completedAt().toInstant()));
            details.put("Consumer", account.fullName() + " · " + account.consumerNumber());
            details.put("Method", payment.paymentMethod().replace('_', ' '));
            details.put("Gateway reference", payment.gatewayPaymentId() == null ? "" : payment.gatewayPaymentId());
            details.put("Signature verified", payment.webhookVerified() ? "Yes" : "No");
            page.details(details);
            page.section("Bills settled");
            var table = page.table(new float[] {2.6f, 1.4f, 1.2f, 1.4f}, "Invoice", "Principal", "Late fee", "Total");
            for (var allocation : payment.allocations()) {
                page.row(table, false, allocation.billNumber(), inr(allocation.principalAmount()),
                        inr(allocation.lateFeeAmount()),
                        inr(allocation.principalAmount().add(allocation.lateFeeAmount())));
            }
            page.row(table, true, "Amount received", "", "", inr(payment.amount()));
            page.add(table);
            page.note("Thank you for your payment. Keep this receipt for your records.");
        });
    }

    private AccountDirectory.Account self(Jwt jwt) {
        return accounts.accountForUser(UUID.fromString(jwt.getSubject()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Consumer account was not found."));
    }

    private static int checkPage(int page, int size) {
        if (page < 0 || size < 1 || size > 100 || page > 10_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid page or size.");
        }
        return page;
    }

    record CheckoutRequest(@NotEmpty @Size(max = 24) List<@NotNull UUID> billIds,
            @NotBlank @Pattern(regexp = "UPI|CREDIT_CARD|DEBIT_CARD|NET_BANKING") String paymentMethod) {}

    record SandboxOutcome(@NotBlank @Pattern(regexp = "SUCCEEDED|FAILED") String outcome) {}
}
