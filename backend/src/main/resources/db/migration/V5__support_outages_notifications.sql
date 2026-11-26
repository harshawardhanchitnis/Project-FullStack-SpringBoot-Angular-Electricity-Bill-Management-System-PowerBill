CREATE TABLE complaints (
    id uuid PRIMARY KEY,
    ticket_number varchar(24) NOT NULL UNIQUE,
    consumer_account_id uuid NOT NULL REFERENCES consumer_accounts(id),
    meter_id uuid REFERENCES smart_meters(id),
    bill_id uuid REFERENCES bills(id),
    complaint_type text NOT NULL CHECK (complaint_type IN ('BILLING','POWER_OUTAGE','METER_FAULT',
        'VOLTAGE_FLUCTUATION','SOLAR_NET_METERING','CONNECTION','TECHNICAL','OTHER')),
    priority text NOT NULL CHECK (priority IN ('CRITICAL','HIGH','MEDIUM','LOW')),
    status text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','ASSIGNED','IN_PROGRESS','RESOLVED','CLOSED')),
    subject varchar(160) NOT NULL,
    description varchar(4000) NOT NULL,
    contact_number varchar(10) NOT NULL CHECK (contact_number ~ '^[6-9][0-9]{9}$'),
    assigned_agent_id uuid REFERENCES users(id),
    sla_due_at timestamptz NOT NULL,
    sla_breached boolean NOT NULL DEFAULT false,
    resolution_summary varchar(2000),
    resolved_at timestamptz,
    csat_rating smallint CHECK (csat_rating BETWEEN 1 AND 5),
    csat_feedback varchar(1000),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0
);
CREATE INDEX complaints_account_idx ON complaints (consumer_account_id, created_at DESC);
CREATE INDEX complaints_queue_idx ON complaints (status, priority, sla_due_at);
CREATE INDEX complaints_agent_idx ON complaints (assigned_agent_id) WHERE assigned_agent_id IS NOT NULL;

CREATE TABLE complaint_comments (
    id uuid PRIMARY KEY,
    complaint_id uuid NOT NULL REFERENCES complaints(id),
    author_user_id uuid NOT NULL REFERENCES users(id),
    author_role text NOT NULL,
    is_internal boolean NOT NULL DEFAULT false,
    body varchar(4000) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX complaint_comments_complaint_idx ON complaint_comments (complaint_id, created_at);
CREATE TRIGGER complaint_comments_append_only BEFORE UPDATE OR DELETE OR TRUNCATE
ON complaint_comments FOR EACH STATEMENT EXECUTE FUNCTION prevent_audit_mutation();

CREATE TABLE complaint_status_history (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    complaint_id uuid NOT NULL REFERENCES complaints(id),
    from_status text,
    to_status text NOT NULL,
    changed_by uuid REFERENCES users(id),
    changed_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX complaint_status_history_idx ON complaint_status_history (complaint_id, changed_at);

CREATE TABLE outage_incidents (
    id uuid PRIMARY KEY,
    incident_code varchar(24) NOT NULL UNIQUE,
    region_zone varchar(100) NOT NULL,
    city varchar(100) NOT NULL,
    affected_pincodes text[] NOT NULL CHECK (cardinality(affected_pincodes) BETWEEN 1 AND 50),
    title varchar(160) NOT NULL,
    description varchar(2000) NOT NULL,
    severity text NOT NULL CHECK (severity IN ('PLANNED','UNPLANNED','EMERGENCY')),
    status text NOT NULL CHECK (status IN ('INVESTIGATING','CREW_DISPATCHED','RESTORING','RESOLVED')),
    starts_at timestamptz NOT NULL DEFAULT now(),
    estimated_restoration_at timestamptz,
    resolved_at timestamptz,
    created_by uuid NOT NULL REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0
);
CREATE INDEX outage_incidents_active_idx ON outage_incidents (status, starts_at DESC);
CREATE INDEX outage_incidents_pincodes_idx ON outage_incidents USING gin (affected_pincodes);

CREATE TABLE notifications (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id),
    channel text NOT NULL CHECK (channel IN ('IN_APP','EMAIL')),
    category text NOT NULL CHECK (category IN ('BILLING','PAYMENT','ANOMALY','BUDGET_ALERT','COMPLAINT','OUTAGE')),
    title varchar(160) NOT NULL,
    message varchar(1000) NOT NULL,
    link varchar(200),
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    dedupe_key varchar(120),
    is_read boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (user_id, dedupe_key)
);
CREATE INDEX notifications_user_idx ON notifications (user_id, created_at DESC);
CREATE INDEX notifications_unread_idx ON notifications (user_id) WHERE NOT is_read;
