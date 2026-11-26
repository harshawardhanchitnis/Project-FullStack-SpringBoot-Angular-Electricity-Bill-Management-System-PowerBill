package com.powerbill.billing.internal;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class BillingStore {
    static final String BILL = """
            SELECT b.id, b.bill_number, b.consumer_account_id, b.meter_id, b.tariff_plan_id, b.billing_run_id,
                b.billing_year, b.billing_month, b.period_start, b.period_end, b.interval_count, b.import_kwh,
                b.export_kwh, b.opening_credit_kwh, b.carried_forward_credit_kwh, b.net_billable_kwh,
                b.energy_charge, b.tod_adjustment, b.fixed_charge, b.fac_charge, b.solar_export_credit,
                b.tax_amount, b.total_amount, b.late_fee, b.credit_note_total, b.debit_note_total, b.paid_amount,
                b.issue_date, b.due_date, b.status, b.issued_at, b.paid_at, b.version,
                t.code AS tariff_code, t.version AS tariff_version, t.name AS tariff_name,
                t.late_fee_daily_pct, t.late_fee_cap_pct, t.grace_days
            FROM bills b JOIN tariff_plans t ON t.id=b.tariff_plan_id
            """;
    private final JdbcClient db;

    BillingStore(JdbcClient db) {
        this.db = db;
    }

    boolean exists(UUID meterId, int year, int month) {
        return db.sql("SELECT EXISTS(SELECT 1 FROM bills WHERE meter_id=? AND billing_year=? AND billing_month=?)")
                .params(meterId, year, month).query(Boolean.class).single();
    }

    BigDecimal closingCredit(UUID meterId, int year, int month) {
        return db.sql("""
                SELECT carried_forward_credit_kwh FROM bills WHERE meter_id=? AND billing_year=? AND billing_month=?
                """).params(meterId, year, month).query(BigDecimal.class).optional().orElse(BigDecimal.ZERO);
    }

    void insert(Bill bill) {
        db.sql("""
                INSERT INTO bills(id,bill_number,consumer_account_id,meter_id,tariff_plan_id,billing_run_id,billing_year,
                    billing_month,period_start,period_end,interval_count,import_kwh,export_kwh,opening_credit_kwh,
                    carried_forward_credit_kwh,net_billable_kwh,energy_charge,tod_adjustment,fixed_charge,fac_charge,
                    solar_export_credit,tax_amount,total_amount,paid_amount,issue_date,due_date,status,paid_at)
                VALUES (:id,:billNumber,:consumerAccountId,:meterId,:tariffPlanId,:billingRunId,:billingYear,:billingMonth,
                    :periodStart,:periodEnd,:intervalCount,:importKwh,:exportKwh,:openingCreditKwh,:carriedForwardCreditKwh,
                    :netBillableKwh,:energyCharge,:todAdjustment,:fixedCharge,:facCharge,:solarExportCredit,:taxAmount,
                    :totalAmount,0,:issueDate,:dueDate,:status,
                    CASE WHEN :status='PAID' THEN now() ELSE NULL END)
                """).paramSource(bill).update();
    }

    void insertLine(UUID billId, int order, String category, String description, BigDecimal units, BigDecimal rate,
            BigDecimal amount) {
        db.sql("""
                INSERT INTO bill_line_items(bill_id,line_order,category,description,units,rate,amount)
                VALUES (?,?,?,?,?,?,?)
                """).params(billId, order, category, description, units, rate, amount).update();
    }

    Optional<BillRow> bill(UUID id, boolean lock) {
        if (lock) {
            db.sql("SELECT 1 FROM bills WHERE id=? FOR UPDATE").param(id).query(Integer.class).optional();
        }
        return db.sql(BILL + " WHERE b.id=?").param(id).query(BillRow.class).optional();
    }

    List<BillRow> lockBills(UUID accountId, List<UUID> ids) {
        db.sql("SELECT id FROM bills WHERE id IN (:ids) AND consumer_account_id=:account ORDER BY id FOR UPDATE")
                .param("ids", ids).param("account", accountId).query(UUID.class).list();
        return db.sql(BILL + " WHERE b.id IN (:ids) AND b.consumer_account_id=:account ORDER BY b.id")
                .param("ids", ids).param("account", accountId).query(BillRow.class).list();
    }

    List<BillRow> openBills(UUID accountId) {
        return db.sql(BILL + """
                 WHERE b.consumer_account_id=? AND b.status IN ('ISSUED','PARTIALLY_PAID','OVERDUE','DISPUTED')
                 ORDER BY b.due_date,b.id
                """).param(accountId).query(BillRow.class).list();
    }

    List<BillRow> recent(UUID accountId, int limit) {
        return db.sql(BILL + " WHERE b.consumer_account_id=? ORDER BY b.billing_year DESC,b.billing_month DESC,b.id LIMIT ?")
                .params(accountId, limit).query(BillRow.class).list();
    }

    Page search(UUID accountId, UUID meterId, String status, Integer year, Integer month, String query,
            int page, int size) {
        String filter = """
                 WHERE (CAST(:account AS uuid) IS NULL OR b.consumer_account_id=:account)
                   AND (CAST(:meter AS uuid) IS NULL OR b.meter_id=:meter)
                   AND (CAST(:status AS text) IS NULL OR b.status=:status)
                   AND (CAST(:year AS integer) IS NULL OR b.billing_year=:year)
                   AND (CAST(:month AS integer) IS NULL OR b.billing_month=:month)
                   AND (:query='' OR strpos(lower(b.bill_number),:query)>0)
                """;
        var rows = db.sql(BILL + filter + " ORDER BY b.billing_year DESC,b.billing_month DESC,b.issued_at DESC,b.id"
                        + " LIMIT :size OFFSET :offset")
                .param("account", accountId, Types.OTHER).param("meter", meterId, Types.OTHER)
                .param("status", status, Types.VARCHAR).param("year", year, Types.INTEGER)
                .param("month", month, Types.INTEGER).param("query", query).param("size", size)
                .param("offset", (long) page * size).query(BillRow.class).list();
        long total = db.sql("SELECT count(*) FROM bills b " + filter)
                .param("account", accountId, Types.OTHER).param("meter", meterId, Types.OTHER)
                .param("status", status, Types.VARCHAR).param("year", year, Types.INTEGER)
                .param("month", month, Types.INTEGER).param("query", query).query(Long.class).single();
        return new Page(rows, page, size, total);
    }

    List<Line> lines(UUID billId) {
        return db.sql("""
                SELECT line_order,category,description,units,rate,amount FROM bill_line_items
                WHERE bill_id=? ORDER BY line_order
                """).param(billId).query(Line.class).list();
    }

    List<Note> notes(UUID billId) {
        return db.sql("""
                SELECT id,note_number,note_type,amount,reason,created_at FROM credit_debit_notes
                WHERE bill_id=? ORDER BY created_at,id
                """).param(billId).query(Note.class).list();
    }

    void insertNote(UUID id, String number, UUID billId, String type, BigDecimal amount, String reason, UUID actor) {
        db.sql("""
                INSERT INTO credit_debit_notes(id,note_number,bill_id,note_type,amount,reason,created_by_user_id)
                VALUES (?,?,?,?,?,?,?)
                """).params(id, number, billId, type, amount, reason, actor).update();
        String column = type.equals("CREDIT") ? "credit_note_total" : "debit_note_total";
        db.sql("UPDATE bills SET " + column + "=" + column + "+?, version=version+1, updated_at=now() WHERE id=?")
                .params(amount, billId).update();
    }

    void markSettled(UUID billId, BigDecimal principal, BigDecimal lateFee, java.time.Instant at) {
        db.sql("""
                UPDATE bills SET late_fee=late_fee+:fee, paid_amount=paid_amount+:principal+:fee,
                    status=CASE WHEN paid_amount+:principal+:fee >= total_amount+late_fee+:fee+debit_note_total-credit_note_total
                        THEN 'PAID' ELSE 'PARTIALLY_PAID' END,
                    paid_at=CASE WHEN paid_amount+:principal+:fee >= total_amount+late_fee+:fee+debit_note_total-credit_note_total
                        THEN :at ELSE paid_at END,
                    version=version+1, updated_at=now()
                WHERE id=:id
                """).param("fee", lateFee).param("principal", principal).param("id", billId)
                .param("at", java.sql.Timestamp.from(at)).update();
    }

    void markPaidIfCleared(UUID billId) {
        db.sql("""
                UPDATE bills SET status='PAID', paid_at=coalesce(paid_at,now()), version=version+1, updated_at=now()
                WHERE id=? AND status<>'PAID' AND paid_amount >= total_amount+late_fee+debit_note_total-credit_note_total
                """).param(billId).update();
    }

    void markReopened(UUID billId) {
        db.sql("""
                UPDATE bills SET status=CASE WHEN due_date<CAST(now() AT TIME ZONE 'Asia/Kolkata' AS date)
                    THEN 'OVERDUE' ELSE 'ISSUED' END, paid_at=NULL, version=version+1, updated_at=now()
                WHERE id=? AND status='PAID'
                """).param(billId).update();
    }

    List<BillRow> markOverdue(LocalDate today) {
        List<UUID> ids = db.sql("""
                UPDATE bills SET status='OVERDUE', version=version+1, updated_at=now()
                WHERE status IN ('ISSUED','PARTIALLY_PAID') AND due_date<? RETURNING id
                """).param(today).query(UUID.class).list();
        return ids.isEmpty() ? List.of()
                : db.sql(BILL + " WHERE b.id IN (:ids)").param("ids", ids).query(BillRow.class).list();
    }

    void journal(UUID id, UUID accountId, String type, UUID reference, String description, List<Entry> entries,
            java.time.Instant at) {
        var timestamp = java.sql.Timestamp.from(at);
        db.sql("""
                INSERT INTO ledger_journals(id,consumer_account_id,reference_type,reference_id,description,created_at)
                VALUES (?,?,?,?,?,?)
                """).params(id, accountId, type, reference, description, timestamp).update();
        for (Entry entry : entries) {
            db.sql("""
                    INSERT INTO ledger_entries(journal_id,consumer_account_id,account_code,entry_type,amount,created_at)
                    VALUES (?,?,?,?,?,?)
                    """).params(id, accountId, entry.account(), entry.type(), entry.amount(), timestamp).update();
        }
    }

    List<LedgerLine> ledger(UUID accountId, int limit) {
        return db.sql("""
                SELECT * FROM (
                    SELECT e.id, j.created_at, j.reference_type, j.reference_id, j.description, e.entry_type, e.amount,
                        sum(CASE WHEN e.entry_type='DEBIT' THEN e.amount ELSE -e.amount END)
                            OVER (ORDER BY e.created_at, e.id) AS running_balance
                    FROM ledger_entries e JOIN ledger_journals j ON j.id=e.journal_id
                    WHERE e.consumer_account_id=? AND e.account_code='RECEIVABLE'
                ) ledger ORDER BY created_at DESC, id DESC LIMIT ?
                """).params(accountId, limit).query(LedgerLine.class).list();
    }

    void insertRun(UUID id, UUID key, int year, int month, UUID actor) {
        db.sql("""
                INSERT INTO billing_runs(id,idempotency_key,billing_year,billing_month,triggered_by,status)
                VALUES (?,?,?,?,?,'RUNNING')
                """).params(id, key, year, month, actor).update();
    }

    Optional<Run> runByKey(UUID key) {
        return db.sql("SELECT * FROM billing_runs WHERE idempotency_key=?").param(key).query(Run.class).optional();
    }

    Run run(UUID id) {
        return db.sql("SELECT * FROM billing_runs WHERE id=?").param(id).query(Run.class).single();
    }

    void finishRun(UUID id, String status, int processed, int generated, BigDecimal billed, String errors) {
        db.sql("""
                UPDATE billing_runs SET status=?, meters_processed=?, bills_generated=?, total_billed_inr=?,
                    errors=CAST(? AS jsonb), completed_at=now() WHERE id=?
                """).params(status, processed, generated, billed, errors, id).update();
    }

    List<Run> runs(int limit) {
        return db.sql("SELECT * FROM billing_runs ORDER BY started_at DESC LIMIT ?").param(limit).query(Run.class).list();
    }

    List<com.powerbill.billing.BillingStatistics.MonthlyRevenue> revenue(int months) {
        return db.sql("""
                WITH periods AS (
                    SELECT to_char(m, 'YYYY-MM') AS month FROM generate_series(
                        date_trunc('month', now() AT TIME ZONE 'Asia/Kolkata') - make_interval(months => :months),
                        date_trunc('month', now() AT TIME ZONE 'Asia/Kolkata') - interval '1 month', interval '1 month') m
                ), billed AS (
                    SELECT to_char(make_date(billing_year, billing_month, 1), 'YYYY-MM') AS month, count(*) AS bills,
                        sum(total_amount + debit_note_total - credit_note_total) AS billed, sum(net_billable_kwh) AS kwh
                    FROM bills WHERE status <> 'VOID' GROUP BY 1
                ), collected AS (
                    SELECT to_char(paid_at AT TIME ZONE 'Asia/Kolkata', 'YYYY-MM') AS month, sum(paid_amount) AS collected
                    FROM bills WHERE paid_at IS NOT NULL GROUP BY 1
                )
                SELECT p.month, coalesce(b.bills, 0) AS bills, coalesce(b.billed, 0) AS billed_inr,
                    coalesce(c.collected, 0) AS collected_inr, coalesce(b.kwh, 0) AS billed_kwh
                FROM periods p LEFT JOIN billed b ON b.month = p.month LEFT JOIN collected c ON c.month = p.month
                ORDER BY p.month
                """).param("months", months).query(com.powerbill.billing.BillingStatistics.MonthlyRevenue.class).list();
    }

    List<com.powerbill.billing.BillingStatistics.AgingBucket> aging() {
        return db.sql("""
                WITH open AS (
                    SELECT greatest(total_amount + debit_note_total - credit_note_total - (paid_amount - late_fee), 0) AS due,
                        CAST(now() AT TIME ZONE 'Asia/Kolkata' AS date) - due_date AS days
                    FROM bills WHERE status IN ('ISSUED','PARTIALLY_PAID','OVERDUE','DISPUTED')
                ), buckets(bucket, ord, low, high) AS (
                    VALUES ('Not yet due', 0, -100000, 0), ('1-30 days', 1, 1, 30), ('31-60 days', 2, 31, 60),
                        ('61-90 days', 3, 61, 90), ('90+ days', 4, 91, 100000)
                )
                SELECT b.bucket, count(o.due) AS bills, coalesce(sum(o.due), 0) AS outstanding_inr
                FROM buckets b LEFT JOIN open o ON o.days BETWEEN b.low AND b.high
                GROUP BY b.bucket, b.ord ORDER BY b.ord
                """).query(com.powerbill.billing.BillingStatistics.AgingBucket.class).list();
    }

    List<com.powerbill.billing.BillingStatistics.SlabBand> slabs(int months) {
        return db.sql("""
                WITH bands(band, ord, low, high) AS (
                    VALUES ('0-100 kWh', 0, 0, 100), ('101-300 kWh', 1, 100.0001, 300), ('301-500 kWh', 2, 300.0001, 500),
                        ('500+ kWh', 3, 500.0001, 100000000)
                ), recent AS (
                    SELECT net_billable_kwh FROM bills
                    WHERE make_date(billing_year, billing_month, 1) >=
                        CAST(date_trunc('month', now() AT TIME ZONE 'Asia/Kolkata') - make_interval(months => :months) AS date)
                )
                SELECT b.band, count(r.net_billable_kwh) AS bills FROM bands b
                LEFT JOIN recent r ON r.net_billable_kwh BETWEEN b.low AND b.high
                GROUP BY b.band, b.ord ORDER BY b.ord
                """).param("months", months).query(com.powerbill.billing.BillingStatistics.SlabBand.class).list();
    }

    record Bill(UUID id, String billNumber, UUID consumerAccountId, UUID meterId, UUID tariffPlanId, UUID billingRunId,
            int billingYear, int billingMonth, LocalDate periodStart, LocalDate periodEnd, int intervalCount,
            BigDecimal importKwh, BigDecimal exportKwh, BigDecimal openingCreditKwh, BigDecimal carriedForwardCreditKwh,
            BigDecimal netBillableKwh, BigDecimal energyCharge, BigDecimal todAdjustment, BigDecimal fixedCharge,
            BigDecimal facCharge, BigDecimal solarExportCredit, BigDecimal taxAmount, BigDecimal totalAmount,
            LocalDate issueDate, LocalDate dueDate, String status) {}

    record BillRow(UUID id, String billNumber, UUID consumerAccountId, UUID meterId, UUID tariffPlanId,
            UUID billingRunId, int billingYear, int billingMonth, LocalDate periodStart, LocalDate periodEnd,
            int intervalCount, BigDecimal importKwh, BigDecimal exportKwh, BigDecimal openingCreditKwh,
            BigDecimal carriedForwardCreditKwh, BigDecimal netBillableKwh, BigDecimal energyCharge,
            BigDecimal todAdjustment, BigDecimal fixedCharge, BigDecimal facCharge, BigDecimal solarExportCredit,
            BigDecimal taxAmount, BigDecimal totalAmount, BigDecimal lateFee, BigDecimal creditNoteTotal,
            BigDecimal debitNoteTotal, BigDecimal paidAmount, LocalDate issueDate, LocalDate dueDate, String status,
            OffsetDateTime issuedAt, OffsetDateTime paidAt, long version, String tariffCode, int tariffVersion,
            String tariffName, BigDecimal lateFeeDailyPct, BigDecimal lateFeeCapPct, int graceDays) {

        /** Principal still owed: the invoice plus debit notes, less credit notes and principal already paid. */
        BigDecimal principalOutstanding() {
            return totalAmount.add(debitNoteTotal).subtract(creditNoteTotal).subtract(paidAmount.subtract(lateFee))
                    .max(BigDecimal.ZERO);
        }

        boolean open() {
            return status.equals("ISSUED") || status.equals("PARTIALLY_PAID") || status.equals("OVERDUE")
                    || status.equals("DISPUTED");
        }
    }

    record Line(int lineOrder, String category, String description, BigDecimal units, BigDecimal rate,
            BigDecimal amount) {}
    record Note(UUID id, String noteNumber, String noteType, BigDecimal amount, String reason,
            OffsetDateTime createdAt) {}
    record Entry(String account, String type, BigDecimal amount) {}
    record LedgerLine(long id, OffsetDateTime createdAt, String referenceType, UUID referenceId, String description,
            String entryType, BigDecimal amount, BigDecimal runningBalance) {}
    record Run(UUID id, UUID idempotencyKey, int billingYear, int billingMonth, UUID triggeredBy, String status,
            int metersProcessed, int billsGenerated, BigDecimal totalBilledInr, String errors,
            OffsetDateTime startedAt, OffsetDateTime completedAt) {}
    record Page(List<BillRow> content, int page, int size, long totalElements) {}
}
