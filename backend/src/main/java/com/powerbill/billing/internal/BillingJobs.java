package com.powerbill.billing.internal;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

import com.powerbill.consumer.AccountDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Scheduled receivable maintenance and the automatic monthly billing cycle. */
@Component
class BillingJobs {
    private static final Logger LOG = LoggerFactory.getLogger(BillingJobs.class);
    private final BillingService billing;

    BillingJobs(BillingService billing) {
        this.billing = billing;
    }

    @Scheduled(cron = "${gridiq.billing.overdue-cron:0 10 0 * * *}", zone = "Asia/Kolkata")
    void markOverdue() {
        int changed = billing.markOverdue();
        if (changed > 0) {
            LOG.info("Marked {} bills overdue", changed);
        }
    }

    @Scheduled(cron = "${gridiq.billing.cycle-cron:0 0 2 1 * *}", zone = "Asia/Kolkata")
    void monthlyCycle() {
        YearMonth month = YearMonth.now(BillingService.INDIA).minusMonths(1);
        UUID key = UUID.nameUUIDFromBytes(("gridiq-auto-billing:" + month).getBytes(StandardCharsets.UTF_8));
        var result = billing.run(month, false, key, null, "SYSTEM", BillingService.SYSTEM);
        LOG.info("Automatic billing run for {} finished with {} errors", month, result.errors().size());
    }
}

/** Issues six months of historical demo invoices from the simulated telemetry. */
@Component
@Profile("dev & !prod")
@ConditionalOnProperty(name = "gridiq.demo.enabled", havingValue = "true")
@Order(200)
class DemoBilling implements ApplicationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(DemoBilling.class);
    private final BillingService billing;
    private final BillingStore store;
    private final AccountDirectory accounts;
    private final TransactionTemplate transactions;

    DemoBilling(BillingService billing, BillingStore store, AccountDirectory accounts, TransactionTemplate transactions) {
        this.billing = billing;
        this.store = store;
        this.accounts = accounts;
        this.transactions = transactions;
    }

    @Override
    public void run(ApplicationArguments args) {
        YearMonth current = YearMonth.now(BillingService.INDIA);
        int issued = 0;
        // A full year of invoices, so the executive revenue chart and each customer's history have no empty months.
        for (int back = 12; back >= 1; back--) {
            YearMonth month = current.minusMonths(back);
            // Issue each bill on the second day after its period so historical due dates look realistic.
            LocalDate issueDate = month.plusMonths(1).atDay(2);
            for (var point : accounts.billableServicePoints()) {
                if (!point.meterSerial().startsWith("M-DEMO-")
                        || store.exists(point.meterId(), month.getYear(), month.getMonthValue())) {
                    continue;
                }
                try {
                    Boolean created = transactions.execute(status -> billing.issue(billing.draft(point, month),
                            issueDate, null, null, "SYSTEM", BillingService.SYSTEM).isPresent());
                    if (Boolean.TRUE.equals(created)) {
                        issued++;
                    }
                } catch (BillingService.BillingProblem problem) {
                    LOG.info("Skipped demo bill for {} {} ({})", point.meterSerial(), month, problem.code);
                }
            }
        }
        billing.markOverdue();
        LOG.info("Issued {} demo bills", issued);
    }
}
