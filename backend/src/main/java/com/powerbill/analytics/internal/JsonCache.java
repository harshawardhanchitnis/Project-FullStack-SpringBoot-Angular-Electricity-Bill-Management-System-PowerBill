package com.powerbill.analytics.internal;

import java.time.Duration;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Read-through Redis cache for dashboard aggregates. Redis failures fall back to computing the value. */
@Component
class JsonCache {
    private static final Logger LOG = LoggerFactory.getLogger(JsonCache.class);
    private static final String PREFIX = "gridiq:analytics:";
    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    JsonCache(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    <T> T get(String key, Duration ttl, Class<T> type, Supplier<T> compute) {
        try {
            String cached = redis.opsForValue().get(PREFIX + key);
            if (cached != null) {
                return json.readValue(cached, type);
            }
        } catch (DataAccessException | JacksonException error) {
            LOG.debug("Analytics cache read skipped ({})", error.getClass().getSimpleName());
        }
        T value = compute.get();
        try {
            redis.opsForValue().set(PREFIX + key, json.writeValueAsString(value), ttl);
        } catch (DataAccessException | JacksonException error) {
            LOG.debug("Analytics cache write skipped ({})", error.getClass().getSimpleName());
        }
        return value;
    }

    void evict(String key) {
        try {
            redis.delete(PREFIX + key);
        } catch (DataAccessException error) {
            LOG.debug("Analytics cache eviction skipped");
        }
    }
}
