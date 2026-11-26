CREATE EXTENSION IF NOT EXISTS timescaledb WITH SCHEMA public;

CREATE TABLE tariff_plans (
    id uuid PRIMARY KEY,
    code varchar(80) NOT NULL,
    version integer NOT NULL CHECK (version > 0),
    name varchar(120) NOT NULL,
    connection_type text NOT NULL CHECK (connection_type IN ('DOMESTIC','COMMERCIAL','INDUSTRIAL')),
    effective_from date NOT NULL,
    effective_to date CHECK (effective_to > effective_from),
    fixed_charge_per_kw numeric(12,4) NOT NULL CHECK (fixed_charge_per_kw >= 0),
    fac_per_kwh numeric(12,4) NOT NULL,
    electricity_duty_pct numeric(7,4) NOT NULL CHECK (electricity_duty_pct BETWEEN 0 AND 100),
    gst_pct numeric(7,4) NOT NULL CHECK (gst_pct BETWEEN 0 AND 100),
    solar_export_rate_per_kwh numeric(12,4) NOT NULL CHECK (solar_export_rate_per_kwh >= 0),
    solar_policy text NOT NULL CHECK (solar_policy IN ('CARRY_FORWARD','CASH_CREDIT')),
    late_fee_daily_pct numeric(7,4) NOT NULL CHECK (late_fee_daily_pct BETWEEN 0 AND 100),
    late_fee_cap_pct numeric(7,4) NOT NULL CHECK (late_fee_cap_pct BETWEEN 0 AND 100),
    grace_days integer NOT NULL CHECK (grace_days BETWEEN 0 AND 365),
    is_active boolean NOT NULL DEFAULT true,
    created_by uuid REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (code, version)
);
CREATE INDEX tariff_plans_category_dates_idx ON tariff_plans(connection_type,effective_from,effective_to)
    WHERE is_active;

CREATE TABLE tariff_slabs (
    tariff_plan_id uuid NOT NULL REFERENCES tariff_plans(id),
    slab_order integer NOT NULL CHECK (slab_order > 0),
    min_units numeric(12,4) NOT NULL CHECK (min_units >= 0),
    max_units numeric(12,4) CHECK (max_units > min_units),
    rate_per_kwh numeric(12,4) NOT NULL CHECK (rate_per_kwh >= 0),
    PRIMARY KEY (tariff_plan_id, slab_order)
);

CREATE TABLE tod_windows (
    tariff_plan_id uuid NOT NULL REFERENCES tariff_plans(id),
    start_hour smallint NOT NULL CHECK (start_hour BETWEEN 0 AND 23),
    end_hour smallint NOT NULL CHECK (end_hour BETWEEN 1 AND 24 AND end_hour > start_hour),
    name varchar(20) NOT NULL CHECK (name IN ('SOLAR_OFF_PEAK','NORMAL','EVENING_PEAK')),
    rate_multiplier numeric(6,4) NOT NULL CHECK (rate_multiplier BETWEEN 0 AND 9.9999),
    PRIMARY KEY (tariff_plan_id, start_hour)
);
ALTER TABLE smart_meters ADD CONSTRAINT smart_meter_tariff_fk FOREIGN KEY (tariff_plan_id) REFERENCES tariff_plans(id);

CREATE TABLE meter_readings (
    meter_id uuid NOT NULL REFERENCES smart_meters(id),
    recorded_at timestamptz NOT NULL,
    import_kwh numeric(12,4) NOT NULL CHECK (import_kwh >= 0),
    export_kwh numeric(12,4) NOT NULL CHECK (export_kwh >= 0),
    voltage numeric(6,2),
    power_factor numeric(4,3),
    reading_source text NOT NULL CHECK (reading_source IN ('AMI_STREAM','SIMULATOR','MANUAL','CSV','GREEN_BUTTON')),
    vee_status text NOT NULL CHECK (vee_status IN ('VALID','ESTIMATED','FLAGGED')),
    PRIMARY KEY (meter_id, recorded_at),
    CHECK (date_trunc('hour', recorded_at AT TIME ZONE 'Asia/Kolkata') = recorded_at AT TIME ZONE 'Asia/Kolkata'),
    CHECK (voltage IS NULL OR voltage BETWEEN 0 AND 9999.99),
    CHECK (power_factor IS NULL OR power_factor BETWEEN 0 AND 1)
);
SELECT create_hypertable('meter_readings', by_range('recorded_at', INTERVAL '14 days'), if_not_exists => true);

CREATE TABLE vee_exceptions (
    meter_id uuid NOT NULL,
    recorded_at timestamptz NOT NULL,
    reason_code text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(meter_id, recorded_at, reason_code),
    FOREIGN KEY (meter_id, recorded_at) REFERENCES meter_readings(meter_id, recorded_at)
);

CREATE TABLE meter_anomalies (
    id uuid PRIMARY KEY,
    meter_id uuid NOT NULL REFERENCES smart_meters(id),
    detected_at timestamptz NOT NULL DEFAULT now(),
    window_start timestamptz NOT NULL,
    window_end timestamptz NOT NULL CHECK (window_end > window_start),
    anomaly_type text NOT NULL CHECK (anomaly_type IN ('SPIKE','ZERO_READ','TAMPER_SUSPECTED','VOLTAGE_FAULT','POWER_FACTOR_DROP')),
    severity text NOT NULL CHECK (severity IN ('LOW','MEDIUM','HIGH','CRITICAL')),
    score numeric(5,2) NOT NULL CHECK (score BETWEEN 0 AND 100),
    reason_codes jsonb NOT NULL,
    status text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','INVESTIGATING','CONFIRMED_FAULT','CONFIRMED_TAMPER','FALSE_POSITIVE')),
    assigned_to_user_id uuid REFERENCES users(id),
    resolution_notes varchar(1000),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (meter_id, window_start, anomaly_type)
);
CREATE INDEX meter_anomalies_status_idx ON meter_anomalies(status,detected_at DESC);

CREATE MATERIALIZED VIEW daily_meter_usage WITH (timescaledb.continuous, timescaledb.materialized_only=false) AS
SELECT time_bucket(INTERVAL '1 day', recorded_at, 'Asia/Kolkata') AS bucket, meter_id,
    coalesce(sum(import_kwh) FILTER (WHERE vee_status <> 'FLAGGED'),0) AS import_kwh,
    coalesce(sum(export_kwh) FILTER (WHERE vee_status <> 'FLAGGED'),0) AS export_kwh,
    avg(voltage) FILTER (WHERE vee_status <> 'FLAGGED') AS avg_voltage,
    avg(power_factor) FILTER (WHERE vee_status <> 'FLAGGED') AS avg_power_factor,
    count(*) FILTER (WHERE vee_status <> 'FLAGGED') AS valid_intervals,
    count(*) FILTER (WHERE vee_status = 'FLAGGED') AS flagged_intervals
FROM meter_readings GROUP BY 1, 2 WITH NO DATA;
SELECT add_continuous_aggregate_policy('daily_meter_usage', start_offset => INTERVAL '400 days',
    end_offset => INTERVAL '1 day', schedule_interval => INTERVAL '1 hour');
