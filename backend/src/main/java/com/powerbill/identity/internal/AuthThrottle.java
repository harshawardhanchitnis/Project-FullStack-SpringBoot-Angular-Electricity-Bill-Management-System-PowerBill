package com.powerbill.identity.internal;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import com.powerbill.common.RequestContext;
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

@Component
class AuthThrottle {
    private static final Duration MINUTE = Duration.ofMinutes(1);
    private final ProxyManager<byte[]> buckets;

    AuthThrottle(LettuceConnectionFactory redis) {
        if (!(redis.getNativeClient() instanceof RedisClient client)) {
            throw new IllegalStateException("Authentication throttling requires a standalone Redis client.");
        }
        buckets = Bucket4jLettuce.casBasedBuilder(client)
                .expirationAfterWrite(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(MINUTE))
                .build();
    }

    void acquire(String operation, RequestContext request, String subject, int limit) {
        String key = "gridiq:auth:limit:" + operation + ":"
                + SecurityTokens.digest(request.remoteAddress() + "\n" + subject);
        var configuration = BucketConfiguration.builder()
                .addLimit(bandwidth -> bandwidth.capacity(limit).refillIntervally(limit, MINUTE)).build();
        long retryAfter;
        try {
            var probe = buckets.getProxy(key.getBytes(StandardCharsets.UTF_8), () -> configuration)
                    .tryConsumeAndReturnRemaining(1);
            if (probe.isConsumed()) {
                return;
            }
            retryAfter = Math.max(1, Duration.ofNanos(probe.getNanosToWaitForRefill()).plusMillis(999).toSeconds());
        } catch (RuntimeException error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Authentication is temporarily unavailable.");
        }
        throw new TooManyRequests(retryAfter);
    }

    private static final class TooManyRequests extends ResponseStatusException {
        private final HttpHeaders headers = new HttpHeaders();

        TooManyRequests(long seconds) {
            super(HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Try again shortly.");
            headers.set(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }
    }
}
