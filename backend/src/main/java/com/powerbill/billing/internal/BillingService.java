package com.powerbill.billing.internal;

import static java.math.BigDecimal.ZERO;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.powerbill.audit.AuditAction;
import com.powerbill.billing.BillingEvents;
import com.powerbill.billing.BillingLedger;
import com.powerbill.billing.internal.BillingStore.BillRow;
import com.powerbill.billing.internal.BillingStore.Entry;
import com.powerbill.common.RequestContext;
import com.powerbill.consumer.AccountDirectory;
import com.powerbill.consumer.AccountDirectory.ServicePoint;
import com.powerbill.metering.MeterUsage;
import com.powerbill.tariff.TariffPlan;
import com.powerbill.tariff.TariffPricing;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
class BillingService implements BillingLedger, com.powerbill.billing.BillingStatistics {
    static final ZoneId INDIA = MeterUsage.BILLING_ZONE;
    static final int PAYMENT_TERM_DAYS = 15;
    static final RequestContext SYSTEM = new RequestContext("gridiq-billing-scheduler", null, "scheduler");
    private static final Logger LOG = LoggerFactory.getLogger(BillingService.class);

    private final BillingStore store;
    private final AccountDirectory accounts;
    private final MeterUsage usage;
    private final TariffPricing pricing;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final Counter billsGenerated;

    BillingService(BillingStore store, AccountDirectory accounts, MeterUsage usage, TariffPricing pricing,
            ApplicationEventPublisher events, TransactionTemplate transactions, ObjectMapper json,
            MeterRegistry metrics) {
        this.store = store;
        this.accounts = accounts;
        this.usage = usage;
        this.pricing = pricing;
        this.events = events;
        this.transactions = transactions;
        this.json = json;
        this.billsGenerated = Counter.builder("powerbill.bills.generated").register(metrics);
    }

    /** Prices one meter for one month. Persists the invoice when {@code persist} is true. */
    Draft draft(ServicePoint point, YearMonth month) {
        LocalDate start = month.atDay(1);
        LocalDate end = month.plusMonths(1).atDay(1);
        if (!month.isBefore(YearMonth.now(INDIA))) {
            throw new BillingProblem("PERIOD_OPEN", "Bills can be generated only for completed months.");
        }
        TariffPlan plan;
        try {
            plan = pricing.applicablePlan(point.tariffPlanId(), point.connectionType(), start);
        } catch (ResponseStatusException missing) {
            throw new BillingProblem("NO_TARIFF", "No active tariff covers " + month + ".");
        }
        Instant from = start.atStartOfDay(INDIA).toInstant();
        Instant to = end.atStartOfDay(INDIA).toInstant();
        var intervals = usage.intervalReadings(point.meterId(), from, to).stream()
                .filter(reading -> !reading.quality().equals("FLAGGED"))
                .map(reading -> new TariffPricing.Interval(reading.start(), reading.importKwh(), reading.exportKwh()))
                .toList();
        long expected = ChronoUnit.HOURS.between(from, to);
        if (intervals.size() * 10L < expected * 9L) {
            throw new BillingProblem("INSUFFICIENT_READINGS", "Only " + intervals.size() + " of " + expected
                    + " hourly readings passed validation. Estimate or correct the gaps first.");
        }
        YearMonth previous = month.minusMonths(1);
        BigDecimal opening = point.solarNetMetering()
                ? store.closingCredit(point.meterId(), previous.getYear(), previous.getMonthValue()) : ZERO;
        var quote = pricing.price(plan, point.sanctionedLoadKw(), point.solarNetMetering(), opening, intervals);
        return new Draft(point, month, plan, intervals.size(), opening, quote);
    }

    Optional<UUID> issue(Draft draft, LocalDate issueDate, UUID runId, UUID actorId, String actorRole,
            RequestContext request) {
        ServicePoint point = draft.point();
        if (store.exists(point.meterId(), draft.month().getYear(), draft.month().getMonthValue())) {
            return Optional.empty();
        }
        var quote = draft.quote();
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        quote.lineItems().forEach(line -> totals.merge(line.category(), line.amountInr(), BigDecimal::add));
        BigDecimal tax = totals.getOrDefault("DUTY", ZERO).add(totals.getOrDefault("GST", ZERO));
        BigDecimal total = quote.totalInr();
        UUID id = UUID.randomUUID();
        String number = number("INV-" + draft.month().toString().replace("-", "") + "-");
        store.insert(new BillingStore.Bill(id, number, point.account().id(), point.meterId(), draft.plan().id(), runId,
                draft.month().getYear(), draft.month().getMonthValue(), draft.month().atDay(1),
                draft.month().atEndOfMonth(), draft.intervalCount(), quote.importedKwh(), quote.exportedKwh(),
                draft.openingCredit(), quote.carryForwardKwh(), quote.netBillableKwh(), totals.getOrDefault("SLAB", ZERO),
                totals.getOrDefault("TOD", ZERO), totals.getOrDefault("FIXED", ZERO), totals.getOrDefault("FAC", ZERO),
                totals.getOrDefault("SOLAR_CREDIT", ZERO).negate(), tax, total, issueDate,
                issueDate.plusDays(PAYMENT_TERM_DAYS), total.signum() == 0 ? "PAID" : "ISSUED"));
        int order = 1;
        for (var line : quote.lineItems()) {
            if (line.amountInr().signum() != 0 || line.category().equals("SLAB")) {
                store.insertLine(id, order++, line.category(), line.description(), line.units(), line.rate(),
                        line.amountInr());
            }
        }
        if (total.signum() > 0) {
            BigDecimal revenue = total.subtract(tax);
            var entries = new ArrayList<Entry>();
            entries.add(new Entry("RECEIVABLE", "DEBIT", total));
            addSigned(entries, "ENERGY_REVENUE", revenue, "CREDIT");
            addSigned(entries, "TAX_PAYABLE", tax, "CREDIT");
            store.journal(UUID.randomUUID(), point.account().id(), "BILL", id, "Invoice " + number, entries,
                    issueInstant(issueDate));
        }
        billsGenerated.increment();
        events.publishEvent(AuditAction.record("BILL_ISSUED", "BILL", id, actorId, actorRole, request,
                Map.of("billNumber", number, "total", total.toPlainString(), "period", draft.month().toString())));
        events.publishEvent(new BillingEvents.BillIssued(id, number, point.account().id(), point.account().userId(),
                point.account().billingEmail(), draft.month().toString(), total, issueDate.plusDays(PAYMENT_TERM_DAYS)));
        return Optional.of(id);
    }

    @Transactional
    BillRow generateOne(UUID meterId, YearMonth month, UUID actor, String role, RequestContext request) {
        ServicePoint point = accounts.servicePoint(meterId);
        if (!point.meterStatus().equals("ACTIVE")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The meter is not active.");
        }
        Draft draft;
        try {
            draft = draft(point, month);
        } catch (BillingProblem problem) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, problem.getMessage());
        }
        UUID id = issue(draft, LocalDate.now(INDIA), null, actor, role, request).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.CONFLICT, "A bill already exists for this meter and month."));
        return store.bill(id, false).orElseThrow();
    }

    RunResult run(YearMonth month, boolean dryRun, UUID key, UUID actor, String role, RequestContext request) {
        if (!month.isBefore(YearMonth.now(INDIA))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select a completed billing month.");
        }
        List<ServicePoint> points = accounts.billableServicePoints();
        if (dryRun) {
            var previews = new ArrayList<Preview>();
            var errors = new ArrayList<RunError>();
            for (ServicePoint point : points) {
                if (store.exists(point.meterId(), month.getYear(), month.getMonthValue())) {
                    continue;
                }
                try {
                    var draft = draft(point, month);
                    previews.add(new Preview(point.meterSerial(), point.account().consumerNumber(),
                            draft.quote().netBillableKwh(), draft.quote().totalInr()));
                } catch (BillingProblem problem) {
                    errors.add(new RunError(point.meterSerial(), problem.code, problem.getMessage()));
                }
            }
            BigDecimal total = previews.stream().map(Preview::totalInr).reduce(ZERO, BigDecimal::add);
            return new RunResult(null, previews, errors, total);
        }
        if (key == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An Idempotency-Key header is required.");
        }
        var existing = store.runByKey(key);
        if (existing.isPresent()) {
            var run = existing.get();
            if (run.billingYear() != month.getYear() || run.billingMonth() != month.getMonthValue()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "This Idempotency-Key was already used for another billing period.");
            }
            return new RunResult(run, List.of(), List.of(), run.totalBilledInr());
        }
        UUID runId = UUID.randomUUID();
        transactions.executeWithoutResult(status -> store.insertRun(runId, key, month.getYear(), month.getMonthValue(), actor));
        int processed = 0;
        int generated = 0;
        BigDecimal billed = ZERO;
        var errors = new ArrayList<RunError>();
        LocalDate issueDate = LocalDate.now(INDIA);
        for (ServicePoint point : points) {
            processed++;
            try {
                BigDecimal amount = transactions.execute(status -> {
                    if (store.exists(point.meterId(), month.getYear(), month.getMonthValue())) {
                        return null;
                    }
                    var draft = draft(point, month);
                    return issue(draft, issueDate, runId, actor, role, request).map(id -> draft.quote().totalInr())
                            .orElse(null);
                });
                if (amount != null) {
                    generated++;
                    billed = billed.add(amount);
                }
            } catch (BillingProblem problem) {
                errors.add(new RunError(point.meterSerial(), problem.code, problem.getMessage()));
            } catch (RuntimeException failure) {
                LOG.warn("Billing failed for a meter ({})", failure.getClass().getSimpleName());
                errors.add(new RunError(point.meterSerial(), "UNEXPECTED", "The bill could not be generated."));
            }
        }
        String status = errors.isEmpty() ? "COMPLETED" : generated > 0 || errors.size() < processed ? "PARTIAL" : "FAILED";
        int finalProcessed = processed;
        int finalGenerated = generated;
        BigDecimal finalBilled = billed;
        transactions.executeWithoutResult(tx -> {
            store.finishRun(runId, status, finalProcessed, finalGenerated, finalBilled, json.writeValueAsString(errors));
            events.publishEvent(AuditAction.record("BILLING_RUN_COMPLETED", "BILLING_RUN", runId, actor, role, request,
                    Map.of("period", month.toString(), "status", status, "billsGenerated", finalGenerated)));
        });
        return new RunResult(store.run(runId), List.of(), errors, billed);
    }

    @Transactional
    BillRow adjust(UUID billId, String type, BigDecimal amount, String reason, UUID actor, String role,
            RequestContext request) {
        BillRow bill = store.bill(billId, true).orElseThrow(BillingService::missing);
        if (bill.status().equals("VOID")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A void bill cannot be adjusted.");
        }
        if (type.equals("CREDIT") && amount.compareTo(bill.principalOutstanding()) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A credit note cannot exceed the outstanding principal of " + bill.principalOutstanding() + ".");
        }
        UUID noteId = UUID.randomUUID();
        String number = number(type.equals("CREDIT") ? "CN-" : "DN-");
        store.insertNote(noteId, number, billId, type, amount, reason.strip(), actor);
        var entries = type.equals("CREDIT")
                ? List.of(new Entry("REVENUE_ADJUSTMENT", "DEBIT", amount), new Entry("RECEIVABLE", "CREDIT", amount))
                : List.of(new Entry("RECEIVABLE", "DEBIT", amount), new Entry("REVENUE_ADJUSTMENT", "CREDIT", amount));
        store.journal(UUID.randomUUID(), bill.consumerAccountId(), type.equals("CREDIT") ? "CREDIT_NOTE" : "DEBIT_NOTE",
                noteId, (type.equals("CREDIT") ? "Credit note " : "Debit note ") + number + " for " + bill.billNumber(),
                entries, Instant.now());
        if (type.equals("DEBIT") && bill.status().equals("PAID")) {
            // Reopen a settled invoice when a debit note adds a new amount due.
            transitionReopened(billId);
        }
        store.markPaidIfCleared(billId);
        events.publishEvent(AuditAction.record(type + "_NOTE_ISSUED", "BILL", billId, actor, role, request,
                Map.of("noteNumber", number, "amount", amount.toPlainString())));
        events.publishEvent(new BillingEvents.BillAdjusted(billId, bill.billNumber(), bill.consumerAccountId(), number,
                type, amount));
        return store.bill(billId, false).orElseThrow();
    }

    private void transitionReopened(UUID billId) {
        store.markReopened(billId);
    }

    @Transactional
    int markOverdue() {
        var bills = store.markOverdue(LocalDate.now(INDIA));
        for (BillRow bill : bills) {
            accounts.findAccount(bill.consumerAccountId()).ifPresent(account -> events.publishEvent(
                    new BillingEvents.BillOverdue(bill.id(), bill.billNumber(), account.id(), account.userId(),
                            bill.dueDate())));
        }
        return bills.size();
    }

    // ----- Late fees and receivables -----

    static BigDecimal accruedLateFee(BillRow bill, LocalDate today) {
        if (!bill.open() || !today.isAfter(bill.dueDate().plusDays(bill.graceDays()))) {
            return ZERO.setScale(2);
        }
        BigDecimal principal = bill.principalOutstanding();
        long days = ChronoUnit.DAYS.between(bill.dueDate(), today);
        BigDecimal fee = principal.multiply(bill.lateFeeDailyPct()).multiply(BigDecimal.valueOf(days)).movePointLeft(2);
        BigDecimal cap = principal.multiply(bill.lateFeeCapPct()).movePointLeft(2);
        return fee.min(cap).setScale(2, RoundingMode.HALF_UP);
    }

    @Override
    @Transactional
    public List<Payable> lockPayables(UUID accountId, List<UUID> billIds, LocalDate asOf) {
        var unique = new HashSet<>(billIds);
        if (unique.isEmpty() || unique.size() != billIds.size() || unique.size() > 24) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select between 1 and 24 distinct bills.");
        }
        var rows = store.lockBills(accountId, List.copyOf(unique));
        if (rows.size() != unique.size()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "One or more bills were not found.");
        }
        LocalDate today = asOf;
        return rows.stream().map(bill -> {
            if (!bill.open() || bill.principalOutstanding().signum() == 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Bill " + bill.billNumber() + " has nothing due.");
            }
            return new Payable(bill.id(), bill.billNumber(), bill.status(), bill.principalOutstanding(),
                    accruedLateFee(bill, today), bill.dueDate());
        }).sorted(Comparator.comparing(Payable::dueDate)).toList();
    }

    @Override
    @Transactional(noRollbackFor = SettlementRejected.class)
    public void settle(UUID accountId, UUID paymentId, String reference, List<Allocation> allocations,
            Instant settledAt) {
        var rows = store.lockBills(accountId, allocations.stream().map(Allocation::billId).toList());
        if (rows.size() != allocations.size()) {
            throw new SettlementRejected("A bill in this payment no longer belongs to the account.");
        }
        var byId = new LinkedHashMap<UUID, BillRow>();
        rows.forEach(row -> byId.put(row.id(), row));
        for (Allocation allocation : allocations) {
            BillRow bill = byId.get(allocation.billId());
            if (!bill.open() || bill.principalOutstanding().compareTo(allocation.principal()) != 0
                    || allocation.lateFee().signum() < 0
                    || allocation.lateFee().compareTo(bill.principalOutstanding()
                        .multiply(bill.lateFeeCapPct()).movePointLeft(2).setScale(2, RoundingMode.HALF_UP)) > 0) {
                throw new SettlementRejected("Bill " + bill.billNumber() + " changed after checkout.");
            }
        }
        BigDecimal total = ZERO;
        for (Allocation allocation : allocations) {
            BillRow bill = byId.get(allocation.billId());
            if (allocation.lateFee().signum() > 0) {
                store.journal(UUID.randomUUID(), accountId, "LATE_FEE", bill.id(), "Late fee on " + bill.billNumber(),
                        List.of(new Entry("RECEIVABLE", "DEBIT", allocation.lateFee()),
                                new Entry("LATE_FEE_INCOME", "CREDIT", allocation.lateFee())), settledAt);
            }
            store.markSettled(bill.id(), allocation.principal(), allocation.lateFee(), settledAt);
            BigDecimal paid = allocation.principal().add(allocation.lateFee());
            total = total.add(paid);
            accounts.findAccount(accountId).ifPresent(account -> events.publishEvent(new BillingEvents.BillSettled(
                    bill.id(), bill.billNumber(), accountId, account.userId(), paid)));
        }
        store.journal(UUID.randomUUID(), accountId, "PAYMENT", paymentId, "Payment " + reference,
                List.of(new Entry("GATEWAY_CLEARING", "DEBIT", total), new Entry("RECEIVABLE", "CREDIT", total)),
                settledAt);
    }

    @Override
    public Optional<BillHeader> header(UUID billId) {
        return store.bill(billId, false).map(BillingService::header);
    }

    @Override
    public List<BillHeader> recentBills(UUID accountId, int limit) {
        return store.recent(accountId, Math.min(Math.max(limit, 1), 24)).stream().map(BillingService::header).toList();
    }

    @Override
    public Receivables receivables(UUID accountId) {
        LocalDate today = LocalDate.now(INDIA);
        var open = store.openBills(accountId);
        BigDecimal principal = open.stream().map(BillRow::principalOutstanding).reduce(ZERO, BigDecimal::add);
        BigDecimal fees = open.stream().map(bill -> accruedLateFee(bill, today)).reduce(ZERO, BigDecimal::add);
        int overdue = (int) open.stream().filter(bill -> bill.status().equals("OVERDUE")).count();
        LocalDate next = open.stream().map(BillRow::dueDate).min(LocalDate::compareTo).orElse(null);
        return new Receivables(open.size(), overdue, principal.setScale(2), fees.setScale(2), next);
    }

    @Override
    public List<LineItem> lineItems(UUID billId) {
        return store.lines(billId).stream().map(line -> new LineItem(line.category(), line.description(), line.units(),
                line.rate(), line.amount())).toList();
    }

    @Override
    public BigDecimal openingCredit(UUID meterId, YearMonth cycle) {
        YearMonth previous = cycle.minusMonths(1);
        return store.closingCredit(meterId, previous.getYear(), previous.getMonthValue());
    }

    @Override
    public List<MonthlyRevenue> revenueByMonth(int months) {
        return store.revenue(Math.min(Math.max(months, 1), 36));
    }

    @Override
    public List<AgingBucket> aging() {
        return store.aging();
    }

    @Override
    public List<SlabBand> slabDistribution(int months) {
        return store.slabs(Math.min(Math.max(months, 1), 36));
    }

    @Override
    public List<MeterBilling> billingByMeter(YearMonth from, YearMonth to) {
        return store.billingByMeter(from, to);
    }

    static BillHeader header(BillRow bill) {
        return new BillHeader(bill.id(), bill.billNumber(), bill.consumerAccountId(), bill.meterId(),
                YearMonth.of(bill.billingYear(), bill.billingMonth()), bill.importKwh(), bill.exportKwh(),
                bill.totalAmount(), bill.principalOutstanding(), bill.status(), bill.dueDate());
    }

    private static void addSigned(List<Entry> entries, String account, BigDecimal amount, String positiveType) {
        if (amount.signum() == 0) {
            return;
        }
        String negative = positiveType.equals("CREDIT") ? "DEBIT" : "CREDIT";
        entries.add(new Entry(account, amount.signum() > 0 ? positiveType : negative, amount.abs()));
    }

    private static Instant issueInstant(LocalDate issueDate) {
        Instant scheduled = issueDate.atTime(9, 0).atZone(INDIA).toInstant();
        return scheduled.isAfter(Instant.now()) ? Instant.now() : scheduled;
    }

    static String number(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT);
    }

    static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Bill was not found.");
    }

    record Draft(ServicePoint point, YearMonth month, TariffPlan plan, int intervalCount, BigDecimal openingCredit,
            TariffPricing.Quote quote) {}
    record Preview(String meterSerial, String consumerNumber, BigDecimal netBillableKwh, BigDecimal totalInr) {}
    record RunError(String meterSerial, String code, String message) {}
    record RunResult(BillingStore.Run run, List<Preview> preview, List<RunError> errors, BigDecimal totalBilledInr) {}

    static final class BillingProblem extends RuntimeException {
        final String code;

        BillingProblem(String code, String message) {
            super(message);
            this.code = code;
        }
    }
}
