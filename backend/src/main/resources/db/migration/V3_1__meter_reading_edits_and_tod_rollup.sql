CREATE TABLE meter_reading_edits (
    id uuid PRIMARY KEY,
    meter_id uuid NOT NULL,
    recorded_at timestamptz NOT NULL,
    edited_by uuid NOT NULL REFERENCES users(id),
    previous_reading jsonb NOT NULL,
    revised_reading jsonb NOT NULL,
    reason varchar(500) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (meter_id, recorded_at) REFERENCES meter_readings(meter_id, recorded_at)
);

CREATE MATERIALIZED VIEW tod_bucket_usage WITH (timescaledb.continuous, timescaledb.materialized_only=false) AS
SELECT time_bucket(INTERVAL '1 day', recorded_at, 'Asia/Kolkata') AS bucket,
    meter_id, extract(hour FROM recorded_at AT TIME ZONE 'Asia/Kolkata')::smallint AS local_hour,
    sum(import_kwh) AS import_kwh, sum(export_kwh) AS export_kwh,
    count(*) AS valid_intervals
FROM meter_readings WHERE vee_status <> 'FLAGGED'
GROUP BY 1, 2, 3 WITH NO DATA;

SELECT remove_continuous_aggregate_policy('daily_meter_usage');
SELECT add_continuous_aggregate_policy('daily_meter_usage', start_offset => NULL,
    end_offset => INTERVAL '1 day', schedule_interval => INTERVAL '1 hour');
SELECT add_continuous_aggregate_policy('tod_bucket_usage', start_offset => NULL,
    end_offset => INTERVAL '1 day', schedule_interval => INTERVAL '1 hour');
