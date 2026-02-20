package com.qb.analytics.controller;

import com.qb.analytics.infra.TenantContext;
import com.qb.analytics.model.ForecastResponse;
import com.qb.analytics.service.forecast.ForecastPipelineRunner;
import com.qb.analytics.service.forecast.ForecastService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * ForecastController:
 * - run pipeline (demo)
 * - fetch forecast
 */
@RestController
@RequestMapping("/api/v1/forecast")
public class ForecastController {

    private static final Logger log = LoggerFactory.getLogger(ForecastController.class);
    private final ForecastPipelineRunner runner;
    private final ForecastService forecastService;

    public ForecastController(ForecastPipelineRunner runner, ForecastService forecastService) {
        this.runner = runner;
        this.forecastService = forecastService;
    }

    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> run() {
        String tenantId = TenantContext.getTenantId();
        log.info("Forecast pipeline run triggered tenantId={}", tenantId);
        runner.run(tenantId);
        log.info("Forecast pipeline run completed tenantId={}", tenantId);
        return ResponseEntity.ok(Map.of("status", "OK", "message", "Forecast pipeline executed for tenant=" + tenantId));
    }

    @GetMapping
    public ResponseEntity<ForecastResponse> get(@RequestParam String merchantId,
                                                @RequestParam String categoryId,
                                                @RequestParam(defaultValue = "7") int horizonDays) {
        String tenantId = TenantContext.getTenantId();
        log.info("Forecast get request tenantId={} merchantId={} categoryId={} horizonDays={}", tenantId, merchantId, categoryId, horizonDays);
        ForecastResponse resp = forecastService.getForecast(tenantId, merchantId, categoryId, horizonDays);
        log.info("Forecast get response pointsCount={}", resp.getPoints() != null ? resp.getPoints().size() : 0);
        return ResponseEntity.ok(resp);
    }
}
