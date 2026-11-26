package com.powerbill.metering.internal;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.powerbill.metering.MeterUsage.Reading;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class ReadingStore {
    private static final String INSERT = """
            INSERT INTO meter_readings(meter_id, recorded_at, import_kwh, export_kwh, voltage,
                power_factor, reading_source, vee_status) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (meter_id, recorded_at) DO NOTHING
            """;
    private final JdbcClient db;

    ReadingStore(JdbcClient db) {
        this.db = db;
    }

    int write(UUID meter, Reading reading) {
        return db.sql(INSERT).params(meter, Timestamp.from(reading.start()), reading.importKwh(),
                reading.exportKwh(), reading.voltage(), reading.powerFactor(), reading.source(),
                reading.quality()).update();
    }

    void exception(UUID meterId, Instant start, String reason) {
        db.sql("INSERT INTO vee_exceptions(meter_id,recorded_at,reason_code) VALUES (?,?,?)")
                .params(meterId, Timestamp.from(start), reason).update();
    }

    void correct(UUID meterId, Reading old, Reading replacement, UUID user, String note, String previous, String revised) {
        int changed = db.sql("""
                UPDATE meter_readings SET import_kwh=?,export_kwh=?,voltage=?,power_factor=?,reading_source='MANUAL',
                    vee_status='VALID' WHERE meter_id=? AND recorded_at=? AND vee_status='FLAGGED'
                """).params(replacement.importKwh(), replacement.exportKwh(), replacement.voltage(),
                replacement.powerFactor(), meterId, Timestamp.from(old.start())).update();
        if (changed != 1) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "Only flagged readings may be corrected.");
        }
        db.sql("""
                INSERT INTO meter_reading_edits(id,meter_id,recorded_at,edited_by,previous_reading,revised_reading,reason)
                VALUES (?,?,?,?,CAST(? AS jsonb),CAST(? AS jsonb),?)
                """).params(UUID.randomUUID(), meterId, Timestamp.from(old.start()), user, previous, revised, note).update();
    }

    BigDecimal historicalMean(UUID meterId, Instant start, boolean exported) {
        String unit = exported ? "export_kwh" : "import_kwh";
        return db.sql("""
                SELECT CASE WHEN count(*) >= 3 THEN round(avg(%s),4) ELSE NULL END AS average
                FROM meter_readings
                WHERE meter_id=? AND recorded_at>=? AND recorded_at<?
                    AND extract(hour FROM recorded_at AT TIME ZONE 'Asia/Kolkata')=?
                    AND vee_status='VALID'
                """.formatted(unit)).params(meterId, Timestamp.from(start.minusSeconds(7 * 86400L)),
                Timestamp.from(start), start.atZone(com.powerbill.metering.MeterUsage.BILLING_ZONE).getHour())
                .query((row, ignored) -> java.util.Optional.ofNullable(row.getBigDecimal("average")))
                .single().orElse(null);
    }

    void anomaly(UUID meterId, Instant start, String type, String severity, int score, String reasons) {
        db.sql("""
                INSERT INTO meter_anomalies(
                    id,meter_id,window_start,window_end,anomaly_type,severity,score,reason_codes
                ) VALUES (?,?,?,?,?,?,?,CAST(? AS jsonb))
                ON CONFLICT (meter_id,window_start,anomaly_type) DO NOTHING
                """).params(UUID.randomUUID(), meterId, Timestamp.from(start), Timestamp.from(start.plusSeconds(3600)),
                type, severity, score, reasons).update();
    }

    List<Reading> readings(UUID meter, Instant from, Instant to) {
        return db.sql("""
                SELECT recorded_at,import_kwh,export_kwh,voltage,power_factor,reading_source,vee_status
                FROM meter_readings WHERE meter_id=? AND recorded_at>=? AND recorded_at<?
                ORDER BY recorded_at
                """).params(meter, Timestamp.from(from), Timestamp.from(to)).query((row, ignored) -> new Reading(
                row.getTimestamp("recorded_at").toInstant(), row.getBigDecimal("import_kwh"),
                row.getBigDecimal("export_kwh"), row.getBigDecimal("voltage"), row.getBigDecimal("power_factor"),
                row.getString("reading_source"), row.getString("vee_status"))).list();
    }

    List<Aggregate> totals(UUID meter, Instant from, Instant to, String granularity) {
        String rows = """
                SELECT bucket, import_kwh, export_kwh, valid_intervals, flagged_intervals
                FROM daily_meter_usage WHERE meter_id=? AND bucket>=? AND bucket<?
                """;
        if (granularity.equals("monthly")) {
            rows = """
                    SELECT time_bucket(INTERVAL '1 month', bucket, 'Asia/Kolkata') AS bucket,
                        sum(import_kwh) AS import_kwh, sum(export_kwh) AS export_kwh,
                        sum(valid_intervals) AS valid_intervals, sum(flagged_intervals) AS flagged_intervals
                    FROM (
                    """ + rows + ") daily GROUP BY 1";
        }
        return db.sql(rows + " ORDER BY bucket").params(meter, Timestamp.from(from), Timestamp.from(to))
                .query(Aggregate.class).list();
    }

    List<Anomaly> anomalies(String status, int limit, int offset) {
        return db.sql("""
                SELECT id,meter_id,window_start,window_end,anomaly_type,severity,score,status,resolution_notes
                FROM meter_anomalies WHERE (CAST(:status AS text) IS NULL OR status=:status)
                ORDER BY detected_at DESC,id DESC LIMIT :limit OFFSET :offset
                """).param("status", status, java.sql.Types.VARCHAR).param("limit", limit).param("offset", offset)
                .query(Anomaly.class).list();
    }

    Anomaly resolve(UUID id, UUID staff, String status, String notes) {
        return db.sql("""
                UPDATE meter_anomalies SET status=?, assigned_to_user_id=?, resolution_notes=?,updated_at=now()
                WHERE id=? RETURNING id,meter_id,window_start,window_end,anomaly_type,severity,score,status,resolution_notes
                """).params(status, staff, notes, id).query(Anomaly.class).optional()
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Meter anomaly was not found."));
    }

    record Aggregate(OffsetDateTime bucket, BigDecimal importKwh, BigDecimal exportKwh,
            long validIntervals, long flaggedIntervals) {}
    record Anomaly(UUID id, UUID meterId, OffsetDateTime windowStart, OffsetDateTime windowEnd,
            String anomalyType, String severity, BigDecimal score, String status, String resolutionNotes) {}
}
