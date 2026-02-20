package com.qb.analytics.controller;

import com.qb.analytics.infra.TenantContext;
import com.qb.analytics.model.TopNResponse;
import com.qb.analytics.service.QueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * AnalyticsController:
 * Dashboard reads for Top-N; read path is delegated to QueryService.
 */
@RestController
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsController.class);
    private final QueryService queryService;

    public AnalyticsController(QueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/topn")
    public ResponseEntity<TopNResponse> topN(@RequestParam String merchantId,
                                            @RequestParam(defaultValue = "WEEKLY") String timeframe,
                                            @RequestParam(defaultValue = "REVENUE") String metric,
                                            @RequestParam(defaultValue = "10") int n,
                                            @RequestParam(required = false) String start,
                                            @RequestParam(required = false) String end) {
        String tenantId = TenantContext.getTenantId();
        log.info("Analytics topn request tenantId={} merchantId={} timeframe={} metric={} n={} start={} end={}", tenantId, merchantId, timeframe, metric, n, start, end);
        TopNResponse resp = queryService.getTopN(tenantId, merchantId, timeframe, metric, n, start, end);
        log.info("Analytics topn response itemCount={}", resp.getItems() != null ? resp.getItems().size() : 0);
        return ResponseEntity.ok(resp);
    }
}
