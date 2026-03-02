package com.qb.analytics.service.forecast;

import com.qb.analytics.model.ForecastPoint;
import com.qb.analytics.model.ForecastResponse;
import com.qb.analytics.repository.CassandraServingRepository;
import com.qb.analytics.config.ForecastConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ForecastPipelineRunner {

    private static final Logger log = LoggerFactory.getLogger(ForecastPipelineRunner.class);

    private final CassandraServingRepository cassandra;
    private final FeatureExtractionService featureExtraction;
    private final MovingAverageForecastService movingAverageForecastService;
    private final ProphetForecastService prophetForecastService;
    private final ForecastWriteService forecastWriteService;
    private final ForecastConfig config;

    public ForecastPipelineRunner(CassandraServingRepository cassandra,
                                  FeatureExtractionService featureExtraction,
                                  MovingAverageForecastService movingAverageForecastService,
                                  ProphetForecastService prophetForecastService,
                                  ForecastWriteService forecastWriteService,
                                  ForecastConfig config) {
        this.cassandra = cassandra;
        this.featureExtraction = featureExtraction;
        this.movingAverageForecastService = movingAverageForecastService;
        this.prophetForecastService = prophetForecastService;
        this.forecastWriteService = forecastWriteService;
        this.config = config;
    }

    public void runForAllTenants() {
        Set<String> tenantIds = cassandra.listTenantIds();
        log.info("ForecastPipeline scheduled run for all tenants count={}", tenantIds.size());
        for (String tenantId : tenantIds) {
            run(tenantId);
        }
        log.info("ForecastPipeline scheduled run completed for all tenants");
    }

    public void run(String tenantId) {
        Map<String, Set<String>> targets = selectTargets(tenantId);
        log.info("ForecastPipeline starting tenantId={} merchants={}", tenantId, targets.size());

        for (Map.Entry<String, Set<String>> entry : targets.entrySet()) {
            String merchantId = entry.getKey();
            for (String categoryId : entry.getValue()) {
                log.info("ForecastPipeline processing merchantId={} categoryId={}", merchantId, categoryId);
                featureExtraction.buildAndStoreFeatures(tenantId, merchantId, categoryId, config.getHistoryDays());

                int pointsCount = featureExtraction.getPointsCountFromStoredFeatures(tenantId, merchantId, categoryId);
                List<ForecastPoint> points;
                boolean useProphet = pointsCount >= config.getMinHistoryDaysForProphet();

                if (useProphet) {
                    points = prophetForecastService.predict(tenantId, merchantId, categoryId, config.getHorizonDaysDefault());
                    if (!points.isEmpty()) {
                        forecastWriteService.write(tenantId, merchantId, categoryId, config.getHorizonDaysDefault(), points, ForecastResponse.MODEL_USED_PROPHET);
                    } else {
                        log.info("ForecastPipeline Prophet produced no points, falling back to baseline merchantId={} categoryId={}", merchantId, categoryId);
                        points = movingAverageForecastService.runFor(tenantId, merchantId, categoryId, config.getHorizonDaysDefault());
                        if (!points.isEmpty()) {
                            forecastWriteService.write(tenantId, merchantId, categoryId, config.getHorizonDaysDefault(), points);
                        }
                    }
                } else {
                    points = movingAverageForecastService.runFor(tenantId, merchantId, categoryId, config.getHorizonDaysDefault());
                    if (!points.isEmpty()) {
                        forecastWriteService.write(tenantId, merchantId, categoryId, config.getHorizonDaysDefault(), points);
                    } else {
                        log.info("ForecastPipeline baseline produced no points (quality gate?) merchantId={} categoryId={}", merchantId, categoryId);
                    }
                }
                log.info("ForecastPipeline completed merchantId={} categoryId={}", merchantId, categoryId);
            }
        }
        log.info("ForecastPipeline finished tenantId={}", tenantId);
    }

    private Map<String, Set<String>> selectTargets(String tenantId) {
        Set<String> merchants = cassandra.listMerchants(tenantId);
        Map<String, Set<String>> targets = new HashMap<>();
        for (String m : merchants) {
            targets.put(m, cassandra.listCategoriesForMerchant(tenantId, m));
        }
        log.info("ForecastPipeline selectTargets tenantId={} merchantCount={}", tenantId, merchants.size());
        return targets;
    }
}
