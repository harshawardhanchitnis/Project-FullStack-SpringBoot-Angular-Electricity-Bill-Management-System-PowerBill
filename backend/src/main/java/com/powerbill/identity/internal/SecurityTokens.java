package com.powerbill.identity.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@Configuration(proxyBeanMethods = false)
class SecurityTokens {

    static final String ISSUER = "powerbill-gridiq";
    static final String AUDIENCE = "gridiq-core-api";
    static final String DEMO_DOMAIN = "@demo.powerbill.invalid";
    static final long ACCESS_SECONDS = 900;
    private static final SecureRandom RANDOM = new SecureRandom();

    @Bean
    SecretKey gridiqSigningKey(Environment env,
            @Value("${gridiq.auth.signing-secret}") String configured,
            @Value("${gridiq.auth.local-signing-secret}") String local) {
        boolean localProfile = env.acceptsProfiles(Profiles.of("(dev | test) & !prod"));
        String secret = configured.isBlank() && localProfile ? local : configured;
        if (!secret.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalStateException("JWT_SECRET must contain 32 random bytes encoded as 64 hex digits.");
        }
        return new SecretKeySpec(HexFormat.of().parseHex(secret), "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey gridiqSigningKey) {
        return NimbusJwtEncoder.withSecretKey(gridiqSigningKey).algorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey gridiqSigningKey, IdentityStore identities, SessionRevocations revocations,
            AuthStateCache states, Environment environment) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(gridiqSigningKey)
                .macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(ISSUER),
                jwt -> {
                    UUID userId;
                    UUID family;
                    if (jwt.getExpiresAt() == null || jwt.getAudience() == null
                            || !jwt.getAudience().contains(AUDIENCE)) {
                        return invalid();
                    }
                    try {
                        userId = UUID.fromString(jwt.getSubject());
                        family = UUID.fromString(jwt.getClaimAsString("sid"));
                    } catch (IllegalArgumentException | NullPointerException error) {
                        return invalid();
                    }
                    Object version = jwt.getClaim("sv");
                    if (!(version instanceof Number sessionVersion) || revocations.contains(family)) {
                        return invalid();
                    }
                    // Both checks are served from Redis when cached; changes to them evict the entries.
                    return states.user(userId, () -> identities.byId(userId, false).map(AuthStateCache.State::of))
                            .filter(user -> canAuthenticate(user, environment)
                                    && user.sessionVersion() == sessionVersion.intValue()
                                    && user.role().equals(jwt.getClaimAsString("role")))
                            .filter(user -> states.familyActive(family, () -> identities.activeFamily(userId, family)))
                            .map(user -> OAuth2TokenValidatorResult.success())
                            .orElseGet(SecurityTokens::invalid);
                }));
        return decoder;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    static String accessToken(JwtEncoder encoder, IdentityStore.User user, UUID family, Instant familyExpiry) {
        Instant now = Instant.now();
        Instant expires = now.plusSeconds(ACCESS_SECONDS);
        if (expires.isAfter(familyExpiry)) {
            expires = familyExpiry;
        }
        var claims = JwtClaimsSet.builder().issuer(ISSUER).audience(List.of(AUDIENCE))
                .subject(user.id().toString()).issuedAt(now).expiresAt(expires)
                .claim("role", user.role()).claim("sid", family.toString())
                .claim("sv", user.sessionVersion()).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    static String newOpaqueToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static boolean canAuthenticate(IdentityStore.User user, Environment environment) {
        return canAuthenticate(AuthStateCache.State.of(user), environment);
    }

    static boolean canAuthenticate(AuthStateCache.State user, Environment environment) {
        return user.status().equals("ACTIVE") && (!user.demo()
                || (environment.acceptsProfiles(Profiles.of("dev & !prod"))
                    && environment.getProperty("gridiq.demo.enabled", Boolean.class, false)));
    }

    static String digest(String token) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable.", error);
        }
    }

    private static OAuth2TokenValidatorResult invalid() {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token",
                "The access token is invalid or its session has ended.", null));
    }
}
