package com.qb.analytics.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Basic tests: default values and setters.
 */
class ForecastConfigTest {

    @Test
    void defaults_matchApplicationYml() {
        ForecastConfig config = new ForecastConfig();
        assertThat(config.getHistoryDays()).isEqualTo(90);
        assertThat(config.getHorizonDaysDefault()).isEqualTo(7);
        assertThat(config.getMinHistoryDaysForProphet()).isEqualTo(28);
    }

    @Test
    void setters_updateValues() {
        ForecastConfig config = new ForecastConfig();
        config.setHistoryDays(60);
        config.setHorizonDaysDefault(14);
        config.setMinHistoryDaysForProphet(14);
        assertThat(config.getHistoryDays()).isEqualTo(60);
        assertThat(config.getHorizonDaysDefault()).isEqualTo(14);
        assertThat(config.getMinHistoryDaysForProphet()).isEqualTo(14);
    }
}
