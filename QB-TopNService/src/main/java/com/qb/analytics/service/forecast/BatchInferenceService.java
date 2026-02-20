package com.qb.analytics.service.forecast;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qb.analytics.model.ForecastPoint;
import com.qb.analytics.model.ForecastResponse;
import com.qb.analytics.repository.CassandraServingRepository;
import com.qb.analytics.repository.RedisCacheRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * BatchInferenceService:
 * - loads champion model from ForecastPipelineRunner (in-memory registry)
 * - generates forecasts (7/30 days)
 * - writes to Cassandra forecast store + Redis cache for hot reads
 *
 * Demo inference logic:
 * - take avgSales from model features
 * - forecast = avgSales for all future days
 * - confidence band uses volatility
 */
@Service
public class BatchInferenceService {

    private static final Logger log = LoggerFactory.getLogger(BatchInferenceService.class);
    private final ForecastPipelineRunner runner;
    private final CassandraServingRepository cassandra;
    private final RedisCacheRepository redis;
    private final ObjectMapper objectMapper;

    @Value("${demo.forecast.horizonDaysDefault:7}")
    private int horizonDefault;

    public BatchInferenceService(ForecastPipelineRunner runner,
                                 CassandraServingRepository cassandra,
                                 RedisCacheRepository redis,
                                 ObjectMapper objectMapper) {
        this.runner = runner;
        this.cassandra = cassandra;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public void runFor(String tenantId, String merchantId, String categoryId, int horizonDays) {

        if (horizonDays <= 0) horizonDays = horizonDefault;

        String model = runner.getChampion(tenantId, merchantId, categoryId);
        if (model == null) {
            log.warn("BatchInference no champion model tenantId={} merchantId={} categoryId={}", tenantId, merchantId, categoryId);
            return;
        }
        log.info("BatchInference running tenantId={} merchantId={} categoryId={} horizonDays={}", tenantId, merchantId, categoryId, horizonDays);

        double avg = extractDoubleFromModel(model, "avgSales");
        double vol = extractDoubleFromModel(model, "volatility");

        List<ForecastPoint> points = new ArrayList<>();
        for (int i = 1; i <= horizonDays; i++) {
            String date = LocalDate.now().plusDays(i).toString();
            points.add(new ForecastPoint(date, avg, Math.max(0, avg - vol), avg + vol));
        }

        cassandra.saveForecast(tenantId, merchantId, categoryId, horizonDays, points);

        ForecastResponse resp = ForecastResponse.of(tenantId, merchantId, categoryId, horizonDays, points);
        try {
            String cacheKey = ForecastKeys.cacheKey(tenantId, merchantId, categoryId, horizonDays);
            redis.put(cacheKey, objectMapper.writeValueAsString(resp), 300);
        } catch (Exception e) {
            log.warn("Failed to cache forecast", e);
        }
        log.info("BatchInference forecast stored tenantId={} merchantId={} categoryId={} pointsCount={}", tenantId, merchantId, categoryId, points.size());
    }

    private double extractDoubleFromModel(String modelJson, String key) {
        try {
            JsonNode root = objectMapper.readTree(modelJson);
            JsonNode features = root.get("features");
            if (features == null) return 0;
            JsonNode val = features.get(key);
            return val != null && val.isNumber() ? val.asDouble() : 0;
        } catch (Exception e) {
            return 0;
        }
    }
}
