package com.qb.analytics.service.forecast;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Optional scheduler for the forecast pipeline. When enabled, runs the pipeline on a fixed delay
 * for all tenants that have aggregate data. POST /api/v1/forecast/run remains for on-demand runs (single tenant).
 *
 * Enable with: demo.forecast.scheduleEnabled=true
 */
@Component
@ConditionalOnProperty(name = "demo.forecast.scheduleEnabled", havingValue = "true")
public class ForecastPipelineScheduler {

    private static final Logger log = LoggerFactory.getLogger(ForecastPipelineScheduler.class);

    private final ForecastPipelineRunner runner;

    public ForecastPipelineScheduler(ForecastPipelineRunner runner) {
        this.runner = runner;
    }

    @Scheduled(fixedDelayString = "${demo.schedules.forecastDelayMs:3600000}")
    public void runScheduled() {
        log.info("ForecastPipelineScheduler running for all tenants");
        runner.runForAllTenants();
        log.info("ForecastPipelineScheduler completed for all tenants");
    }
}
