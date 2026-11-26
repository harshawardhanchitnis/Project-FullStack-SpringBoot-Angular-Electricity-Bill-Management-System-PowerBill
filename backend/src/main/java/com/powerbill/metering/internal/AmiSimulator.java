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

    private int populate(Meter meter, int months) {
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(BILLING_ZONE).truncatedTo(java.time.temporal.ChronoUnit.HOURS);
        LocalDate firstOfMonth = now.toLocalDate().withDayOfMonth(1);
        Instant startInstant = firstOfMonth.minusMonths(months).atStartOfDay(BILLING_ZONE).toInstant();
        Instant endInstant = now.toInstant();
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
                        CAST(:kw AS numeric) * 0.4 * (0.08 + (CASE WHEN hour BETWEEN 18 AND 22 THEN 0.23
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
                    round(greatest(generation - demand,0) * export_factor,4), 228 + mod(sequence,11),
                    0.91 + mod(sequence,10) * 0.006, 'SIMULATOR', 'VALID'
                FROM load ON CONFLICT (meter_id,recorded_at) DO NOTHING
                """).param("meter", meter.id()).param("start", Timestamp.from(startInstant))
                .param("finish", Timestamp.from(endInstant)).param("kw", meter.sanctionedLoadKw())
                .param("solar", meter.solarCapacityKw() == null ? java.math.BigDecimal.ZERO : meter.solarCapacityKw())
                .param("salt", Math.floorMod(meter.meterSerial().hashCode(), 100))
                .param("tamperFrom", Timestamp.from(tamperFrom)).param("tamperTo", Timestamp.from(tamperTo))
                .param("nightFrom", Timestamp.from(nightFrom))
                .param("zeroFrom", Timestamp.from(zeroFrom)).param("zeroTo", Timestamp.from(zeroTo))
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
        for (Meter meter : meters.demoMeters()) {
            simulator.simulate(meter.id(), 12);
        }
    }
}
