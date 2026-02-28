package com.qb.analytics.service.forecast;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qb.analytics.model.ForecastPoint;
import com.qb.analytics.config.ForecastConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Prophet forecast path only: get history JSON, build request, call ProphetScriptRunner, return points.
 * Does not write to store; caller uses ForecastWriteService.
 */
@Service
public class ProphetForecastService {

    private static final Logger log = LoggerFactory.getLogger(ProphetForecastService.class);

    private final FeatureExtractionService featureExtraction;
    private final ProphetScriptRunner scriptRunner;
    private final ObjectMapper objectMapper;
    private final ForecastConfig config;

    public ProphetForecastService(FeatureExtractionService featureExtraction,
                                  ProphetScriptRunner scriptRunner,
                                  ObjectMapper objectMapper,
                                  ForecastConfig config) {
        this.featureExtraction = featureExtraction;
        this.scriptRunner = scriptRunner;
        this.objectMapper = objectMapper;
        this.config = config;
    }

    /**
     * Run Prophet path: build request JSON (history + horizonDays), call script, return forecast points or empty list.
     */
    public List<ForecastPoint> predict(String tenantId, String merchantId, String categoryId, int horizonDays) {
        if (horizonDays <= 0) horizonDays = config.getHorizonDaysDefault();

        String historyJson = featureExtraction.getHistoryJson(tenantId, merchantId, categoryId, config.getHistoryDays());
        if (historyJson == null || "[]".equals(historyJson.trim())) {
            log.warn("ProphetForecastService no history merchantId={} categoryId={}", merchantId, categoryId);
            return List.of();
        }

        String requestJson = buildRequestJson(historyJson, horizonDays);
        if (requestJson == null) return List.of();

        List<ForecastPoint> points = scriptRunner.predict(requestJson);
        log.info("ProphetForecastService predicted merchantId={} categoryId={} pointsCount={}", merchantId, categoryId, points.size());
        return points;
    }

    private String buildRequestJson(String historyJson, int horizonDays) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.set("history", objectMapper.readTree(historyJson));
            root.put("horizonDays", horizonDays);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("ProphetForecastService buildRequestJson failed: {}", e.getMessage());
            return null;
        }
    }
}
