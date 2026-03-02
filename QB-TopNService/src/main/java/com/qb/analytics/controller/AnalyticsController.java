package com.qb.analytics.controller;

import com.qb.analytics.infra.TenantContext;
import com.qb.analytics.model.TopNResponse;
import com.qb.analytics.service.QueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsController.class);
    private final QueryService queryService;

    public AnalyticsController(QueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/topn")
    public ResponseEntity<TopNResponse> topN(@RequestParam("merchantId") String merchantId,
                                            @RequestParam(value = "timeframe", defaultValue = "WEEKLY") String timeframe,
                                            @RequestParam(value = "metric", defaultValue = "REVENUE") String metric,
                                            @RequestParam(value = "n", defaultValue = "10") int n,
                                            @RequestParam(value = "start", required = false) String start,
                                            @RequestParam(value = "end", required = false) String end,
                                            @RequestParam(value = "startDate", required = false) String startDate,
                                            @RequestParam(value = "endDate", required = false) String endDate) {
        if (start == null && startDate != null) start = startDate;
        if (end == null && endDate != null) end = endDate;
        String tenantId = TenantContext.getTenantId();
        log.info("Analytics topn request tenantId={} merchantId={} timeframe={} metric={} n={} start={} end={}", tenantId, merchantId, timeframe, metric, n, start, end);
        TopNResponse resp = queryService.getTopN(tenantId, merchantId, timeframe, metric, n, start, end);
        log.info("Analytics topn response itemCount={}", resp.getItems() != null ? resp.getItems().size() : 0);
        return ResponseEntity.ok(resp);
    }
}
