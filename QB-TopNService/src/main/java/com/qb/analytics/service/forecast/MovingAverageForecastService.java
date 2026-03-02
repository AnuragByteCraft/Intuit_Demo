package com.qb.analytics.service.forecast;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qb.analytics.config.DemoConfig;
import com.qb.analytics.model.ForecastPoint;
import com.qb.analytics.repository.S3FeatureStoreRepository;
import com.qb.analytics.config.ForecastConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MovingAverageForecastService {

    private static final Logger log = LoggerFactory.getLogger(MovingAverageForecastService.class);
    private final S3FeatureStoreRepository s3;
    private final ObjectMapper objectMapper;
    private final ForecastConfig config;
    private final DemoConfig demoConfig;
    private final Map<String, String> championModels = new ConcurrentHashMap<>();

    public MovingAverageForecastService(S3FeatureStoreRepository s3, ObjectMapper objectMapper, ForecastConfig config, DemoConfig demoConfig) {
        this.s3 = s3;
        this.objectMapper = objectMapper;
        this.config = config;
        this.demoConfig = demoConfig;
    }

    public List<ForecastPoint> runFor(String tenantId, String merchantId, String categoryId, int horizonDays) {
        if (horizonDays <= 0) horizonDays = config.getHorizonDaysDefault();

        String modelJson = train(tenantId, merchantId, categoryId);
        if (modelJson == null) return List.of();
        if (!passesQualityGate(modelJson)) {
            log.info("MovingAverageForecastService quality gate failed merchantId={} categoryId={}", merchantId, categoryId);
            return List.of();
        }
        saveChampion(tenantId, merchantId, categoryId, modelJson);
        return predict(tenantId, merchantId, categoryId, horizonDays);
    }

    public String train(String tenantId, String merchantId, String categoryId) {
        String featuresJson = s3.getFeatures(tenantId, merchantId, categoryId);
        if (featuresJson == null) {
            log.warn("MovingAverageForecastService no features tenantId={} merchantId={} categoryId={}", tenantId, merchantId, categoryId);
            return null;
        }
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("type", "MOVING_AVERAGE_BASELINE");
            root.set("features", objectMapper.readTree(featuresJson));
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("MovingAverageForecastService train failed tenantId={} merchantId={} categoryId={}", tenantId, merchantId, categoryId, e);
            return null;
        }
    }

    public boolean passesQualityGate(String modelJson) {
        if (modelJson == null) return false;
        try {
            JsonNode root = objectMapper.readTree(modelJson);
            JsonNode features = root.get("features");
            if (features == null) return false;
            JsonNode pointsNode = features.get("points");
            int points = pointsNode != null && pointsNode.isNumber() ? pointsNode.asInt() : 0;
            int minRequired = config.getMinPointsForBaseline() > 0 ? config.getMinPointsForBaseline() : 1;
            return points >= minRequired;
        } catch (Exception e) {
            return false;
        }
    }

    public void saveChampion(String tenantId, String merchantId, String categoryId, String modelJson) {
        championModels.put(registryKey(tenantId, merchantId, categoryId), modelJson);
        log.info("MovingAverageForecastService champion saved merchantId={} categoryId={}", merchantId, categoryId);
    }

    public List<ForecastPoint> predict(String tenantId, String merchantId, String categoryId, int horizonDays) {
        if (horizonDays <= 0) horizonDays = config.getHorizonDaysDefault();
        String modelJson = championModels.get(registryKey(tenantId, merchantId, categoryId));
        if (modelJson == null) {
            log.warn("MovingAverageForecastService no champion merchantId={} categoryId={}", merchantId, categoryId);
            return List.of();
        }
        double avg = extractDouble(modelJson, "avgSales");
        double vol = extractDouble(modelJson, "volatility");
        List<ForecastPoint> points = new ArrayList<>();
        LocalDate today = demoConfig.today();
        for (int i = 1; i <= horizonDays; i++) {
            String date = today.plusDays(i).toString();
            points.add(new ForecastPoint(date, avg, Math.max(0, avg - vol), avg + vol));
        }
        return points;
    }

    private static String registryKey(String tenantId, String merchantId, String categoryId) {
        return tenantId + ":" + merchantId + ":" + categoryId;
    }

    private double extractDouble(String modelJson, String key) {
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
