package com.powerbill.payment.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * A payment provider adapter. Checkout asks it for an order, and inbound webhooks are verified by the adapter whose
 * {@link #webhookName()} matches the {@code /api/v1/webhooks/payments/{provider}} path. The built-in
 * {@link SandboxPaymentGateway} is the default; a Stripe or Razorpay adapter would implement the same contract and
 * post its events through the same settlement handler.
 */
interface PaymentGateway {

    /** Stored in {@code payment_transactions.gateway_provider}, for example {@code SANDBOX}. */
    String provider();

    /** The provider segment of the webhook URL, for example {@code sandbox}. */
    String webhookName();

    /** Creates the provider-side order for a checkout and returns its identifier. */
    String createOrder(UUID paymentId, BigDecimal amount, String currency);

    /** Verifies a webhook body against the provider's signature header. */
    boolean verify(String body, String signature, Instant receivedAt);
}

/** The keyless sandbox: orders are local identifiers and webhooks are signed with HMAC-SHA256. */
@Component
class SandboxPaymentGateway implements PaymentGateway {
    private final WebhookSigner signer;

    SandboxPaymentGateway(WebhookSigner signer) {
        this.signer = signer;
    }

    @Override
    public String provider() {
        return "SANDBOX";
    }

    @Override
    public String webhookName() {
        return "sandbox";
    }

    @Override
    public String createOrder(UUID paymentId, BigDecimal amount, String currency) {
        return "sbx_order_" + PaymentService.token(16);
    }

    @Override
    public boolean verify(String body, String signature, Instant receivedAt) {
        return signer.verify(body, signature, receivedAt);
    }
}

/** Resolves the adapter used for new checkouts and the one that owns an inbound webhook. */
@Component
class PaymentGateways {
    private final List<PaymentGateway> gateways;

    PaymentGateways(List<PaymentGateway> gateways) {
        this.gateways = gateways;
    }

    /** The adapter for new checkouts. Only the sandbox is configured today. */
    PaymentGateway checkout() {
        return byProvider("SANDBOX");
    }

    PaymentGateway byWebhook(String name) {
        return gateways.stream().filter(gateway -> gateway.webhookName().equals(name)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown payment provider."));
    }

    PaymentGateway byProvider(String provider) {
        return gateways.stream().filter(gateway -> gateway.provider().equals(provider)).findFirst()
                .orElseThrow(() -> new IllegalStateException("No payment gateway for " + provider));
    }
}
