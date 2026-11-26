package com.powerbill.identity.internal;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.powerbill.audit.AuditAction;
import com.powerbill.common.RequestContext;
import com.powerbill.identity.CustomerIdentity;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class IdentityService implements CustomerIdentity {
    private final IdentityStore store;
    private final PasswordEncoder passwords;
    private final JwtEncoder encoder;
    private final AuthThrottle throttle;
    private final ApplicationEventPublisher events;
    private final Environment environment;
    private final String dummyPasswordHash;

    IdentityService(IdentityStore store, PasswordEncoder passwords, JwtEncoder encoder,
            AuthThrottle throttle, ApplicationEventPublisher events, Environment environment) {
        this.store = store;
        this.passwords = passwords;
        this.encoder = encoder;
        this.throttle = throttle;
        this.events = events;
        this.environment = environment;
        dummyPasswordHash = passwords.encode(SecurityTokens.newOpaqueToken());
    }

    @Override
    @Transactional
    public UUID seedDemoCustomer(String email, String password, String name, String phone) {
        if (!environment.acceptsProfiles(Profiles.of("dev & !prod"))
                || !environment.getProperty("gridiq.demo.enabled", Boolean.class, false)) {
            throw new IllegalStateException("Demo seeding is disabled.");
        }
        UUID id = UUID.randomUUID();
        store.insert(id, email, passwords.encode(password), name, phone, "CUSTOMER", true);
        var user = store.byEmail(email, true).orElseThrow();
        if (!user.id().equals(id)) {
            throw new IllegalStateException("A demo customer email is already registered.");
        }
        return id;
    }

    @Override
    @Transactional
    public UUID registerCustomer(String rawEmail, String password, String fullName,
            String mobileNumber, RequestContext request) {
        throttle.acquire("register", request, "", 3);
        UUID id = provisionCustomer(rawEmail, password, fullName, mobileNumber, request);
        audit("CUSTOMER_REGISTERED", id, "CUSTOMER", request, Map.of());
        return id;
    }

    @Override
    @Transactional
    public UUID provisionCustomer(String rawEmail, String password, String fullName,
            String mobileNumber, RequestContext request) {
        String email = normalize(rawEmail);
        if (email.endsWith(SecurityTokens.DEMO_DOMAIN)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use a non-demo email address.");
        }
        UUID id = UUID.randomUUID();
        if (!store.insert(id, email, passwords.encode(password), fullName.strip(), mobileNumber, "CUSTOMER", false)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An account with this email already exists.");
        }
        String token = SecurityTokens.newOpaqueToken();
        store.createVerification(id, SecurityTokens.digest(token), now().plusDays(1));
        events.publishEvent(new IdentityMail.Mail(email, "Welcome to PowerBill GridIQ",
                "Verify your email address to finish setting up your GridIQ account.",
                "/auth/verify-email", token));
        return id;
    }

    @Transactional
    Optional<Session> login(String rawEmail, String password, RequestContext request) {
        String email = normalize(rawEmail);
        throttle.acquire("login", request, email, 5);
        Optional<IdentityStore.User> match = store.byEmail(email, true);
        boolean validPassword = passwords.matches(password, match.map(IdentityStore.User::passwordHash)
                .orElse(dummyPasswordHash));
        if (match.isEmpty()) {
            audit("LOGIN_REJECTED", null, null, request, Map.of());
            return Optional.empty();
        }
        IdentityStore.User user = match.get();
        boolean locked = user.lockedUntil() != null && user.lockedUntil().isAfter(now());
        if (!SecurityTokens.canAuthenticate(user, environment) || locked) {
            audit("LOGIN_REJECTED", user.id(), user.role(), request, Map.of());
            return Optional.empty();
        }
        if (!validPassword) {
            int failures = user.lockedUntil() != null ? 1 : user.failedLoginAttempts() + 1;
            store.loginFailure(user.id(), failures, failures >= 5 ? now().plusMinutes(1) : null);
            audit("LOGIN_REJECTED", user.id(), user.role(), request, Map.of());
            return Optional.empty();
        }
        store.loginFailure(user.id(), 0, null);
        audit("LOGIN_SUCCEEDED", user.id(), user.role(), request, Map.of());
        return Optional.of(newSession(user, UUID.randomUUID(), now().plusDays(7), request).session());
    }

    @Transactional
    Optional<Session> refresh(String rawToken, RequestContext request) {
        throttle.acquire("refresh", request, "", 60);
        if (rawToken == null || !rawToken.matches("[A-Za-z0-9_-]{43}")) {
            return Optional.empty();
        }
        String hash = SecurityTokens.digest(rawToken);
        Optional<IdentityStore.Refresh> initial = store.refresh(hash);
        if (initial.isEmpty()) {
            return Optional.empty();
        }
        IdentityStore.User user = store.byId(initial.get().userId(), true).orElseThrow();
        IdentityStore.Refresh old = store.refresh(hash).orElseThrow();
        if (!old.expiresAt().isAfter(now()) || !SecurityTokens.canAuthenticate(user, environment)) {
            return Optional.empty();
        }
        if (old.revokedAt() != null) {
            if (old.replacedById() != null) {
                revoke(old.familyId());
                audit("REFRESH_REUSE_REVOKED", user.id(), user.role(), request, Map.of());
            }
            return Optional.empty();
        }
        IssuedSession issued = newSession(user, old.familyId(), old.expiresAt(), request);
        store.replaceRefresh(old.id(), issued.id());
        audit("SESSION_REFRESHED", user.id(), user.role(), request, Map.of());
        return Optional.of(issued.session());
    }

    @Transactional
    void logout(String rawToken, RequestContext request) {
        if (rawToken == null || !rawToken.matches("[A-Za-z0-9_-]{43}")) {
            return;
        }
        store.refresh(SecurityTokens.digest(rawToken)).ifPresent(token -> {
            IdentityStore.User user = store.byId(token.userId(), true).orElseThrow();
            revoke(token.familyId());
            audit("SESSION_LOGGED_OUT", user.id(), user.role(), request, Map.of());
        });
    }

    @Transactional
    void forgotPassword(String rawEmail, RequestContext request) {
        String email = normalize(rawEmail);
        throttle.acquire("forgot", request, "", 5);
        store.byEmail(email, true).filter(user -> SecurityTokens.canAuthenticate(user, environment)).ifPresent(user -> {
            String token = SecurityTokens.newOpaqueToken();
            store.createReset(user.id(), SecurityTokens.digest(token), now().plusMinutes(20));
            events.publishEvent(new IdentityMail.Mail(email, "Reset your GridIQ password",
                    "Use this link within 20 minutes to reset your password.", "/auth/reset-password", token));
            audit("PASSWORD_RESET_REQUESTED", user.id(), user.role(), request, Map.of());
        });
    }

    @Transactional
    void resetPassword(String rawToken, String password, RequestContext request) {
        throttle.acquire("reset", request, "", 10);
        String hash = SecurityTokens.digest(rawToken);
        var user = store.resetUser(hash).flatMap(userId -> store.byId(userId, true))
                .filter(account -> SecurityTokens.canAuthenticate(account, environment))
                .orElseThrow(IdentityService::invalidLink);
        if (!store.takeReset(hash)) {
            throw invalidLink();
        }
        store.changePassword(user.id(), passwords.encode(password));
        audit("PASSWORD_RESET", user.id(), user.role(), request, Map.of());
    }

    @Transactional
    void verifyEmail(String rawToken, RequestContext request) {
        throttle.acquire("verify", request, "", 10);
        UUID id = store.verifyEmail(SecurityTokens.digest(rawToken)).orElseThrow(IdentityService::invalidLink);
        store.markVerified(id);
        audit("EMAIL_VERIFIED", id, "CUSTOMER", request, Map.of());
    }

    @Transactional
    void changePassword(UUID id, String current, String replacement, RequestContext request) {
        throttle.acquire("change-password", request, id.toString(), 5);
        IdentityStore.User user = store.byId(id, true).orElseThrow();
        if (!passwords.matches(current, user.passwordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password is incorrect.");
        }
        store.changePassword(id, passwords.encode(replacement));
        audit("PASSWORD_CHANGED", id, user.role(), request, Map.of());
    }

    Profile me(UUID userId) {
        return profile(store.byId(userId, false).orElseThrow());
    }

    @Transactional
    Profile preferences(UUID id, String theme, Boolean alerts, RequestContext request) {
        IdentityStore.User existing = store.byId(id, true).orElseThrow();
        store.preferences(id, theme, alerts);
        audit("PREFERENCES_UPDATED", id, existing.role(), request, Map.of());
        return me(id);
    }

    private IssuedSession newSession(IdentityStore.User user, UUID family, OffsetDateTime expires,
            RequestContext request) {
        UUID id = UUID.randomUUID();
        String token = SecurityTokens.newOpaqueToken();
        store.insertRefresh(id, user.id(), family, SecurityTokens.digest(token), expires,
                request.userAgent(), request.remoteAddress());
        String jwt = SecurityTokens.accessToken(encoder, user, family, expires.toInstant());
        return new IssuedSession(id, new Session(jwt, token, expires, profile(user)));
    }

    private void revoke(UUID family) {
        store.revokeFamily(family);
        events.publishEvent(new SessionRevocations.FamilyRevoked(family));
    }

    private void audit(String action, UUID user, String role, RequestContext request, Map<String, Object> changes) {
        events.publishEvent(AuditAction.record(action, "USER", user, user, role, request, changes));
    }

    private static Profile profile(IdentityStore.User user) {
        return new Profile(user.id(), user.email(), user.fullName(), user.mobileNumber(), user.role(),
                user.emailVerified(), user.themePreference(), user.budgetAlerts());
    }

    private static ResponseStatusException invalidLink() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "This link is invalid or expired.");
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    private static String normalize(String rawEmail) {
        return rawEmail.strip().toLowerCase(Locale.ROOT);
    }

    record Profile(UUID id, String email, String fullName, String mobileNumber, String role, boolean emailVerified,
            String themePreference, boolean budgetAlerts) {}
    record Session(String accessToken, String refreshToken, OffsetDateTime expiresAt, Profile user) {}
    record IssuedSession(UUID id, Session session) {}
}
