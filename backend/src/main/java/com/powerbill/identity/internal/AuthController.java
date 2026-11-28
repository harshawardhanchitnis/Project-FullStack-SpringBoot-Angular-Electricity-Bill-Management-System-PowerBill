package com.powerbill.identity.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.powerbill.common.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/auth")
class AuthController {
    static final String REFRESH_COOKIE = "gridiq_refresh";
    private final IdentityService identity;
    private final boolean secure;

    AuthController(IdentityService identity, @Value("${gridiq.auth.cookie-secure}") boolean secure) {
        this.identity = identity;
        this.secure = secure;
    }

    @PostMapping("/login")
    ResponseEntity<TokenResponse> login(@Valid @RequestBody Login body, HttpServletRequest request) {
        var session = identity.login(body.email(), body.password(), RequestContext.from(request))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password."));
        return session(session);
    }

    @PostMapping("/refresh")
    ResponseEntity<TokenResponse> refresh(@CookieValue(name = REFRESH_COOKIE, required = false) String token,
            @RequestHeader(name = "X-GridIQ-Session", required = false) String sessionHeader,
            HttpServletRequest request) {
        requireSessionHeader(sessionHeader);
        if (token == null || token.isEmpty()) {
            // No refresh cookie means there is no session to restore: not an error for a fresh page load.
            return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
        }
        var session = identity.refresh(token, RequestContext.from(request))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "The session has expired. Sign in again."));
        return session(session);
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(@CookieValue(name = REFRESH_COOKIE, required = false) String token,
            @RequestHeader(name = "X-GridIQ-Session", required = false) String sessionHeader,
            HttpServletRequest request) {
        requireSessionHeader(sessionHeader);
        identity.logout(token, RequestContext.from(request));
        return clearCookie();
    }

    @PostMapping("/forgot-password")
    ResponseEntity<Void> forgot(@Valid @RequestBody EmailRequest body, HttpServletRequest request) {
        identity.forgotPassword(body.email(), RequestContext.from(request));
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/reset-password")
    ResponseEntity<Void> reset(@Valid @RequestBody Reset body, HttpServletRequest request) {
        identity.resetPassword(body.token(), body.password(), RequestContext.from(request));
        return clearCookie();
    }

    @PostMapping("/verify-email")
    ResponseEntity<Void> verify(@Valid @RequestBody Verify body, HttpServletRequest request) {
        identity.verifyEmail(body.token(), RequestContext.from(request));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/change-password")
    ResponseEntity<Void> change(@AuthenticationPrincipal Jwt principal, @Valid @RequestBody PasswordChange body,
            HttpServletRequest request) {
        identity.changePassword(subject(principal), body.currentPassword(), body.newPassword(),
                RequestContext.from(request));
        return clearCookie();
    }

    @GetMapping("/me")
    ResponseEntity<IdentityService.Profile> me(@AuthenticationPrincipal Jwt principal) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(identity.me(subject(principal)));
    }

    @PatchMapping("/me/preferences")
    ResponseEntity<IdentityService.Profile> preferences(@AuthenticationPrincipal Jwt principal,
            @Valid @RequestBody Preferences body, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(identity.preferences(subject(principal), body.theme(), body.budgetAlerts(),
                        RequestContext.from(request)));
    }

    private ResponseEntity<TokenResponse> session(IdentityService.Session session) {
        Duration maxAge = Duration.between(Instant.now(), session.expiresAt().toInstant());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, cookie(session.refreshToken(), maxAge).toString())
                .body(new TokenResponse(session.accessToken(), "Bearer", SecurityTokens.ACCESS_SECONDS, session.user()));
    }

    private ResponseEntity<Void> clearCookie() {
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString()).build();
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE, value).httpOnly(true).secure(secure)
                .sameSite("Lax").path("/api/v1/auth").maxAge(maxAge).build();
    }

    private static UUID subject(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    private static void requireSessionHeader(String header) {
        if (!"1".equals(header)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "A session request header is required.");
        }
    }

    record Login(@NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 128) String password) {}
    record EmailRequest(@NotBlank @Email @Size(max = 254) String email) {}
    record Verify(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String token) {}
    record Reset(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String token,
            @NotBlank @Size(min = 12, max = 128) String password) {}
    record PasswordChange(@NotBlank @Size(max = 128) String currentPassword,
            @NotBlank @Size(min = 12, max = 128) String newPassword) {}
    record Preferences(@Pattern(regexp = "SYSTEM|LIGHT|DARK") String theme, Boolean budgetAlerts) {}
    record TokenResponse(String accessToken, String tokenType, long expiresIn, IdentityService.Profile user) {}
}
