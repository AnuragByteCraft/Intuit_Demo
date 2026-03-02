package com.qb.analytics.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class RootController {

    @GetMapping(value = {"/", "/api"}, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> root() {
        return Map.of(
                "application", "QB Commerce Analytics Demo",
                "status", "running",
                "message", "Use the API endpoints below. Ingest: headers X-Tenant-Id + X-Merchant-Id; body: transactionId, categoryId, amount, eventTime; optional: quantity (default 1), currency (default USD). Others: X-Tenant-Id.",
                "endpoints", Map.of(
                        "health", "GET /actuator/health",
                        "ingest", "POST /api/v1/webhooks/transactions",
                        "topn", "GET /api/v1/analytics/topn?merchantId=...&timeframe=WEEKLY&n=10",
                        "forecastRun", "POST /api/v1/forecast/run",
                        "forecastGet", "GET /api/v1/forecast?merchantId=...&categoryId=...&horizonDays=7"
                )
        );
    }
}
