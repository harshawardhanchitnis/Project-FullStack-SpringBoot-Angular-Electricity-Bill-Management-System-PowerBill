package com.powerbill.identity.internal;

import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("dev & !prod")
@ConditionalOnProperty(name = "gridiq.demo.enabled", havingValue = "true")
@org.springframework.core.annotation.Order(5)
class DemoStaff implements ApplicationRunner {
    private static final Map<String, String> ROLES = Map.of("billing", "BILLING_OFFICER",
            "support", "SUPPORT_AGENT", "field", "FIELD_ENGINEER", "admin", "SUPER_ADMIN");
    private final JdbcClient db;
    private final IdentityStore store;
    private final PasswordEncoder encoder;
    private final String password;

    DemoStaff(JdbcClient db, IdentityStore store, PasswordEncoder encoder,
            @Value("${gridiq.demo.password}") String password) {
        this.db = db;
        this.store = store;
        this.encoder = encoder;
        if (password.isBlank() || password.length() < 12 || password.length() > 128) {
            throw new IllegalStateException("Set DEMO_PASSWORD to a password of 12–128 characters.");
        }
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        db.sql("SELECT pg_advisory_xact_lock(1249902022)").query((rs, row) -> row).single();
        ROLES.forEach((name, role) -> {
            String email = name + "@demo.powerbill.invalid";
            var existing = store.byEmail(email, true);
            if (existing.isEmpty()) {
                store.insert(UUID.randomUUID(), email, encoder.encode(password), "Demo " + name,
                        "9000000099", role, true);
            } else if (!existing.get().role().equals(role)) {
                throw new IllegalStateException("A demo staff email is already registered.");
            }
        });
    }
}
