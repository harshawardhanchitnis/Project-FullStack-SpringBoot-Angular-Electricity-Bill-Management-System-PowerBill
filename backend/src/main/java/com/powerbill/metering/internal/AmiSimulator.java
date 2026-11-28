package com.powerbill.metering.internal;

import static com.powerbill.metering.MeterUsage.BILLING_ZONE;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.powerbill.consumer.MeterDirectory;
import com.powerbill.consumer.MeterDirectory.Meter;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class AmiSimulator {
    private final JdbcClient db;
    private final MeterDirectory meters;

    AmiSimulator(JdbcClient db, MeterDirectory meters) {
        this.db = db;
        this.meters = meters;
    }

    @Transactional
    int simulate(UUID meterId, int months) {
        Meter meter = meters.meter(meterId);
        if (!meter.meterSerial().startsWith("M-DEMO-")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Select a demo meter.");
        }
        return populate(meter, months);
    }

    /**
     * Adds readings after the meter's latest one (or for the whole window if it has none) without filling older gaps,
     * so missing hours planted for the VEE tools survive restarts until someone estimates or imports them.
     */
    @Transactional
    int extend(UUID meterId, int months) {
        Meter meter = meters.meter(meterId);
        if (!meter.meterSerial().startsWith("M-DEMO-")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Select a demo meter.");
        }
        Timestamp latest = db.sql("SELECT max(recorded_at) AS latest FROM meter_readings WHERE meter_id=?")
                .param(meterId).query((row, index) -> row.getTimestamp("latest")).list().getFirst();
        return populate(meter, months, latest == null ? null : latest.toInstant().plusSeconds(3600));
    }

    private int populate(Meter meter, int months) {
        return populate(meter, months, null);
    }

    private int populate(Meter meter, int months, Instant after) {
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(BILLING_ZONE).truncatedTo(java.time.temporal.ChronoUnit.HOURS);
        LocalDate firstOfMonth = now.toLocalDate().withDayOfMonth(1);
        Instant startInstant = firstOfMonth.minusMonths(months).atStartOfDay(BILLING_ZONE).toInstant();
        if (after != null && after.isAfter(startInstant)) {
            startInstant = after;
        }
        Instant endInstant = now.toInstant();
        if (!startInstant.isBefore(endInstant)) {
            return 0;
        }
        // Deterministic demo scenarios for the anomaly detector. Only newly inserted hours receive them.
        Instant tamperFrom = endInstant;
        Instant tamperTo = endInstant;
        Instant nightFrom = endInstant;
        Instant zeroFrom = endInstant;
        Instant zeroTo = endInstant;
        String serial = meter.meterSerial();
        if (serial.endsWith("-04-1")) {
            tamperFrom = now.minusDays(15).toInstant();
            tamperTo = now.minusDays(12).toInstant();
        } else if (serial.endsWith("-07-1")) {
            nightFrom = now.minusDays(6).truncatedTo(java.time.temporal.ChronoUnit.DAYS).toInstant();
        } else if (serial.endsWith("-12-1")) {
            zeroFrom = now.minusDays(25).toInstant();
            zeroTo = now.minusDays(23).toInstant();
        }
        // VEE scenarios for the staff tools. A voltage sag the day before yesterday is flagged, and a communications
        // outage yesterday leaves missing hours to estimate. A current-transformer fault two months back flags four days
        // of readings, so that month misses the 90% coverage rule, its bill is held, and the regional loss proxy shows it.
        Instant sagFrom = endInstant;
        Instant sagTo = endInstant;
        Instant gapFrom = endInstant;
        Instant gapTo = endInstant;
        Instant faultFrom = endInstant;
        Instant faultTo = endInstant;
        if (serial.endsWith("-03-1")) {
            sagFrom = now.minusDays(2).withHour(19).toInstant();
            sagTo = now.minusDays(2).withHour(22).toInstant();
            gapFrom = now.minusDays(1).withHour(1).toInstant();
            gapTo = now.minusDays(1).withHour(5).toInstant();
        } else if (serial.endsWith("-14-1")) {
            faultFrom = firstOfMonth.minusMonths(2).plusDays(9).atStartOfDay(BILLING_ZONE).toInstant();
            faultTo = firstOfMonth.minusMonths(2).plusDays(13).atStartOfDay(BILLING_ZONE).toInstant();
        }
        return db.sql("""
                WITH hours AS (
                    SELECT t, extract(hour FROM t AT TIME ZONE 'Asia/Kolkata') AS hour,
                        extract(month FROM t AT TIME ZONE 'Asia/Kolkata') AS month,
                        extract(isodow FROM t AT TIME ZONE 'Asia/Kolkata') AS day,
                        row_number() OVER (ORDER BY t) AS sequence
                    FROM generate_series(CAST(:start AS timestamptz),
                        CAST(:finish AS timestamptz)-interval '1 hour', interval '1 hour') AS t
                ), load AS (
                    SELECT t, hour, sequence,
                        CAST(:kw AS numeric) * CAST(:intensity AS numeric) * 0.4 * (0.08 + (CASE WHEN hour BETWEEN 18 AND 22 THEN 0.23
                            WHEN hour BETWEEN 7 AND 9 THEN 0.14 ELSE 0.06 END)
                            + CASE WHEN month BETWEEN 3 AND 6 THEN 0.10 ELSE 0 END
                            + CASE WHEN day >= 6 THEN 0.03 ELSE 0 END
                            + mod(sequence * 13 + :salt, 19) * 0.004)
                            + CASE WHEN t >= CAST(:nightFrom AS timestamptz) AND hour < 6
                                THEN CAST(:kw AS numeric) * 0.25 ELSE 0 END AS demand,
                        CAST(:solar AS numeric) * CASE WHEN hour BETWEEN 9 AND 16
                            THEN greatest(0, 0.72 - abs(12.5-hour)*0.15) ELSE 0 END AS generation,
                        CASE WHEN t >= CAST(:tamperFrom AS timestamptz) AND t < CAST(:tamperTo AS timestamptz) THEN 0.03
                             WHEN t >= CAST(:zeroFrom AS timestamptz) AND t < CAST(:zeroTo AS timestamptz) THEN 0
                             ELSE 1 END AS factor,
                        -- A stuck meter records no export either, so a solar meter's flatline is a true zero read.
                        CASE WHEN t >= CAST(:zeroFrom AS timestamptz) AND t < CAST(:zeroTo AS timestamptz) THEN 0
                             ELSE 1 END AS export_factor
                    FROM hours
                )
                INSERT INTO meter_readings(meter_id, recorded_at, import_kwh, export_kwh, voltage,
                    power_factor, reading_source, vee_status)
                SELECT :meter, t, round(greatest(demand - generation,0) * factor,4),
                    round(greatest(generation - demand,0) * export_factor,4),
                    CASE WHEN t >= CAST(:sagFrom AS timestamptz) AND t < CAST(:sagTo AS timestamptz) THEN 172
                         ELSE 228 + mod(sequence,11) END,
                    CASE WHEN t >= CAST(:faultFrom AS timestamptz) AND t < CAST(:faultTo AS timestamptz) THEN 0.45
                         ELSE 0.91 + mod(sequence,10) * 0.006 END,
                    'SIMULATOR',
                    CASE WHEN (t >= CAST(:sagFrom AS timestamptz) AND t < CAST(:sagTo AS timestamptz))
                           OR (t >= CAST(:faultFrom AS timestamptz) AND t < CAST(:faultTo AS timestamptz))
                         THEN 'FLAGGED' ELSE 'VALID' END
                FROM load WHERE NOT (t >= CAST(:gapFrom AS timestamptz) AND t < CAST(:gapTo AS timestamptz))
                ON CONFLICT (meter_id,recorded_at) DO NOTHING
                """).param("meter", meter.id()).param("start", Timestamp.from(startInstant))
                .param("finish", Timestamp.from(endInstant)).param("kw", meter.sanctionedLoadKw())
                .param("solar", meter.solarCapacityKw() == null ? java.math.BigDecimal.ZERO : meter.solarCapacityKw())
                .param("salt", Math.floorMod(meter.meterSerial().hashCode(), 100))
                // Households of the same size differ: a stable 0.75x to 1.25x usage intensity per meter.
                .param("intensity", java.math.BigDecimal.valueOf(75 + Math.floorMod(meter.meterSerial().hashCode() * 31, 51), 2))
                .param("tamperFrom", Timestamp.from(tamperFrom)).param("tamperTo", Timestamp.from(tamperTo))
                .param("nightFrom", Timestamp.from(nightFrom))
                .param("zeroFrom", Timestamp.from(zeroFrom)).param("zeroTo", Timestamp.from(zeroTo))
                .param("sagFrom", Timestamp.from(sagFrom)).param("sagTo", Timestamp.from(sagTo))
                .param("gapFrom", Timestamp.from(gapFrom)).param("gapTo", Timestamp.from(gapTo))
                .param("faultFrom", Timestamp.from(faultFrom)).param("faultTo", Timestamp.from(faultTo))
                .update();
    }
}

@Component
@Profile("dev & !prod")
@ConditionalOnProperty(name = "gridiq.demo.enabled", havingValue = "true")
@org.springframework.core.annotation.Order(100)
class DemoTelemetry implements ApplicationRunner {
    private final AmiSimulator simulator;
    private final MeterDirectory meters;

    DemoTelemetry(AmiSimulator simulator, MeterDirectory meters) {
        this.simulator = simulator;
        this.meters = meters;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Thirteen months: twelve billed by the demo seed, plus one complete earlier month with readings but no invoice.
        // Restarts only append hours after each meter's latest reading, so planted gaps stay missing.
        for (Meter meter : meters.demoMeters()) {
            simulator.extend(meter.id(), 13);
        }
    }
}
