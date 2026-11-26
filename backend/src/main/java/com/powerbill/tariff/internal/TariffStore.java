package com.powerbill.tariff.internal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.powerbill.tariff.TariffPlan;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class TariffStore {
    private final JdbcClient db;
    private final JdbcTemplate batches;

    TariffStore(JdbcClient db, JdbcTemplate batches) {
        this.db = db;
        this.batches = batches;
    }

    List<TariffPlan> on(LocalDate date) {
        return db.sql("""
                SELECT * FROM tariff_plans WHERE is_active AND effective_from<=?
                    AND (effective_to IS NULL OR effective_to>?) ORDER BY connection_type,code
                """).params(date, date).query(Row.class).list().stream().map(this::assemble).toList();
    }

    List<TariffPlan> list() {
        return db.sql("""
                SELECT * FROM tariff_plans ORDER BY connection_type,effective_from DESC,code,version DESC
                """).query(Row.class).list().stream().map(this::assemble).toList();
    }

    Optional<TariffPlan> on(String type, LocalDate date) {
        return db.sql("""
                SELECT * FROM tariff_plans WHERE connection_type=? AND is_active AND effective_from<=?
                    AND (effective_to IS NULL OR effective_to>?)
                """).params(type, date, date).query(Row.class).optional().map(this::assemble);
    }

    Optional<TariffPlan> find(UUID id) {
        return db.sql("SELECT * FROM tariff_plans WHERE id=?").param(id).query(Row.class).optional().map(this::assemble);
    }

    void lock(String category) {
        db.sql("SELECT pg_advisory_xact_lock(hashtext(?))").param("gridiq:tariff:" + category)
                .query((rs, row) -> row).single();
    }

    int nextVersion(String code) {
        return db.sql("SELECT coalesce(max(version),0)+1 FROM tariff_plans WHERE code=?")
                .param(code).query(Integer.class).single();
    }

    Optional<String> categoryOf(String code) {
        return db.sql("SELECT DISTINCT connection_type FROM tariff_plans WHERE code=?")
                .param(code).query(String.class).optional();
    }

    boolean overlaps(UUID id, String type, LocalDate from, LocalDate to) {
        return db.sql("""
                SELECT EXISTS(SELECT 1 FROM tariff_plans WHERE id<>? AND connection_type=? AND is_active
                    AND daterange(effective_from,effective_to,'[)') && daterange(?,?,'[)'))
                """).params(id, type, from, to).query(Boolean.class).single();
    }

    void closePrevious(String code, String category, LocalDate starts) {
        db.sql("""
                UPDATE tariff_plans SET effective_to=? WHERE code=? AND connection_type=? AND is_active
                    AND effective_from<? AND (effective_to IS NULL OR effective_to>?)
                """).params(starts, code, category, starts, starts).update();
    }

    void insert(TariffPlan plan, UUID creator) {
        db.sql("""
                INSERT INTO tariff_plans(id,code,version,name,connection_type,effective_from,effective_to,fixed_charge_per_kw,
                    fac_per_kwh,electricity_duty_pct,gst_pct,solar_export_rate_per_kwh,solar_policy,
                    late_fee_daily_pct,late_fee_cap_pct,grace_days,is_active,created_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """).params(plan.id(), plan.code(), plan.version(), plan.name(), plan.connectionType(),
                plan.effectiveFrom(), plan.effectiveTo(), plan.fixedChargePerKw(), plan.facPerKwh(),
                plan.electricityDutyPct(), plan.gstPct(), plan.solarExportRatePerKwh(), plan.solarPolicy().name(),
                plan.lateFeeDailyPct(), plan.lateFeeCapPct(), plan.graceDays(), plan.active(), creator).update();
        writeRules(plan);
    }

    void update(TariffPlan plan) {
        db.sql("""
                UPDATE tariff_plans SET name=?,effective_from=?,effective_to=?,fixed_charge_per_kw=?,fac_per_kwh=?,
                    electricity_duty_pct=?,gst_pct=?,solar_export_rate_per_kwh=?,solar_policy=?,late_fee_daily_pct=?,
                    late_fee_cap_pct=?,grace_days=? WHERE id=?
                """).params(plan.name(), plan.effectiveFrom(), plan.effectiveTo(), plan.fixedChargePerKw(),
                plan.facPerKwh(), plan.electricityDutyPct(), plan.gstPct(), plan.solarExportRatePerKwh(),
                plan.solarPolicy().name(), plan.lateFeeDailyPct(), plan.lateFeeCapPct(), plan.graceDays(), plan.id()).update();
        db.sql("DELETE FROM tariff_slabs WHERE tariff_plan_id=?").param(plan.id()).update();
        db.sql("DELETE FROM tod_windows WHERE tariff_plan_id=?").param(plan.id()).update();
        writeRules(plan);
    }

    void activate(UUID id) {
        db.sql("UPDATE tariff_plans SET is_active=true WHERE id=?").param(id).update();
    }

    private void writeRules(TariffPlan plan) {
        for (int index = 0; index < plan.slabs().size(); index++) {
            var slab = plan.slabs().get(index);
            db.sql("""
                    INSERT INTO tariff_slabs(tariff_plan_id,slab_order,min_units,max_units,rate_per_kwh)
                    VALUES (?,?,?,?,?)
                    """).params(plan.id(), index + 1, slab.fromKwh(), slab.untilKwh(), slab.ratePerKwh()).update();
        }
        batches.batchUpdate("INSERT INTO tod_windows(tariff_plan_id,start_hour,end_hour,name,rate_multiplier)"
                        + " VALUES (?,?,?,?,?)", plan.windows(), 24, (statement, window) -> {
                    statement.setObject(1, plan.id());
                    statement.setInt(2, window.startHour());
                    statement.setInt(3, window.endHour());
                    statement.setString(4, window.name());
                    statement.setBigDecimal(5, window.rateMultiplier());
                });
    }

    private TariffPlan assemble(Row row) {
        List<TariffPlan.Slab> slabs = db.sql("""
                SELECT min_units AS from_kwh, max_units AS until_kwh, rate_per_kwh
                FROM tariff_slabs WHERE tariff_plan_id=? ORDER BY slab_order
                """).param(row.id()).query(TariffPlan.Slab.class).list();
        List<TariffPlan.TimeWindow> windows = db.sql("""
                SELECT name,start_hour,end_hour,rate_multiplier
                FROM tod_windows WHERE tariff_plan_id=? ORDER BY start_hour
                """).param(row.id()).query(TariffPlan.TimeWindow.class).list();
        return new TariffPlan(row.id(), row.code(), row.version(), row.name(), row.connectionType(),
                row.effectiveFrom(), row.effectiveTo(), row.fixedChargePerKw(), row.facPerKwh(),
                row.electricityDutyPct(), row.gstPct(), row.solarExportRatePerKwh(), row.solarPolicy(),
                row.lateFeeDailyPct(), row.lateFeeCapPct(), row.graceDays(), row.isActive(), slabs, windows);
    }

    record Row(UUID id, String code, int version, String name, String connectionType,
            LocalDate effectiveFrom, LocalDate effectiveTo, BigDecimal fixedChargePerKw, BigDecimal facPerKwh,
            BigDecimal electricityDutyPct, BigDecimal gstPct, BigDecimal solarExportRatePerKwh,
            TariffPlan.SolarPolicy solarPolicy, BigDecimal lateFeeDailyPct, BigDecimal lateFeeCapPct, int graceDays,
            boolean isActive) {}
}
