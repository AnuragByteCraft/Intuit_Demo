package com.qb.analytics.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Basic tests for bucketRange (date range for Top-N). */
class AggregationServiceTest {

    @Test
    void bucketRange_daily_returnsSingleDay() {
        LocalDate wed = LocalDate.of(2026, 2, 18);
        AggregationService.BucketRange r = AggregationService.bucketRange("DAILY", wed);
        assertThat(r.bucketStart).isEqualTo("2026-02-18");
        assertThat(r.startDay).isEqualTo("2026-02-18");
        assertThat(r.endDay).isEqualTo("2026-02-18");
    }

    @Test
    void bucketRange_weekly_returnsMondayToSunday() {
        LocalDate wed = LocalDate.of(2026, 2, 18);
        AggregationService.BucketRange r = AggregationService.bucketRange("WEEKLY", wed);
        assertThat(r.bucketStart).isEqualTo("2026-02-16");
        assertThat(r.startDay).isEqualTo("2026-02-16");
        assertThat(r.endDay).isEqualTo("2026-02-22");
    }

    @Test
    void bucketRange_monthly_returnsFirstToLastOfMonth() {
        LocalDate mid = LocalDate.of(2026, 2, 15);
        AggregationService.BucketRange r = AggregationService.bucketRange("MONTHLY", mid);
        assertThat(r.bucketStart).isEqualTo("2026-02-01");
        assertThat(r.startDay).isEqualTo("2026-02-01");
        assertThat(r.endDay).isEqualTo("2026-02-28");
    }

    @Test
    void bucketRange_yearly_returnsJanToDec() {
        LocalDate mid = LocalDate.of(2026, 6, 15);
        AggregationService.BucketRange r = AggregationService.bucketRange("YEARLY", mid);
        assertThat(r.bucketStart).isEqualTo("2026-01-01");
        assertThat(r.startDay).isEqualTo("2026-01-01");
        assertThat(r.endDay).isEqualTo("2026-12-31");
    }

    @Test
    void bucketRange_unsupportedTimeframe_throws() {
        assertThatThrownBy(() -> AggregationService.bucketRange("INVALID", LocalDate.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported materialized timeframe");
    }
}
