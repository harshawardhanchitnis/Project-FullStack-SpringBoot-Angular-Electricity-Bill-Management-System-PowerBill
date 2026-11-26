package com.powerbill.support.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.powerbill.billing.BillingLedger;
import com.powerbill.common.RequestContext;
import com.powerbill.consumer.AccountDirectory;
import com.powerbill.identity.StaffDirectory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Seeds a realistic support desk: tickets in every lifecycle state and two outage incidents. */
@Component
@Profile("dev & !prod")
@ConditionalOnProperty(name = "gridiq.demo.enabled", havingValue = "true")
@Order(400)
class DemoSupport implements ApplicationRunner {
    private static final RequestContext SEED = new RequestContext("gridiq-demo-seed", null, "seed");
    private final SupportService support;
    private final AccountDirectory accounts;
    private final BillingLedger billing;
    private final StaffDirectory staff;
    private final JdbcClient db;

    DemoSupport(SupportService support, AccountDirectory accounts, BillingLedger billing, StaffDirectory staff,
            JdbcClient db) {
        this.support = support;
        this.accounts = accounts;
        this.billing = billing;
        this.staff = staff;
        this.db = db;
    }

    @Override
    public void run(ApplicationArguments args) {
        db.sql("SELECT pg_advisory_lock(1249902030)").query((rs, row) -> row).single();
        try {
            if (db.sql("SELECT count(*) FROM complaints c JOIN consumer_accounts a ON a.id=c.consumer_account_id "
                    + "WHERE a.consumer_number LIKE 'PB-DEMO-%'").query(Long.class).single() > 0) {
                return;
            }
            seed();
        } finally {
            db.sql("SELECT pg_advisory_unlock(1249902030)").query((rs, row) -> row).single();
        }
    }

    private void seed() {
        var demo = new LinkedHashMap<String, AccountDirectory.Account>();
        accounts.billableServicePoints().forEach(point -> demo.putIfAbsent(point.account().consumerNumber(),
                point.account()));
        UUID agent = staff.staffByEmail("support@demo.powerbill.invalid").orElseThrow().id();
        UUID field = staff.staffByEmail("field@demo.powerbill.invalid").orElseThrow().id();
        Instant now = Instant.now();

        var bill = ticket(demo, "01", "BILLING", "Bill higher than usual last month",
                "My last bill rose sharply compared to the month before although our usage pattern did not change. "
                        + "Please check the slab calculation.", now.minus(Duration.ofHours(20)), true);
        progress(bill, agent, "IN_PROGRESS", null);
        support.staffComment(bill.id(), agent, "SUPPORT_AGENT", true,
                "Usage crossed into slab 3 during the heat wave. Compare ToD peak share before replying.", SEED);
        support.staffComment(bill.id(), agent, "SUPPORT_AGENT", false,
                "We are reviewing the interval data for your meter and will update you shortly.", SEED);

        ticket(demo, "06", "POWER_OUTAGE", "No electricity supply since the morning",
                "Power has been off in our building since early morning. Neighbours are affected too.",
                now.minus(Duration.ofHours(5)), false);

        var voltage = ticket(demo, "10", "VOLTAGE_FLUCTUATION", "Voltage drops in the evening at the shop",
                "Lights flicker and the refrigeration compressor trips between 7 pm and 9 pm on most days.",
                now.minus(Duration.ofHours(30)), false);
        progress(voltage, field, null, null);

        var meter = ticket(demo, "13", "METER_FAULT", "Meter display is blank",
                "The smart meter display has been blank for two days, but the supply is normal.",
                now.minus(Duration.ofDays(4)), false);
        progress(meter, field, "IN_PROGRESS", null);
        progress(meter, field, "RESOLVED", "Replaced the faulty display module. Interval data was unaffected.");

        var solar = ticket(demo, "12", "SOLAR_NET_METERING", "Solar export credit not shown",
                "Export units from my rooftop solar are not reflected in the latest bill.",
                now.minus(Duration.ofDays(9)), true);
        progress(solar, agent, "IN_PROGRESS", null);
        progress(solar, agent, "RESOLVED",
                "Export credit was carried forward in kWh under the net metering policy and appears on the invoice.");
        var customer = demo.get("PB-DEMO-12");
        support.rate(customer, customer.userId(), solar.id(), 5, "Clear explanation, thank you.", SEED);

        ticket(demo, "09", "CONNECTION", "Request to increase sanctioned load",
                "We are installing an EV charger and need the sanctioned load increased from 12 kW to 15 kW.",
                now.minus(Duration.ofHours(2)), false);

        var feeder = support.declare(new SupportController.NewOutage("Pune", "Pune", List.of("411001"),
                "Feeder trip at Camp substation",
                "An 11 kV feeder tripped after a cable fault. Crews are restoring supply section by section.",
                "UNPLANNED", now.plus(Duration.ofHours(2))), agent, "SUPPORT_AGENT", SEED);
        support.changeOutage(feeder.id(), new SupportController.OutageUpdate("RESTORING", null, null,
                feeder.version()), field, "FIELD_ENGINEER", SEED);
        support.declare(new SupportController.NewOutage("Mumbai", "Mumbai", List.of("400001"),
                "Planned transformer maintenance", "Supply will be interrupted for scheduled maintenance.",
                "PLANNED", now.plus(Duration.ofHours(30))), agent, "SUPPORT_AGENT", SEED);
        support.flagBreaches();
    }

    private SupportStore.Complaint ticket(Map<String, AccountDirectory.Account> demo, String index, String type,
            String subject, String description, Instant createdAt, boolean linkBill) {
        var account = demo.get("PB-DEMO-" + index);
        UUID billId = linkBill ? billing.recentBills(account.id(), 1).stream().findFirst()
                .map(BillingLedger.BillHeader::id).orElse(null) : null;
        UUID meterId = accounts.servicePoints(account.id()).getFirst().meterId();
        return support.create(account, account.userId(), new SupportController.NewComplaint(type, subject, description,
                null, meterId, billId), createdAt, SEED);
    }

    private SupportStore.Complaint progress(SupportStore.Complaint complaint, UUID agent, String status,
            String resolution) {
        var current = support.find(complaint.id());
        return support.staffUpdate(complaint.id(), new SupportController.StaffUpdate(status, agent, false, null,
                resolution, current.version()), agent, "SUPPORT_AGENT", SEED);
    }
}
