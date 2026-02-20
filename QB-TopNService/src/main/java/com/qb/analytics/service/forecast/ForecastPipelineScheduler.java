package com.qb.analytics.service.forecast;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Optional scheduler for the forecast pipeline. When enabled, runs the pipeline on a fixed delay
 * for a configured tenant (e.g. defaultTenant). In production you'd typically run forecast daily;
 * the endpoint POST /api/v1/forecast/run remains for on-demand runs.
 *
 * Enable with: demo.forecast.scheduleEnabled=true
 */
@Component
@ConditionalOnProperty(name = "demo.forecast.scheduleEnabled", havingValue = "true")
public class ForecastPipelineScheduler {

    private static final Logger log = LoggerFactory.getLogger(ForecastPipelineScheduler.class);

    private final ForecastPipelineRunner runner;
    private final String scheduledTenantId;

    public ForecastPipelineScheduler(ForecastPipelineRunner runner,
                                     @Value("${demo.forecast.scheduledTenantId:defaultTenant}") String scheduledTenantId) {
        this.runner = runner;
        this.scheduledTenantId = scheduledTenantId;
    }

    @Scheduled(fixedDelayString = "${demo.schedules.forecastDelayMs:3600000}")
    public void runScheduled() {
        log.info("ForecastPipelineScheduler running for tenantId={}", scheduledTenantId);
        runner.run(scheduledTenantId);
        log.info("ForecastPipelineScheduler completed for tenantId={}", scheduledTenantId);
    }
}
