package com.powerbill.identity.internal;

import java.time.Duration;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
class SessionRevocations {
    private static final Logger LOG = LoggerFactory.getLogger(SessionRevocations.class);
    private final StringRedisTemplate redis;

    SessionRevocations(StringRedisTemplate redis) {
        this.redis = redis;
    }

    boolean contains(UUID family) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(key(family)));
        } catch (org.springframework.dao.DataAccessException error) {
            return false; // JWT validation also checks the authoritative PostgreSQL state.
        }
    }

    @TransactionalEventListener
    void remember(FamilyRevoked event) {
        try {
            redis.opsForValue().set(key(event.family()), "1", Duration.ofDays(7));
        } catch (org.springframework.dao.DataAccessException error) {
            LOG.warn("Redis was unavailable while caching a committed session revocation.");
        }
    }

    private String key(UUID family) {
        return "gridiq:auth:revoked:" + family;
    }

    record FamilyRevoked(UUID family) {}
}
