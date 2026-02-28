package com.qb.analytics.service.forecast;

import com.qb.analytics.config.ForecastConfig;
import com.qb.analytics.model.ForecastPoint;
import com.qb.analytics.repository.S3FeatureStoreRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Basic tests: quality gate (configurable minPointsForBaseline), predict from champion returns flat line.
 */
class MovingAverageForecastServiceTest {

    private S3FeatureStoreRepository s3;
    private ForecastConfig config;
    private MovingAverageForecastService service;

    @BeforeEach
    void setUp() {
        s3 = new S3FeatureStoreRepository();
        config = new ForecastConfig();
        config.setHorizonDaysDefault(7);
        config.setMinPointsForBaseline(1); // demo default: 1 day of data is enough
        service = new MovingAverageForecastService(s3, new ObjectMapper(), config);
    }

    @Test
    void passesQualityGate_whenMinIs7_andLessThan7Points_returnsFalse() {
        config.setMinPointsForBaseline(7);
        service = new MovingAverageForecastService(s3, new ObjectMapper(), config);
        String modelJson = "{\"type\":\"MOVING_AVERAGE_BASELINE\",\"features\":{\"avgSales\":100,\"volatility\":10,\"points\":5}}";
        assertThat(service.passesQualityGate(modelJson)).isFalse();
    }

    @Test
    void passesQualityGate_onePoint_withMinOne_returnsTrue() {
        String modelJson = "{\"type\":\"MOVING_AVERAGE_BASELINE\",\"features\":{\"avgSales\":100,\"volatility\":10,\"points\":1}}";
        assertThat(service.passesQualityGate(modelJson)).isTrue();
    }

    @Test
    void passesQualityGate_zeroPoints_returnsFalse() {
        String modelJson = "{\"type\":\"MOVING_AVERAGE_BASELINE\",\"features\":{\"avgSales\":100,\"volatility\":10,\"points\":0}}";
        assertThat(service.passesQualityGate(modelJson)).isFalse();
    }

    @Test
    void runFor_noFeaturesInS3_returnsEmpty() {
        List<ForecastPoint> result = service.runFor("t1", "m1", "cat1", 7);
        assertThat(result).isEmpty();
    }

    @Test
    void runFor_featuresWithOnePoint_savesChampionAndReturnsPoints() {
        String featuresJson = "{\"avgSales\":100.0,\"last7Avg\":95.0,\"volatility\":15.0,\"points\":1}";
        s3.putFeatures("t1", "m1", "cat1", featuresJson);
        List<ForecastPoint> result = service.runFor("t1", "m1", "cat1", 7);
        assertThat(result).hasSize(7);
        assertThat(result.get(0).getPredictedSales()).isEqualTo(100.0);
        assertThat(result.get(0).getConfidenceLow()).isEqualTo(85.0);
        assertThat(result.get(0).getConfidenceHigh()).isEqualTo(115.0);
    }
}
