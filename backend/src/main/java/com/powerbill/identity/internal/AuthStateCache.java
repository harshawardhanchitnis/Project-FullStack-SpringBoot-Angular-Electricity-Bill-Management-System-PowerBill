package com.powerbill.identity.internal;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Caches the facts every authenticated request checks: the user's status, role and session version, and whether a
 * refresh-token family is still active. Entries live for 60 seconds and are evicted as soon as a password, role,
 * status or session changes, so access-token validation normally needs no PostgreSQL query. Revoked families are
 * also listed in {@link SessionRevocations}, which is checked first. If Redis fails, every check reads PostgreSQL.
 */
@Component
class AuthStateCache {
    private static final Duration TTL = Duration.ofSeconds(60);
    private final StringRedisTemplate redis;

    AuthStateCache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** The authentication-relevant view of a user; {@code demo} marks a demo-domain account. */
    record State(String status, String role, int sessionVersion, boolean demo) {
        static State of(IdentityStore.User user) {
            return new State(user.status(), user.role(), user.sessionVersion(),
                    user.email().endsWith(SecurityTokens.DEMO_DOMAIN));
        }

        String encode() {
            return status + "|" + role + "|" + sessionVersion + "|" + demo;
        }

        static Optional<State> decode(String value) {
            String[] parts = value.split("\\|");
            if (parts.length != 4) {
                return Optional.empty();
            }
            try {
                return Optional.of(new State(parts[0], parts[1], Integer.parseInt(parts[2]), Boolean.parseBoolean(parts[3])));
            } catch (NumberFormatException error) {
                return Optional.empty();
            }
        }
    }

    Optional<State> user(UUID userId, Supplier<Optional<State>> loader) {
        String key = userKey(userId);
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                var state = State.decode(cached);
                if (state.isPresent()) {
                    return state;
                }
            }
        } catch (DataAccessException error) {
            return loader.get();
        }
        var loaded = loader.get();
        loaded.ifPresent(state -> write(key, state.encode()));
        return loaded;
    }

    /** Only active families are cached; an inactive answer always comes from PostgreSQL. */
    boolean familyActive(UUID family, BooleanSupplier loader) {
        String key = familyKey(family);
        try {
            if ("1".equals(redis.opsForValue().get(key))) {
                return true;
            }
        } catch (DataAccessException error) {
            return loader.getAsBoolean();
        }
        boolean active = loader.getAsBoolean();
        if (active) {
            write(key, "1");
        }
        return active;
    }

    /** Evicts now and again after commit, so a request racing the transaction cannot re-cache the old state. */
    void evictUser(UUID userId) {
        evict(userKey(userId));
    }

    void evictFamily(UUID family) {
        evict(familyKey(family));
    }

    @TransactionalEventListener
    void on(SessionRevocations.FamilyRevoked event) {
        delete(familyKey(event.family()));
    }

    private void evict(String key) {
        delete(key);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    delete(key);
                }
            });
        }
    }

    private void write(String key, String value) {
        try {
            redis.opsForValue().set(key, value, TTL);
        } catch (DataAccessException error) {
            // A missed write only costs a database read on the next request.
        }
    }

    private void delete(String key) {
        try {
            redis.delete(key);
        } catch (DataAccessException error) {
            // Entries expire within the TTL; session versions and the revocation list still apply.
        }
    }

    private static String userKey(UUID userId) {
        return "gridiq:auth:state:" + userId;
    }

    private static String familyKey(UUID family) {
        return "gridiq:auth:family:" + family;
    }
}
