package com.powerbill.identity.internal;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.powerbill.audit.AuditAction;
import com.powerbill.common.RequestContext;
import com.powerbill.identity.StaffDirectory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/ops")
class StaffController {
    static final String STAFF_ROLES = "BILLING_OFFICER|SUPPORT_AGENT|FIELD_ENGINEER|SUPER_ADMIN";
    private final StaffService staff;

    StaffController(StaffService staff) {
        this.staff = staff;
    }

    @GetMapping("/staff")
    List<StaffDirectory.StaffMember> assignable(@RequestParam(required = false) String role) {
        if (role != null && !role.matches(STAFF_ROLES)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown staff role.");
        }
        return staff.activeStaff(role == null ? Set.of("SUPPORT_AGENT", "FIELD_ENGINEER", "SUPER_ADMIN") : Set.of(role));
    }

    @GetMapping("/users")
    StaffService.Page users(@RequestParam(defaultValue = "") String q, @RequestParam(required = false) String role,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > 100 || q.length() > 254
                || (role != null && !role.matches(STAFF_ROLES + "|CUSTOMER"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid filters or paging.");
        }
        return staff.users(q.strip().toLowerCase(Locale.ROOT), role, page, size);
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    StaffService.UserRow create(@Valid @RequestBody NewStaff input, @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request) {
        return staff.create(input, UUID.fromString(jwt.getSubject()), RequestContext.from(request));
    }

    @PatchMapping("/users/{id}")
    StaffService.UserRow update(@PathVariable UUID id, @Valid @RequestBody StaffChange input,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        return staff.change(id, input, UUID.fromString(jwt.getSubject()), RequestContext.from(request));
    }

    record NewStaff(@NotBlank @Email @Size(max = 254) String email, @NotBlank @Size(max = 120) String fullName,
            @NotBlank @Pattern(regexp = "[6-9][0-9]{9}") String mobileNumber,
            @NotBlank @Pattern(regexp = STAFF_ROLES) String role,
            @NotBlank @Size(min = 12, max = 128) String temporaryPassword) {}

    record StaffChange(@Pattern(regexp = STAFF_ROLES) String role, @Pattern(regexp = "ACTIVE|DISABLED") String status,
            @NotNull @PositiveOrZero Integer sessionVersion) {}
}

@Service
class StaffService implements StaffDirectory, com.powerbill.identity.UserDirectory {
    private static final String FIELDS = "id, email, full_name, role, status";
    private final JdbcClient db;
    private final IdentityStore store;
    private final PasswordEncoder passwords;
    private final ApplicationEventPublisher events;

    StaffService(JdbcClient db, IdentityStore store, PasswordEncoder passwords, ApplicationEventPublisher events) {
        this.db = db;
        this.store = store;
        this.passwords = passwords;
        this.events = events;
    }

    @Override
    public Optional<StaffMember> staffMember(UUID id) {
        return db.sql("SELECT " + FIELDS + " FROM users WHERE id=? AND role<>'CUSTOMER'").param(id)
                .query(StaffMember.class).optional();
    }

    @Override
    public Optional<StaffMember> staffByEmail(String email) {
        return db.sql("SELECT " + FIELDS + " FROM users WHERE email=? AND role<>'CUSTOMER'")
                .param(email.toLowerCase(Locale.ROOT)).query(StaffMember.class).optional();
    }

    @Override
    public List<StaffMember> activeStaff(Collection<String> roles) {
        return db.sql("SELECT " + FIELDS + " FROM users WHERE role IN (:roles) AND status='ACTIVE' ORDER BY full_name, id")
                .param("roles", List.copyOf(roles)).query(StaffMember.class).list();
    }

    @Override
    public Optional<Contact> contact(UUID userId) {
        return db.sql("SELECT id, email, full_name, role, budget_alerts FROM users WHERE id=? AND status='ACTIVE'")
                .param(userId).query(Contact.class).optional();
    }

    Page users(String query, String role, int page, int size) {
        String filter = """
                 WHERE (CAST(:role AS text) IS NULL OR role=:role)
                   AND (:query='' OR strpos(lower(email),:query)>0 OR strpos(lower(full_name),:query)>0)
                """;
        var rows = db.sql("""
                SELECT id, email, full_name, mobile_number, role, status, email_verified, session_version, created_at
                FROM users""" + filter + " ORDER BY role='CUSTOMER', created_at DESC, id LIMIT :size OFFSET :offset")
                .param("role", role, Types.VARCHAR).param("query", query).param("size", size)
                .param("offset", (long) page * size).query(UserRow.class).list();
        long total = db.sql("SELECT count(*) FROM users" + filter).param("role", role, Types.VARCHAR)
                .param("query", query).query(Long.class).single();
        return new Page(rows, page, size, total);
    }

    @Transactional
    UserRow create(StaffController.NewStaff input, UUID actor, RequestContext request) {
        String email = input.email().strip().toLowerCase(Locale.ROOT);
        if (email.endsWith(SecurityTokens.DEMO_DOMAIN)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use a non-demo email address.");
        }
        UUID id = UUID.randomUUID();
        if (!store.insert(id, email, passwords.encode(input.temporaryPassword()), input.fullName().strip(),
                input.mobileNumber(), input.role(), true)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An account with this email already exists.");
        }
        events.publishEvent(AuditAction.record("STAFF_CREATED", "USER", id, actor, "SUPER_ADMIN", request,
                Map.of("role", input.role())));
        return row(id);
    }

    @Transactional
    UserRow change(UUID id, StaffController.StaffChange input, UUID actor, RequestContext request) {
        if (id.equals(actor)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You cannot change your own role or status.");
        }
        var user = store.byId(id, true).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "User was not found."));
        if (user.sessionVersion() != input.sessionVersion()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The user changed. Reload and try again.");
        }
        if (input.role() != null && user.role().equals("CUSTOMER")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Customer accounts cannot be given staff roles.");
        }
        String role = input.role() == null ? user.role() : input.role();
        String status = input.status() == null ? user.status() : input.status();
        db.sql("""
                UPDATE users SET role=?, status=?, session_version=session_version+1, updated_at=now() WHERE id=?
                """).params(role, status, id).update();
        db.sql("UPDATE refresh_tokens SET revoked_at=now() WHERE user_id=? AND revoked_at IS NULL").param(id).update();
        events.publishEvent(AuditAction.record("USER_ACCESS_CHANGED", "USER", id, actor, "SUPER_ADMIN", request,
                Map.of("fromRole", user.role(), "toRole", role, "fromStatus", user.status(), "toStatus", status)));
        return row(id);
    }

    private UserRow row(UUID id) {
        return db.sql("""
                SELECT id, email, full_name, mobile_number, role, status, email_verified, session_version, created_at
                FROM users WHERE id=?
                """).param(id).query(UserRow.class).single();
    }

    record UserRow(UUID id, String email, String fullName, String mobileNumber, String role, String status,
            boolean emailVerified, int sessionVersion, OffsetDateTime createdAt) {}
    record Page(List<UserRow> content, int page, int size, long totalElements) {}
}
