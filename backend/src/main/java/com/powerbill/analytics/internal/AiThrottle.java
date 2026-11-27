package com.powerbill.analytics.internal;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.RedisClient;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Per-user token buckets in Redis for the AI endpoints. Bucket4j updates each bucket atomically with
 * compare-and-swap, so concurrent requests from one user can never exceed the limit.
 */
@Component
class AiThrottle {
    static final int ASSISTANT_PER_MINUTE = 20;
    static final int FORECAST_PER_MINUTE = 30;
    private static final Duration MINUTE = Duration.ofMinutes(1);
    private final ProxyManager<byte[]> buckets;

    AiThrottle(LettuceConnectionFactory redis) {
        if (!(redis.getNativeClient() instanceof RedisClient client)) {
            throw new IllegalStateException("AI throttling requires a standalone Redis client.");
        }
        buckets = Bucket4jLettuce.casBasedBuilder(client)
                .expirationAfterWrite(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(MINUTE))
                .build();
    }

    /** Takes one request from the user's bucket for the operation, or fails with 429 and a Retry-After header. */
    void acquire(String operation, UUID user, int perMinute) {
        byte[] key = ("gridiq:ai:limit:" + operation + ":" + user).getBytes(StandardCharsets.UTF_8);
        var configuration = BucketConfiguration.builder()
                .addLimit(limit -> limit.capacity(perMinute).refillIntervally(perMinute, MINUTE)).build();
        long retryAfter;
        try {
            var probe = buckets.getProxy(key, () -> configuration).tryConsumeAndReturnRemaining(1);
            if (probe.isConsumed()) {
                return;
            }
            retryAfter = Math.max(1, Duration.ofNanos(probe.getNanosToWaitForRefill()).plusMillis(999).toSeconds());
        } catch (RuntimeException error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The assistant is temporarily unavailable.");
        }
        throw new TooManyRequests(retryAfter);
    }

    static final class TooManyRequests extends ResponseStatusException {
        private final HttpHeaders headers = new HttpHeaders();

        TooManyRequests(long seconds) {
            super(HttpStatus.TOO_MANY_REQUESTS, "Too many assistant requests. Try again shortly.");
            headers.set(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }
    }
}
