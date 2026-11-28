package com.powerbill.payment.internal;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.powerbill.billing.BillingEvents;
import com.powerbill.billing.BillingLedger;
import com.powerbill.common.RequestContext;
import com.powerbill.consumer.AccountDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Settles bills for accounts with an auto-pay mandate. Each bill uses a fixed idempotency key derived from its ID,
 * so a retry, a duplicate event or the daily sweep can never charge it twice. The charge goes through the gateway's
 * signed webhook path exactly like a customer checkout, and each bill runs in its own transaction.
 */
@Service
class AutoPayService {
    private static final Logger LOG = LoggerFactory.getLogger(AutoPayService.class);
    private static final Set<String> PAYABLE = Set.of("ISSUED", "OVERDUE", "PARTIALLY_PAID");
    private static final RequestContext SYSTEM = new RequestContext("auto-pay", null, "gridiq-auto-pay");
    private final PaymentService payments;
    private final SandboxGateway sandbox;
    private final AccountDirectory accounts;
    private final BillingLedger billing;
    private final TransactionTemplate perBill;

    AutoPayService(PaymentService payments, SandboxGateway sandbox, AccountDirectory accounts, BillingLedger billing,
            PlatformTransactionManager transactions) {
        this.payments = payments;
        this.sandbox = sandbox;
        this.accounts = accounts;
        this.billing = billing;
        this.perBill = new TransactionTemplate(transactions);
        this.perBill.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Not {@code @ApplicationModuleListener}: that opens a transaction around this method, and {@link #settle} opens
     * its own per bill, so each charge would hold two pooled connections and a busy pool could starve it. Spring
     * Modulith still records and completes the event publication for this transactional listener.
     */
    @Async
    @TransactionalEventListener
    void on(BillingEvents.BillIssued event) {
        if (accounts.autoPayEnabled(event.accountId())) {
            settle(event.accountId(), event.billId());
        }
    }

    /** Daily catch-up for bills issued before the mandate was set up or while a charge could not complete. */
    @Scheduled(cron = "${gridiq.payments.auto-pay-cron:0 30 6 * * *}", zone = "Asia/Kolkata")
    void sweep() {
        int settled = run();
        if (settled > 0) {
            LOG.info("Auto-pay settled {} bills", settled);
        }
    }

    /** Settles every open bill on accounts with a mandate; returns how many bills were paid. */
    int run() {
        int settled = 0;
        for (UUID accountId : accounts.autoPayAccounts()) {
            for (var bill : billing.recentBills(accountId, 24)) {
                if (PAYABLE.contains(bill.status()) && bill.outstanding().compareTo(BigDecimal.ZERO) > 0
                        && settle(accountId, bill.id())) {
                    settled++;
                }
            }
        }
        return settled;
    }

    /** Pays one bill with the account's mandate. Returns true when this call settled it. */
    boolean settle(UUID accountId, UUID billId) {
        var account = accounts.findAccount(accountId).filter(item -> item.status().equals("ACTIVE"));
        if (account.isEmpty()) {
            return false;
        }
        UUID key = UUID.nameUUIDFromBytes(("gridiq:auto-pay:" + billId).getBytes(StandardCharsets.UTF_8));
        try {
            return Boolean.TRUE.equals(perBill.execute(status -> {
                Instant now = Instant.now();
                var checkout = payments.checkout(account.get(), account.get().userId(), List.of(billId), "AUTO_PAY",
                        key, PaymentSettlement.today(), now, SYSTEM);
                if (checkout.replayed() || !checkout.payment().status().equals("INITIATED")) {
                    return false; // this bill was already charged under its mandate key
                }
                var payment = sandbox.confirm(accountId, checkout.payment().id(), "SUCCEEDED", now);
                return payment.status().equals("SUCCEEDED");
            }));
        } catch (RuntimeException skipped) {
            // A payment in progress, a gateway decline or a database error: nothing was committed for this bill, and
            // the daily sweep tries again. Logged at WARN because an unexplained missed charge is a support case.
            LOG.warn("Auto-pay did not settle bill {} ({}: {})", billId, skipped.getClass().getSimpleName(),
                    skipped.getMessage());
            return false;
        }
    }
}
