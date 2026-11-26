package com.powerbill.identity.internal;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class IdentityStore {
    private static final String USER_FIELDS = """
            id, email, password_hash, full_name, mobile_number, role, status, email_verified,
            failed_login_attempts, locked_until, session_version, theme_preference, budget_alerts
            """;
    private final JdbcClient db;

    IdentityStore(JdbcClient db) {
        this.db = db;
    }

    Optional<User> byEmail(String email, boolean lock) {
        return db.sql("SELECT " + USER_FIELDS + " FROM users WHERE email = ?" + (lock ? " FOR UPDATE" : ""))
                .param(email).query(User.class).optional();
    }

    Optional<User> byId(UUID id, boolean lock) {
        return db.sql("SELECT " + USER_FIELDS + " FROM users WHERE id = ?" + (lock ? " FOR UPDATE" : ""))
                .param(id).query(User.class).optional();
    }

    boolean insert(UUID id, String email, String hash, String name, String phone, String role, boolean verified) {
        return db.sql("""
                INSERT INTO users (id,email,password_hash,full_name,mobile_number,role,email_verified)
                VALUES (?,?,?,?,?,?,?) ON CONFLICT (email) DO NOTHING
                """).params(id, email, hash, name, phone, role, verified).update() == 1;
    }

    void loginFailure(UUID id, int failures, OffsetDateTime lockedUntil) {
        db.sql("UPDATE users SET failed_login_attempts=?, locked_until=?, updated_at=now() WHERE id=?")
                .params(failures, lockedUntil, id).update();
    }

    void preferences(UUID id, String theme, Boolean budgetAlerts) {
        db.sql("""
                UPDATE users SET theme_preference=COALESCE(?,theme_preference),
                    budget_alerts=COALESCE(?,budget_alerts), updated_at=now()
                WHERE id=?
                """).params(theme, budgetAlerts, id).update();
    }

    Optional<Refresh> refresh(String hash) {
        return db.sql("""
                SELECT id,user_id,family_id,expires_at,revoked_at,replaced_by_id
                FROM refresh_tokens WHERE token_hash=?
                """).param(hash).query(Refresh.class).optional();
    }

    void insertRefresh(UUID id, UUID user, UUID family, String hash, OffsetDateTime expiresAt,
            String agent, String address) {
        db.sql("""
                INSERT INTO refresh_tokens(id,user_id,family_id,token_hash,expires_at,user_agent,ip_address)
                VALUES (?,?,?,?,?,?,CAST(? AS inet))
                """).params(id, user, family, hash, expiresAt, agent, address).update();
    }

    void replaceRefresh(UUID id, UUID replacement) {
        db.sql("UPDATE refresh_tokens SET revoked_at=now(), replaced_by_id=? WHERE id=?")
                .params(replacement, id).update();
    }

    void revokeFamily(UUID family) {
        db.sql("UPDATE refresh_tokens SET revoked_at=now() WHERE family_id=? AND revoked_at IS NULL")
                .param(family).update();
    }

    boolean activeFamily(UUID userId, UUID family) {
        return db.sql("""
                SELECT EXISTS (SELECT 1 FROM refresh_tokens WHERE user_id=? AND family_id=?
                    AND revoked_at IS NULL AND expires_at>now())
                """).params(userId, family).query(Boolean.class).single();
    }

    void createReset(UUID userId, String hash, OffsetDateTime expires) {
        db.sql("UPDATE password_reset_tokens SET used_at=now() WHERE user_id=? AND used_at IS NULL")
                .param(userId).update();
        db.sql("INSERT INTO password_reset_tokens (id,user_id,token_hash,expires_at) VALUES (?,?,?,?)")
                .params(UUID.randomUUID(), userId, hash, expires).update();
    }

    Optional<UUID> resetUser(String hash) {
        return db.sql("SELECT user_id FROM password_reset_tokens WHERE token_hash=?")
                .param(hash).query(UUID.class).optional();
    }

    boolean takeReset(String hash) {
        return db.sql("""
                UPDATE password_reset_tokens SET used_at=now()
                WHERE token_hash=? AND used_at IS NULL AND expires_at>now()
                """).param(hash).update() == 1;
    }

    void changePassword(UUID id, String hash) {
        db.sql("""
                UPDATE users SET password_hash=?, session_version=session_version+1,
                    failed_login_attempts=0, locked_until=NULL, updated_at=now()
                WHERE id=?
                """).params(hash, id).update();
        db.sql("UPDATE refresh_tokens SET revoked_at=now() WHERE user_id=? AND revoked_at IS NULL")
                .param(id).update();
        db.sql("UPDATE password_reset_tokens SET used_at=now() WHERE user_id=? AND used_at IS NULL")
                .param(id).update();
    }

    void createVerification(UUID userId, String hash, OffsetDateTime expires) {
        db.sql("INSERT INTO email_verification_tokens(token_hash,user_id,expires_at) VALUES (?,?,?)")
                .params(hash, userId, expires).update();
    }

    Optional<UUID> verifyEmail(String hash) {
        return db.sql("""
                UPDATE email_verification_tokens SET used_at=now()
                WHERE token_hash=? AND used_at IS NULL AND expires_at>now()
                RETURNING user_id
                """).param(hash).query(UUID.class).optional();
    }

    void markVerified(UUID id) {
        db.sql("UPDATE users SET email_verified=true, updated_at=now() WHERE id=?").param(id).update();
    }

    record User(UUID id, String email, String passwordHash, String fullName, String mobileNumber,
            String role, String status, boolean emailVerified, int failedLoginAttempts,
            OffsetDateTime lockedUntil, int sessionVersion, String themePreference, boolean budgetAlerts) {}
    record Refresh(UUID id, UUID userId, UUID familyId, OffsetDateTime expiresAt,
            OffsetDateTime revokedAt, UUID replacedById) {}
}
