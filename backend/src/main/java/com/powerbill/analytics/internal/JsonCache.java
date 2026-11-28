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

    <T> java.util.Optional<T> read(String key, Class<T> type) {
        try {
            String cached = redis.opsForValue().get(PREFIX + key);
            return cached == null ? java.util.Optional.empty() : java.util.Optional.of(json.readValue(cached, type));
        } catch (DataAccessException | JacksonException error) {
            return java.util.Optional.empty();
        }
    }

    void put(String key, Object value, Duration ttl) {
        try {
            redis.opsForValue().set(PREFIX + key, json.writeValueAsString(value), ttl);
        } catch (DataAccessException | JacksonException error) {
            LOG.debug("Analytics cache write skipped ({})", error.getClass().getSimpleName());
        }
    }

    void evict(String key) {
        try {
            redis.delete(PREFIX + key);
        } catch (DataAccessException error) {
            LOG.debug("Analytics cache eviction skipped");
        }
    }

    /**
     * Current value of a generation counter, used as part of a cache key. Bumping the counter makes every key built
     * from the old value unreachable at once, without scanning Redis. Returns -1 if Redis is unavailable, so the
     * key changes and a stale entry is never served.
     */
    long generation(String name) {
        try {
            String value = redis.opsForValue().get(PREFIX + "generation:" + name);
            return value == null ? 0 : Long.parseLong(value);
        } catch (DataAccessException | NumberFormatException error) {
            return -1;
        }
    }

    void bump(String name) {
        try {
            redis.opsForValue().increment(PREFIX + "generation:" + name);
            redis.expire(PREFIX + "generation:" + name, Duration.ofDays(2));
        } catch (DataAccessException error) {
            LOG.debug("Analytics cache generation bump skipped");
        }
    }
}
