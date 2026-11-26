package com.powerbill.consumer.internal;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.UUID;

import com.powerbill.identity.CustomerIdentity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("dev & !prod")
@ConditionalOnProperty(name = "gridiq.demo.enabled", havingValue = "true")
class DemoConsumers implements ApplicationRunner {
    private final JdbcClient db;
    private final ConsumerStore store;
    private final CustomerIdentity identity;
    private final String password;

    DemoConsumers(JdbcClient db, ConsumerStore store, CustomerIdentity identity,
            @Value("${gridiq.demo.password}") String password) {
        this.db = db;
        this.store = store;
        this.identity = identity;
        if (password.isBlank() || password.length() < 12 || password.length() > 128) {
            throw new IllegalStateException("Set DEMO_PASSWORD to a password of 12–128 characters.");
        }
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        db.sql("SELECT pg_advisory_xact_lock(1249902021)").query((rs, row) -> row).single();
        for (int i = 1; i <= 15; i++) {
            seed(i);
        }
    }

    private void seed(int index) {
        String sequence = String.format(Locale.ROOT, "%02d", index);
        String number = "PB-DEMO-" + sequence;
        if (store.exists(number)) {
            return;
        }
        String city = index % 2 == 0 ? "Pune" : "Mumbai";
        String pincode = index % 2 == 0 ? "411001" : "400001";
        String type = index <= 9 ? "RESIDENTIAL" : index <= 13 ? "COMMERCIAL" : "INDUSTRIAL";
        String email = "customer" + sequence + "@demo.powerbill.invalid";
        String name = "Demo " + city + " " + sequence;
        String phone = "90000000" + sequence;
        UUID user = identity.seedDemoCustomer(email, password, name, phone);
        UUID account = UUID.randomUUID();
        store.insertAccount(account, user, number, name, type, email, phone);
        UUID premise = UUID.randomUUID();
        store.insertPremise(premise, account, new ConsumerRequests.Premise(
                "Primary service", sequence + " Demo Utility Road", city, "Maharashtra", pincode, city, null, null));
        String connection = type.equals("RESIDENTIAL") ? "DOMESTIC" : type;
        boolean solar = index % 3 == 0;
        store.insertMeter(UUID.randomUUID(), premise, "M-DEMO-" + sequence + "-1", connection,
                BigDecimal.valueOf(3L + index), solar, solar ? BigDecimal.valueOf(3) : null);
        if (index <= 10) {
            UUID secondPremise = UUID.randomUUID();
            store.insertPremise(secondPremise, account, new ConsumerRequests.Premise(
                    "Secondary service", sequence + " Demo Service Lane", city, "Maharashtra", pincode, city, null, null));
            store.insertMeter(UUID.randomUUID(), secondPremise, "M-DEMO-" + sequence + "-2", connection,
                    BigDecimal.valueOf(5), false, null);
        }
    }
}
