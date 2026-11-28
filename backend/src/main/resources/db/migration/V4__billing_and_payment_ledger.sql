CREATE TABLE billing_runs (
    id uuid PRIMARY KEY,
    idempotency_key uuid NOT NULL UNIQUE,
    billing_year smallint NOT NULL CHECK (billing_year BETWEEN 2000 AND 2100),
    billing_month smallint NOT NULL CHECK (billing_month BETWEEN 1 AND 12),
    triggered_by uuid REFERENCES users(id),
    status text NOT NULL CHECK (status IN ('RUNNING','COMPLETED','PARTIAL','FAILED')),
    meters_processed integer NOT NULL DEFAULT 0,
    bills_generated integer NOT NULL DEFAULT 0,
    total_billed_inr numeric(14,2) NOT NULL DEFAULT 0,
    errors jsonb NOT NULL DEFAULT '[]'::jsonb,
    started_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz
);
CREATE INDEX billing_runs_period_idx ON billing_runs (billing_year DESC, billing_month DESC, started_at DESC);

CREATE TABLE bills (
    id uuid PRIMARY KEY,
    bill_number varchar(40) NOT NULL UNIQUE,
    consumer_account_id uuid NOT NULL REFERENCES consumer_accounts(id),
    meter_id uuid NOT NULL REFERENCES smart_meters(id),
    tariff_plan_id uuid NOT NULL REFERENCES tariff_plans(id),
    billing_run_id uuid REFERENCES billing_runs(id),
    billing_year smallint NOT NULL CHECK (billing_year BETWEEN 2000 AND 2100),
    billing_month smallint NOT NULL CHECK (billing_month BETWEEN 1 AND 12),
    period_start date NOT NULL,
    period_end date NOT NULL CHECK (period_end > period_start),
    interval_count integer NOT NULL CHECK (interval_count >= 0),
    import_kwh numeric(12,4) NOT NULL CHECK (import_kwh >= 0),
    export_kwh numeric(12,4) NOT NULL CHECK (export_kwh >= 0),
    opening_credit_kwh numeric(12,4) NOT NULL DEFAULT 0 CHECK (opening_credit_kwh >= 0),
    carried_forward_credit_kwh numeric(12,4) NOT NULL DEFAULT 0 CHECK (carried_forward_credit_kwh >= 0),
    net_billable_kwh numeric(12,4) NOT NULL CHECK (net_billable_kwh >= 0),
    energy_charge numeric(12,2) NOT NULL,
    tod_adjustment numeric(12,2) NOT NULL,
    fixed_charge numeric(12,2) NOT NULL,
    fac_charge numeric(12,2) NOT NULL,
    solar_export_credit numeric(12,2) NOT NULL DEFAULT 0,
    tax_amount numeric(12,2) NOT NULL,
    total_amount numeric(12,2) NOT NULL CHECK (total_amount >= 0),
    late_fee numeric(12,2) NOT NULL DEFAULT 0 CHECK (late_fee >= 0),
    credit_note_total numeric(12,2) NOT NULL DEFAULT 0 CHECK (credit_note_total >= 0),
    debit_note_total numeric(12,2) NOT NULL DEFAULT 0 CHECK (debit_note_total >= 0),
    paid_amount numeric(12,2) NOT NULL DEFAULT 0 CHECK (paid_amount >= 0),
    issue_date date NOT NULL,
    due_date date NOT NULL CHECK (due_date >= issue_date),
    status text NOT NULL CHECK (status IN ('ISSUED','PARTIALLY_PAID','PAID','OVERDUE','DISPUTED','VOID')),
    issued_at timestamptz NOT NULL DEFAULT now(),
    paid_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (meter_id, billing_year, billing_month),
    CHECK (paid_amount <= total_amount + late_fee + debit_note_total - credit_note_total)
);
CREATE INDEX bills_account_idx ON bills (consumer_account_id, billing_year DESC, billing_month DESC);
CREATE INDEX bills_status_due_idx ON bills (status, due_date);

-- Issued invoices are immutable. Corrections are recorded as credit or debit notes.
CREATE FUNCTION protect_issued_bill() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Bills cannot be deleted';
    END IF;
    IF (NEW.bill_number, NEW.consumer_account_id, NEW.meter_id, NEW.tariff_plan_id, NEW.billing_year,
        NEW.billing_month, NEW.period_start, NEW.period_end, NEW.import_kwh, NEW.export_kwh,
        NEW.opening_credit_kwh, NEW.carried_forward_credit_kwh, NEW.net_billable_kwh, NEW.energy_charge,
        NEW.tod_adjustment, NEW.fixed_charge, NEW.fac_charge, NEW.solar_export_credit, NEW.tax_amount,
        NEW.total_amount, NEW.issue_date, NEW.due_date, NEW.issued_at)
       IS DISTINCT FROM
       (OLD.bill_number, OLD.consumer_account_id, OLD.meter_id, OLD.tariff_plan_id, OLD.billing_year,
        OLD.billing_month, OLD.period_start, OLD.period_end, OLD.import_kwh, OLD.export_kwh,
        OLD.opening_credit_kwh, OLD.carried_forward_credit_kwh, OLD.net_billable_kwh, OLD.energy_charge,
        OLD.tod_adjustment, OLD.fixed_charge, OLD.fac_charge, OLD.solar_export_credit, OLD.tax_amount,
        OLD.total_amount, OLD.issue_date, OLD.due_date, OLD.issued_at) THEN
        RAISE EXCEPTION 'Issued bill amounts are immutable';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER bills_immutable BEFORE UPDATE OR DELETE ON bills FOR EACH ROW EXECUTE FUNCTION protect_issued_bill();

CREATE TABLE bill_line_items (
    bill_id uuid NOT NULL REFERENCES bills(id),
    line_order smallint NOT NULL CHECK (line_order > 0),
    category text NOT NULL CHECK (category IN ('SLAB','TOD','FIXED','FAC','DUTY','GST','SOLAR_CREDIT')),
    description varchar(120) NOT NULL,
    units numeric(14,4),
    rate numeric(12,4),
    amount numeric(12,2) NOT NULL,
    PRIMARY KEY (bill_id, line_order)
);
CREATE TRIGGER bill_line_items_append_only BEFORE UPDATE OR DELETE OR TRUNCATE
ON bill_line_items FOR EACH STATEMENT EXECUTE FUNCTION prevent_audit_mutation();

CREATE TABLE credit_debit_notes (
    id uuid PRIMARY KEY,
    note_number varchar(40) NOT NULL UNIQUE,
    bill_id uuid NOT NULL REFERENCES bills(id),
    note_type text NOT NULL CHECK (note_type IN ('CREDIT','DEBIT')),
    amount numeric(12,2) NOT NULL CHECK (amount > 0),
    reason varchar(500) NOT NULL,
    created_by_user_id uuid NOT NULL REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX credit_debit_notes_bill_idx ON credit_debit_notes (bill_id);
CREATE TRIGGER credit_debit_notes_append_only BEFORE UPDATE OR DELETE OR TRUNCATE
ON credit_debit_notes FOR EACH STATEMENT EXECUTE FUNCTION prevent_audit_mutation();

CREATE TABLE ledger_journals (
    id uuid PRIMARY KEY,
    consumer_account_id uuid NOT NULL REFERENCES consumer_accounts(id),
    reference_type text NOT NULL CHECK (reference_type IN ('BILL','PAYMENT','CREDIT_NOTE','DEBIT_NOTE','LATE_FEE')),
    reference_id uuid NOT NULL,
    description varchar(200) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ledger_journals_account_idx ON ledger_journals (consumer_account_id, created_at);

CREATE TABLE ledger_entries (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    journal_id uuid NOT NULL REFERENCES ledger_journals(id),
    consumer_account_id uuid NOT NULL REFERENCES consumer_accounts(id),
    account_code text NOT NULL CHECK (account_code IN
        ('RECEIVABLE','ENERGY_REVENUE','TAX_PAYABLE','LATE_FEE_INCOME','REVENUE_ADJUSTMENT','GATEWAY_CLEARING')),
    entry_type text NOT NULL CHECK (entry_type IN ('DEBIT','CREDIT')),
    amount numeric(12,2) NOT NULL CHECK (amount > 0),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ledger_entries_receivable_idx ON ledger_entries (consumer_account_id, account_code, id);
CREATE INDEX ledger_entries_journal_idx ON ledger_entries (journal_id);
CREATE TRIGGER ledger_entries_append_only BEFORE UPDATE OR DELETE OR TRUNCATE
ON ledger_entries FOR EACH STATEMENT EXECUTE FUNCTION prevent_audit_mutation();
CREATE TRIGGER ledger_journals_append_only BEFORE UPDATE OR DELETE OR TRUNCATE
ON ledger_journals FOR EACH STATEMENT EXECUTE FUNCTION prevent_audit_mutation();

-- Every journal must balance when the transaction commits.
CREATE FUNCTION check_journal_balance() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    difference numeric;
BEGIN
    SELECT coalesce(sum(CASE WHEN entry_type = 'DEBIT' THEN amount ELSE -amount END), 0)
    INTO difference FROM ledger_entries WHERE journal_id = NEW.journal_id;
    IF difference <> 0 THEN
        RAISE EXCEPTION 'Ledger journal % is unbalanced by %', NEW.journal_id, difference;
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER ledger_entries_balanced AFTER INSERT ON ledger_entries
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_journal_balance();

CREATE TABLE payment_transactions (
    id uuid PRIMARY KEY,
    payment_reference varchar(40) NOT NULL UNIQUE,
    idempotency_key uuid NOT NULL,
    request_hash char(64) NOT NULL,
    consumer_account_id uuid NOT NULL REFERENCES consumer_accounts(id),
    initiated_by uuid NOT NULL REFERENCES users(id),
    gateway_provider text NOT NULL CHECK (gateway_provider IN ('SANDBOX','STRIPE','RAZORPAY')),
    gateway_order_id varchar(80) NOT NULL,
    gateway_payment_id varchar(80),
    payment_method text NOT NULL CHECK (payment_method IN ('UPI','CREDIT_CARD','DEBIT_CARD','NET_BANKING')),
    amount numeric(12,2) NOT NULL CHECK (amount > 0),
    currency char(3) NOT NULL DEFAULT 'INR' CHECK (currency = 'INR'),
    status text NOT NULL CHECK (status IN ('INITIATED','PENDING','SUCCEEDED','FAILED','EXPIRED')),
    failure_reason varchar(200),
    webhook_verified boolean NOT NULL DEFAULT false,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (consumer_account_id, idempotency_key)
);
CREATE INDEX payment_transactions_account_idx ON payment_transactions (consumer_account_id, created_at DESC);
CREATE INDEX payment_transactions_open_idx ON payment_transactions (status, expires_at)
    WHERE status IN ('INITIATED','PENDING');

CREATE TABLE payment_allocations (
    payment_transaction_id uuid NOT NULL REFERENCES payment_transactions(id),
    bill_id uuid NOT NULL REFERENCES bills(id),
    principal_amount numeric(12,2) NOT NULL CHECK (principal_amount >= 0),
    late_fee_amount numeric(12,2) NOT NULL CHECK (late_fee_amount >= 0),
    PRIMARY KEY (payment_transaction_id, bill_id)
);
CREATE INDEX payment_allocations_bill_idx ON payment_allocations (bill_id);

CREATE TABLE payment_webhook_events (
    event_id varchar(80) PRIMARY KEY,
    provider text NOT NULL,
    payment_transaction_id uuid REFERENCES payment_transactions(id),
    payload_sha256 char(64) NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now()
);
