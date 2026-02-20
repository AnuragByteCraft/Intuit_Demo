package com.qb.analytics.service.forecast;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qb.analytics.repository.CassandraServingRepository;
import com.qb.analytics.repository.S3FeatureStoreRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single entrypoint for the forecast pipeline: target selection, feature extraction,
 * training (baseline), quality gate, champion registry (in-memory map), inference.
 */
@Service
public class ForecastPipelineRunner {

    private static final Logger log = LoggerFactory.getLogger(ForecastPipelineRunner.class);
    private final CassandraServingRepository cassandra;
    private final S3FeatureStoreRepository s3;
    private final FeatureExtractionService featureExtraction;
    private final BatchInferenceService inference;
    private final ObjectMapper objectMapper;
    private final Map<String, String> championModels = new ConcurrentHashMap<>();

    @Value("${demo.forecast.historyDays:90}")
    private int historyDays;

    @Value("${demo.forecast.horizonDaysDefault:7}")
    private int horizonDaysDefault;

    public ForecastPipelineRunner(CassandraServingRepository cassandra,
                                  S3FeatureStoreRepository s3,
                                  FeatureExtractionService featureExtraction,
                                  @Lazy BatchInferenceService inference,
                                  ObjectMapper objectMapper) {
        this.cassandra = cassandra;
        this.s3 = s3;
        this.featureExtraction = featureExtraction;
        this.inference = inference;
        this.objectMapper = objectMapper;
    }

    public void saveChampion(String tenantId, String merchantId, String categoryId, String modelJson) {
        championModels.put(registryKey(tenantId, merchantId, categoryId), modelJson);
        log.info("ModelRegistry champion saved tenantId={} merchantId={} categoryId={}", tenantId, merchantId, categoryId);
    }

    public String getChampion(String tenantId, String merchantId, String categoryId) {
        return championModels.get(registryKey(tenantId, merchantId, categoryId));
    }

    private static String registryKey(String tenantId, String merchantId, String categoryId) {
        return tenantId + ":" + merchantId + ":" + categoryId;
    }

    public void run(String tenantId) {
        Map<String, Set<String>> targets = selectTargets(tenantId);
        log.info("ForecastPipeline starting tenantId={} merchants={}", tenantId, targets.size());
        for (Map.Entry<String, Set<String>> entry : targets.entrySet()) {
            String merchantId = entry.getKey();
            for (String categoryId : entry.getValue()) {
                log.info("ForecastPipeline processing merchantId={} categoryId={}", merchantId, categoryId);
                featureExtraction.buildAndStoreFeatures(tenantId, merchantId, categoryId, historyDays);
                String candidate = trainCandidateModel(tenantId, merchantId, categoryId);
                if (!passesQualityGate(candidate)) {
                    log.info("ForecastPipeline model rejected merchantId={} categoryId={} (quality gate failed)", merchantId, categoryId);
                    continue;
                }
                saveChampion(tenantId, merchantId, categoryId, candidate);
                inference.runFor(tenantId, merchantId, categoryId, horizonDaysDefault);
                log.info("ForecastPipeline completed merchantId={} categoryId={}", merchantId, categoryId);
            }
        }
        log.info("ForecastPipeline finished tenantId={}", tenantId);
    }

    private Map<String, Set<String>> selectTargets(String tenantId) {
        Map<String, Set<String>> targets = new HashMap<>();
        Set<String> merchants = cassandra.listMerchants(tenantId);
        for (String m : merchants) {
            targets.put(m, cassandra.listCategoriesForMerchant(tenantId, m));
        }
        log.info("ForecastOrchestrator selectTargets tenantId={} merchantCount={} targets={}", tenantId, merchants.size(), targets);
        return targets;
    }

    private String trainCandidateModel(String tenantId, String merchantId, String categoryId) {
        String featuresJson = s3.getFeatures(tenantId, merchantId, categoryId);
        if (featuresJson == null) {
            log.warn("Training no features found tenantId={} merchantId={} categoryId={}", tenantId, merchantId, categoryId);
            return null;
        }
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("type", "MOVING_AVERAGE_BASELINE");
            root.set("features", objectMapper.readTree(featuresJson));
            log.info("Training candidate model built tenantId={} merchantId={} categoryId={}", tenantId, merchantId, categoryId);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("Training failed to build model JSON tenantId={} merchantId={} categoryId={}", tenantId, merchantId, categoryId, e);
            return null;
        }
    }

    private boolean passesQualityGate(String candidateModelJson) {
        if (candidateModelJson == null) {
            log.info("ModelEvaluation quality gate failed candidate=null");
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(candidateModelJson);
            JsonNode features = root.get("features");
            if (features == null) return false;
            JsonNode pointsNode = features.get("points");
            int points = pointsNode != null && pointsNode.isNumber() ? pointsNode.asInt() : 0;
            boolean pass = points >= 7;
            log.info("ModelEvaluation quality gate points={} pass={}", points, pass);
            return pass;
        } catch (Exception e) {
            log.info("ModelEvaluation quality gate failed parse error");
            return false;
        }
    }
}
