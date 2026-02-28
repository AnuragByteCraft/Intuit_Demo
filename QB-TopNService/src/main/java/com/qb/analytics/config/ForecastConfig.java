package com.qb.analytics.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Shared forecast configuration. Injected into forecast services to avoid duplicate @Value.
 * Registered via @EnableConfigurationProperties(ForecastConfig.class) in MainApplication.
 */
@ConfigurationProperties(prefix = "demo.forecast")
public class ForecastConfig {

    private int historyDays = 90;
    private int horizonDaysDefault = 7;
    private int minHistoryDaysForProphet = 28;
    /** Minimum daily data points required for moving-average baseline (demo: 1 so one day of data yields a forecast). */
    private int minPointsForBaseline = 1;

    public int getHistoryDays() {
        return historyDays;
    }

    public void setHistoryDays(int historyDays) {
        this.historyDays = historyDays;
    }

    public int getHorizonDaysDefault() {
        return horizonDaysDefault;
    }

    public void setHorizonDaysDefault(int horizonDaysDefault) {
        this.horizonDaysDefault = horizonDaysDefault;
    }

    public int getMinHistoryDaysForProphet() {
        return minHistoryDaysForProphet;
    }

    public void setMinHistoryDaysForProphet(int minHistoryDaysForProphet) {
        this.minHistoryDaysForProphet = minHistoryDaysForProphet;
    }

    public int getMinPointsForBaseline() {
        return minPointsForBaseline;
    }

    public void setMinPointsForBaseline(int minPointsForBaseline) {
        this.minPointsForBaseline = minPointsForBaseline;
    }
}
