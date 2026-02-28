package com.qb.analytics.service.forecast;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qb.analytics.model.ForecastPoint;
import com.qb.analytics.model.ForecastResponse;
import com.qb.analytics.repository.CassandraServingRepository;
import com.qb.analytics.repository.RedisCacheRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Shared write path: persist forecast points to Cassandra and cache (Redis).
 * Used by both Moving Average and Prophet paths. Cache key format is here so read path (ForecastService) matches.
 */
@Service
public class ForecastWriteService {

    private static final String CACHE_KEY_PREFIX = "forecast:";
    private static final int CACHE_TTL_SECONDS = 300;

    /** Cache key for forecast (read path must use same format). */
    public static String cacheKey(String tenantId, String merchantId, String categoryId, int horizonDays) {
        return CACHE_KEY_PREFIX + tenantId + ":" + merchantId + ":" + categoryId + ":" + horizonDays;
    }

    private static final Logger log = LoggerFactory.getLogger(ForecastWriteService.class);

    private final CassandraServingRepository cassandra;
    private final RedisCacheRepository redis;
    private final ObjectMapper objectMapper;

    public ForecastWriteService(CassandraServingRepository cassandra,
                                RedisCacheRepository redis,
                                ObjectMapper objectMapper) {
        this.cassandra = cassandra;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /**
     * Write forecast points to store and cache. Uses MOVING_AVERAGE_BASELINE as modelUsed.
     */
    public void write(String tenantId, String merchantId, String categoryId, int horizonDays,
                      List<ForecastPoint> points) {
        write(tenantId, merchantId, categoryId, horizonDays, points, ForecastResponse.MODEL_USED_BASELINE);
    }

    /**
     * Write forecast points to store and cache with given modelUsed (e.g. PROPHET).
     */
    public void write(String tenantId, String merchantId, String categoryId, int horizonDays,
                      List<ForecastPoint> points, String modelUsed) {
        if (points == null || points.isEmpty()) return;

        cassandra.saveForecast(tenantId, merchantId, categoryId, horizonDays, points);

        ForecastResponse resp = ForecastResponse.of(tenantId, merchantId, categoryId, horizonDays, points, modelUsed);
        try {
            String cacheKey = cacheKey(tenantId, merchantId, categoryId, horizonDays);
            redis.put(cacheKey, objectMapper.writeValueAsString(resp), CACHE_TTL_SECONDS);
        } catch (JsonProcessingException e) {
            log.warn("Failed to cache forecast tenantId={} merchantId={} categoryId={}", tenantId, merchantId, categoryId, e);
        }
        log.info("ForecastWriteService saved forecast tenantId={} merchantId={} categoryId={} pointsCount={} modelUsed={}",
                tenantId, merchantId, categoryId, points.size(), modelUsed);
    }
}
