package com.powerbill.payment.internal;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * HMAC-SHA256 webhook signatures in the form {@code t=<unix seconds>,v1=<hex digest>} computed over
 * {@code <t>.<raw body>}. Verification rejects stale timestamps to stop replays.
 */
@Component
class WebhookSigner {
    static final String HEADER = "X-GridIQ-Signature";
    static final Duration TOLERANCE = Duration.ofMinutes(5);
    private static final Logger LOG = LoggerFactory.getLogger(WebhookSigner.class);
    private final byte[] secret;

    WebhookSigner(@Value("${gridiq.payments.webhook-secret:}") String configured, Environment environment) {
        if (configured.isBlank()) {
            if (!environment.acceptsProfiles(Profiles.of("(dev | test) & !prod"))) {
                throw new IllegalStateException("Set PAYMENT_WEBHOOK_SECRET to at least 32 characters.");
            }
            byte[] generated = new byte[32];
            new SecureRandom().nextBytes(generated);
            secret = generated;
            LOG.info("Using a per-process sandbox webhook secret. Set PAYMENT_WEBHOOK_SECRET to sign externally.");
        } else {
            if (configured.length() < 32) {
                throw new IllegalStateException("PAYMENT_WEBHOOK_SECRET must contain at least 32 characters.");
            }
            secret = configured.getBytes(StandardCharsets.UTF_8);
        }
    }

    String sign(String body, Instant at) {
        long timestamp = at.getEpochSecond();
        return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(mac(timestamp + "." + body));
    }

    boolean verify(String body, String header, Instant now) {
        if (header == null || header.length() > 200) {
            return false;
        }
        Long timestamp = null;
        byte[] supplied = null;
        for (String part : header.split(",")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length != 2) {
                return false;
            }
            try {
                if (pair[0].equals("t")) {
                    timestamp = Long.parseLong(pair[1]);
                } else if (pair[0].equals("v1")) {
                    supplied = HexFormat.of().parseHex(pair[1]);
                }
            } catch (IllegalArgumentException invalid) {
                return false;
            }
        }
        if (timestamp == null || supplied == null
                || Duration.between(Instant.ofEpochSecond(timestamp), now).abs().compareTo(TOLERANCE) > 0) {
            return false;
        }
        return MessageDigest.isEqual(mac(timestamp + "." + body), supplied);
    }

    static String sha256(String body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private byte[] mac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException error) {
            throw new IllegalStateException(error);
        }
    }
}
