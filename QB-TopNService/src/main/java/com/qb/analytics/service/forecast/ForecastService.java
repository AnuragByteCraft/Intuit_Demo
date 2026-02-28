package com.qb.analytics.service.forecast;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qb.analytics.model.ForecastPoint;
import com.qb.analytics.model.ForecastResponse;
import com.qb.analytics.repository.CassandraServingRepository;
import com.qb.analytics.repository.RedisCacheRepository;
import com.qb.analytics.config.ForecastConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Serves forecast reads: cache first, then Cassandra.
 */
@Service
public class ForecastService {

    private static final Logger log = LoggerFactory.getLogger(ForecastService.class);

    private final CassandraServingRepository cassandra;
    private final RedisCacheRepository redis;
    private final ObjectMapper objectMapper;
    private final ForecastConfig config;

    public ForecastService(CassandraServingRepository cassandra,
                           RedisCacheRepository redis,
                           ObjectMapper objectMapper,
                           ForecastConfig config) {
        this.cassandra = cassandra;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.config = config;
    }

    public ForecastResponse getForecast(String tenantId, String merchantId, String categoryId, int horizonDays) {
        int h = horizonDays <= 0 ? config.getHorizonDaysDefault() : horizonDays;
        String key = ForecastWriteService.cacheKey(tenantId, merchantId, categoryId, h);

        String cached = redis.get(key);
        if (cached != null) {
            try {
                ForecastResponse resp = objectMapper.readValue(cached, ForecastResponse.class);
                log.info("ForecastService cache hit tenantId={} merchantId={} categoryId={}", tenantId, merchantId, categoryId);
                return resp;
            } catch (JsonProcessingException e) {
                log.warn("ForecastService cache parse failed, falling back to store", e);
            }
        }

        log.info("ForecastService cache miss reading from store tenantId={} merchantId={} categoryId={}", tenantId, merchantId, categoryId);
        List<ForecastPoint> points = cassandra.fetchForecast(tenantId, merchantId, categoryId, h);
        return ForecastResponse.of(tenantId, merchantId, categoryId, h, points);
    }
}
