package com.qb.analytics.service;

import com.qb.analytics.model.TopNResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * QueryService: dedicated read path for Top-N.
 * Chooses materialized (cache/Cassandra) vs custom range; delegates to AggregationService.
 */
@Service
public class QueryService {

    private static final Logger log = LoggerFactory.getLogger(QueryService.class);
    private final AggregationService aggregationService;

    public QueryService(AggregationService aggregationService) {
        this.aggregationService = aggregationService;
    }

    public TopNResponse getTopN(String tenantId, String merchantId, String timeframe, String metric, int n, String start, String end) {
        if (start != null || end != null) {
            log.info("QueryService getTopN path=customRange tenantId={} merchantId={} start={} end={} n={}", tenantId, merchantId, start, end, n);
            return aggregationService.getTopNForCustomRange(tenantId, merchantId, metric, n, start, end);
        }
        log.info("QueryService getTopN path=materialized tenantId={} merchantId={} timeframe={} n={}", tenantId, merchantId, timeframe, n);
        return aggregationService.getMaterializedTopN(tenantId, merchantId, timeframe, metric, n);
    }
}
