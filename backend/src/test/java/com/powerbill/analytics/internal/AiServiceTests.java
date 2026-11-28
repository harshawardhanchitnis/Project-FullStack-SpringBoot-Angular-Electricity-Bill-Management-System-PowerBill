package com.powerbill.analytics.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.powerbill.analytics.AnalyticsEvents;
import com.powerbill.billing.BillingLedger;
import com.powerbill.consumer.AccountDirectory;
import com.powerbill.consumer.AccountDirectory.ServicePoint;
import com.powerbill.metering.AnomalyRegistry;
import com.powerbill.metering.MeterUsage;
import com.powerbill.support.SupportDirectory;
import com.powerbill.tariff.TariffPricing;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.ObjectMapper;

class AiServiceTests {
    private final AccountDirectory accounts = mock(AccountDirectory.class);
    private final MeterUsage usage = mock(MeterUsage.class);
    private final ForecastService forecasts = mock(ForecastService.class);
    private final MlClient ml = mock(MlClient.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private AiService service;

    @BeforeEach
    void setUp() {
        service = new AiService(accounts, usage, mock(BillingLedger.class), mock(SupportDirectory.class),
                mock(AnomalyRegistry.class), mock(TariffPricing.class), forecasts, ml, events, new SimpleMeterRegistry());
    }

    @Test
    void anAnomalyScanSurvivesOneFailedMeterAndStopsAfterThreeInARow() {
        var points = List.of(point(null), point(null), point(null), point(null), point(null), point(null));
        when(accounts.billableServicePoints()).thenReturn(points);
        when(ml.enabled()).thenReturn(true);
        when(usage.intervalReadings(any(), any(), any())).thenReturn(readings());
        var empty = new ObjectMapper().createObjectNode().set("anomalies", new ObjectMapper().createArrayNode());
        // Meter 1 fails, meter 2 succeeds (resetting the count), then meters 3-5 fail: the scan stops before meter 6.
        when(ml.post(eq("/ml/v1/anomalies/detect"), any(), anyString())).thenReturn(Optional.empty(),
                Optional.of(empty), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(empty));

        service.scanAnomalies("test");

        verify(ml, times(5)).post(eq("/ml/v1/anomalies/detect"), any(), anyString());
    }

    @Test
    void slabWarningsReachCustomersWithoutABudget() {
        var point = point(null);
        when(accounts.billableServicePoints()).thenReturn(List.of(point));
        when(forecasts.forecast(eq(point), any(), anyString())).thenReturn(forecast(2, "1500.00"));

        assertThat(service.budgetAlerts("test", LocalDate.of(2026, 11, 20))).isEqualTo(1);

        var alert = ArgumentCaptor.forClass(AnalyticsEvents.BudgetAlert.class);
        verify(events).publishEvent(alert.capture());
        assertThat(alert.getValue().kind()).isEqualTo("SLAB");
        assertThat(alert.getValue().budgetInr()).isNull();
        assertThat(alert.getValue().reason()).endsWith("300 kWh in 2 days.");
    }

    @Test
    void aBudgetAlertTakesPriorityAndNothingIsSentInTheFirstDaysOfACycle() {
        var point = point(new BigDecimal("1000"));
        when(accounts.billableServicePoints()).thenReturn(List.of(point));
        when(forecasts.forecast(eq(point), any(), anyString())).thenReturn(forecast(0, "1500.00"));

        assertThat(service.budgetAlerts("test", LocalDate.of(2026, 11, 3))).isZero();
        verify(events, never()).publishEvent(any(Object.class));

        assertThat(service.budgetAlerts("test", LocalDate.of(2026, 11, 20))).isEqualTo(1);
        var alert = ArgumentCaptor.forClass(AnalyticsEvents.BudgetAlert.class);
        verify(events).publishEvent(alert.capture());
        assertThat(alert.getValue().kind()).isEqualTo("BUDGET");
        assertThat(alert.getValue().budgetInr()).isEqualByComparingTo("1000");
    }

    @Test
    void slabCountdownsReadNaturally() {
        assertThat(AiService.whenText(0)).isEqualTo("today");
        assertThat(AiService.whenText(1)).isEqualTo("tomorrow");
        assertThat(AiService.whenText(4)).isEqualTo("in 4 days");
    }

    @Test
    void netMeteredSlabsUseNetBillableEnergy() {
        var solar = solarPoint();
        assertThat(AnalyticsService.netBillable(solar, new BigDecimal("420"), new BigDecimal("150"), new BigDecimal("40")))
                .isEqualByComparingTo("230");
        assertThat(AnalyticsService.netBillable(solar, new BigDecimal("100"), new BigDecimal("150"), BigDecimal.ZERO))
                .isEqualByComparingTo("0");
        assertThat(AnalyticsService.netBillable(point(null), new BigDecimal("420"), new BigDecimal("150"), BigDecimal.TEN))
                .isEqualByComparingTo("420");
    }

    private static ForecastService.Forecast forecast(int daysToNextSlab, String projectedInr) {
        var band = new ForecastService.Band(new BigDecimal(projectedInr), new BigDecimal(projectedInr),
                new BigDecimal(projectedInr));
        return new ForecastService.Forecast(UUID.randomUUID(), "M-1", "2026-11", LocalDate.of(2026, 11, 20), 19, 30,
                new BigDecimal("280"), BigDecimal.ZERO, band, band, new BigDecimal("300"), new BigDecimal("300"),
                daysToNextSlab, List.of(), "test", "ml", BigDecimal.ZERO, List.of());
    }

    private static ServicePoint point(BigDecimal budget) {
        return servicePoint(false, budget);
    }

    private static ServicePoint solarPoint() {
        return servicePoint(true, null);
    }

    private static ServicePoint servicePoint(boolean solar, BigDecimal budget) {
        var account = new AccountDirectory.Account(UUID.randomUUID(), UUID.randomUUID(), "PB-1", "Test", "RESIDENTIAL",
                "test@example.test", "9876543210", "ACTIVE", budget);
        return new ServicePoint(UUID.randomUUID(), "M-1", "DOMESTIC", new BigDecimal("5"), solar,
                solar ? new BigDecimal("3") : null, "ACTIVE", UUID.randomUUID(), "Home", "1 Road", "Pune",
                "Maharashtra", "411001", "Pune", null, account);
    }

    private static List<MeterUsage.Reading> readings() {
        var readings = new ArrayList<MeterUsage.Reading>();
        Instant start = Instant.parse("2026-10-01T00:00:00Z");
        for (int hour = 0; hour < 24 * 22; hour++) {
            readings.add(new MeterUsage.Reading(start.plus(Duration.ofHours(hour)), BigDecimal.ONE, BigDecimal.ZERO,
                    new BigDecimal("230"), new BigDecimal("0.95"), "SIMULATOR", "VALID"));
        }
        return readings;
    }
}
