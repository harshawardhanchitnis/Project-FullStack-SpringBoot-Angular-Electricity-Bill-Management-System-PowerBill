package com.powerbill.payment.internal;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import com.powerbill.billing.BillingLedger;
import com.powerbill.common.RequestContext;
import com.powerbill.consumer.AccountDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Backfills on-time demo payments through the real checkout and signed-webhook flow. */
@Component
@Profile("dev & !prod")
@ConditionalOnProperty(name = "gridiq.demo.enabled", havingValue = "true")
@Order(300)
class DemoPayments implements ApplicationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(DemoPayments.class);
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final List<String> METHODS = List.of("UPI", "CREDIT_CARD", "NET_BANKING", "DEBIT_CARD");
    private final PaymentService payments;
    private final SandboxGateway sandbox;
    private final BillingLedger billing;
    private final AccountDirectory accounts;

    DemoPayments(PaymentService payments, SandboxGateway sandbox, BillingLedger billing,
            AccountDirectory accounts) {
        this.payments = payments;
        this.sandbox = sandbox;
        this.billing = billing;
        this.accounts = accounts;
    }

    @Override
    public void run(ApplicationArguments args) {
        var demoAccounts = new LinkedHashMap<UUID, AccountDirectory.Account>();
        accounts.billableServicePoints().stream().filter(point -> point.meterSerial().startsWith("M-DEMO-"))
                .forEach(point -> demoAccounts.putIfAbsent(point.account().id(), point.account()));
        YearMonth current = YearMonth.now(INDIA);
        int paid = 0;
        int index = 0;
        for (var account : demoAccounts.values()) {
            index++;
            // Two meters times twelve months: every demo invoice, so none older than the window is left unpaid.
            for (var bill : billing.recentBills(account.id(), 24)) {
                boolean settle = bill.period().isBefore(current.minusMonths(2))
                        || (bill.period().equals(current.minusMonths(2)) && index % 2 == 0);
                if (!settle || bill.outstanding().signum() == 0 || bill.status().equals("PAID")) {
                    continue;
                }
                LocalDate payOn = bill.dueDate().minusDays(2 + index % 5);
                Instant at = payOn.atTime(10 + index % 8, 15).atZone(INDIA).toInstant();
                UUID key = UUID.nameUUIDFromBytes(("gridiq-demo-payment:" + bill.id()).getBytes(StandardCharsets.UTF_8));
                try {
                    var checkout = payments.checkout(account, account.userId(), List.of(bill.id()),
                            METHODS.get(index % METHODS.size()), key, payOn, at.minusSeconds(120),
                            new RequestContext("gridiq-demo-seed", null, "seed"));
                    var result = sandbox.confirm(account.id(), checkout.payment().id(), "SUCCEEDED", at);
                    if (result.status().equals("SUCCEEDED")) {
                        paid++;
                    }
                } catch (RuntimeException failure) {
                    LOG.info("Skipped a demo payment ({})", failure.getMessage());
                }
            }
        }
        LOG.info("Settled {} demo payments", paid);
    }
}
