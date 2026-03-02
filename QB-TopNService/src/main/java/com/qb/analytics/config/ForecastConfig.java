package com.qb.analytics.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "demo.forecast")
public class ForecastConfig {

    private int historyDays = 90;
    private int horizonDaysDefault = 7;
    private int minHistoryDaysForProphet = 90;
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
