package com.powerbill.tariff.internal;

import static java.math.BigDecimal.ZERO;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.powerbill.audit.AuditAction;
import com.powerbill.common.RequestContext;
import com.powerbill.consumer.MeterDirectory;
import com.powerbill.metering.MeterUsage;
import com.powerbill.tariff.TariffPlan;
import com.powerbill.tariff.TariffPricing;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class TariffService implements TariffPricing {
    private final TariffStore store;
    private final TariffCalculator calculator;
    private final MeterDirectory meters;
    private final MeterUsage usage;
    private final ApplicationEventPublisher events;

    TariffService(TariffStore store, TariffCalculator calculator, MeterDirectory meters, MeterUsage usage,
            ApplicationEventPublisher events) {
        this.store = store;
        this.calculator = calculator;
        this.meters = meters;
        this.usage = usage;
        this.events = events;
    }

    List<TariffPlan> active() {
        return store.on(today());
    }

    List<TariffPlan> all() {
        return store.list();
    }

    @Override
    public TariffPlan applicablePlan(String type, LocalDate date) {
        return store.on(type, date).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No tariff applies on the requested date."));
    }

    @Override
    public Quote price(TariffPlan plan, BigDecimal load, boolean netMetering, BigDecimal previousCredit,
            List<Interval> intervals) {
        return calculator.price(plan, load, netMetering, previousCredit, intervals);
    }

    @Transactional
    TariffPlan create(TariffRequests.Draft input, Jwt actor, RequestContext request) {
        store.lock("code:" + input.code());
        store.lock(input.connectionType());
        store.categoryOf(input.code()).filter(category -> !category.equals(input.connectionType())).ifPresent(category -> {
            throw conflict("A tariff code must stay in the same connection category.");
        });
        var draft = input.create(UUID.randomUUID(), store.nextVersion(input.code()), false);
        validate(draft);
        store.insert(draft, UUID.fromString(actor.getSubject()));
        audit("TARIFF_VERSION_CREATED", draft, actor, request);
        return draft;
    }

    @Transactional
    TariffPlan update(UUID id, TariffRequests.Draft input, Jwt actor, RequestContext request) {
        store.lock(input.connectionType());
        var previous = find(id);
        if (previous.active() || !previous.code().equals(input.code())
                || !previous.connectionType().equals(input.connectionType())) {
            throw conflict("Only unpublished drafts can be edited. Keep the existing code and connection category.");
        }
        var draft = input.create(id, previous.version(), false);
        validate(draft);
        store.update(draft);
        audit("TARIFF_DRAFT_UPDATED", draft, actor, request);
        return draft;
    }

    @Transactional
    TariffPlan activate(UUID id, Jwt actor, RequestContext request) {
        var plan = find(id);
        store.lock(plan.connectionType());
        plan = find(id);
        if (plan.active()) {
            return plan;
        }
        if (plan.effectiveFrom().isBefore(today()) && store.on(plan.connectionType(), today()).isPresent()) {
            throw conflict("A replacement tariff must start on or after the current date.");
        }
        store.closePrevious(plan.code(), plan.connectionType(), plan.effectiveFrom());
        if (store.overlaps(id, plan.connectionType(), plan.effectiveFrom(), plan.effectiveTo())) {
            throw conflict("This version overlaps another active tariff for the same connection category.");
        }
        store.activate(id);
        var active = find(id);
        audit("TARIFF_VERSION_ACTIVATED", active, actor, request);
        return active;
    }

    SimulationResult simulate(UUID user, TariffRequests.Simulation input) {
        var meter = meters.customerMeter(user, input.meterId());
        YearMonth month = input.month() == null ? YearMonth.from(today()).minusMonths(1) : input.month();
        if (month.isAfter(YearMonth.from(today()).minusMonths(1)) || month.isBefore(YearMonth.of(2000, 1))) {
            throw conflict("Select a complete historical billing month.");
        }
        LocalDate start = month.atDay(1);
        LocalDate end = month.plusMonths(1).atDay(1);
        var plan = applicablePlan(meter.connectionType(), start);
        if (plan.effectiveTo() != null && plan.effectiveTo().isBefore(end)) {
            throw conflict("This month spans more than one tariff version.");
        }
        var from = start.atStartOfDay(TariffCalculator.INDIA).toInstant();
        var to = end.atStartOfDay(TariffCalculator.INDIA).toInstant();
        var readings = usage.intervalReadings(meter.id(), from, to).stream()
                .filter(reading -> !reading.quality().equals("FLAGGED"))
                .map(reading -> new Interval(reading.start(), reading.importKwh(), reading.exportKwh())).toList();
        int expected = Math.toIntExact(ChronoUnit.HOURS.between(from, to));
        if (readings.size() != expected) {
            throw conflict("This simulation requires all hourly readings for the selected month.");
        }
        var baseline = price(plan, meter.sanctionedLoadKw(), meter.solarNetMetering(), ZERO, readings);
        var solarKw = value(input.rooftopSolarKw());
        var shift = value(input.loadShiftPct()).movePointLeft(2);
        var projectedIntervals = project(plan, readings, shift, solarKw);
        var projected = price(plan, meter.sanctionedLoadKw(), meter.solarNetMetering() || solarKw.signum() > 0,
                ZERO, projectedIntervals);
        BigDecimal annualized = baseline.totalInr().subtract(projected.totalInr()).multiply(BigDecimal.valueOf(12));
        BigDecimal payback = annualized.signum() > 0 && solarKw.signum() > 0 && input.installedCostInr() != null
                ? input.installedCostInr().divide(annualized, 2, RoundingMode.HALF_UP) : null;
        return new SimulationResult(month, plan.id(), expected, baseline, projected, annualized, payback);
    }

    private List<Interval> project(TariffPlan plan, List<Interval> history, BigDecimal shift, BigDecimal solarKw) {
        var days = new LinkedHashMap<LocalDate, List<Interval>>();
        history.forEach(interval -> days.computeIfAbsent(interval.start().atZone(TariffCalculator.INDIA).toLocalDate(),
                ignored -> new ArrayList<>()).add(interval));
        var result = new ArrayList<Interval>();
        for (List<Interval> day : days.values()) {
            long solarHours = day.stream().filter(interval -> slot(plan, interval).equals("SOLAR_OFF_PEAK")).count();
            BigDecimal moved = solarHours == 0 ? ZERO : day.stream()
                    .filter(interval -> slot(plan, interval).equals("EVENING_PEAK"))
                    .map(interval -> interval.importKwh().multiply(shift)).reduce(ZERO, BigDecimal::add);
            BigDecimal remaining = moved;
            BigDecimal hourlyShift = solarHours == 0 ? ZERO
                    : moved.divide(BigDecimal.valueOf(solarHours), 8, RoundingMode.DOWN);
            for (Interval interval : day) {
                String slot = slot(plan, interval);
                BigDecimal consumption = interval.importKwh();
                if (slot.equals("SOLAR_OFF_PEAK")) {
                    BigDecimal shifted = solarHours == 1 ? remaining : hourlyShift;
                    consumption = consumption.add(shifted);
                    remaining = remaining.subtract(shifted);
                    solarHours--;
                } else if (slot.equals("EVENING_PEAK") && moved.signum() > 0) {
                    consumption = consumption.subtract(consumption.multiply(shift));
                }
                int hour = interval.start().atZone(TariffCalculator.INDIA).getHour();
                BigDecimal sunFactor = BigDecimal.valueOf(Math.max(0, 68 - Math.abs(13 - hour) * 14), 2);
                BigDecimal net = consumption.subtract(solarKw.multiply(sunFactor));
                result.add(new Interval(interval.start(), net.max(ZERO), interval.exportKwh().add(net.negate().max(ZERO))));
            }
        }
        return result;
    }

    private static String slot(TariffPlan plan, Interval interval) {
        int hour = interval.start().atZone(TariffCalculator.INDIA).getHour();
        return plan.windows().stream().filter(w -> w.startHour() <= hour && w.endHour() > hour)
                .findFirst().orElseThrow().name();
    }

    private void audit(String action, TariffPlan plan, Jwt actor, RequestContext request) {
        events.publishEvent(AuditAction.record(action, "TARIFF", plan.id(), UUID.fromString(actor.getSubject()),
                actor.getClaimAsString("role"), request, Map.of("code", plan.code(), "version", plan.version())));
    }

    private TariffPlan find(UUID id) {
        return store.find(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Tariff version was not found."));
    }

    private static void validate(TariffPlan plan) {
        if (plan.effectiveTo() != null && !plan.effectiveFrom().isBefore(plan.effectiveTo())) {
            throw invalid("The exclusive end date must be after the start date.");
        }
        BigDecimal start = ZERO;
        for (var slab : plan.slabs()) {
            if (start == null || slab.fromKwh().compareTo(start) != 0
                    || (slab.untilKwh() != null && slab.untilKwh().compareTo(start) <= 0)) {
                throw invalid("Slabs must be contiguous, ordered, and start at zero.");
            }
            start = slab.untilKwh();
        }
        if (start != null) {
            throw invalid("The last slab must have an open upper boundary.");
        }
        int hour = 0;
        for (var window : plan.windows()) {
            if (window.startHour() != hour || window.endHour() <= hour) {
                throw invalid("Time windows must be ordered and contiguous without overlap.");
            }
            hour = window.endHour();
        }
        if (hour != 24) {
            throw invalid("Time windows must cover all 24 local hours.");
        }
    }

    private static BigDecimal value(BigDecimal value) {
        return value == null ? ZERO : value;
    }

    private static LocalDate today() {
        return LocalDate.now(TariffCalculator.INDIA);
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    record SimulationResult(YearMonth month, UUID tariffPlanId, int intervalCount, Quote baseline, Quote projected,
            BigDecimal annualizedSavingsInr, BigDecimal solarPaybackYears) {}
}
