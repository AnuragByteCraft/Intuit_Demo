package com.qb.analytics.service.forecast;

import com.qb.analytics.model.ForecastPoint;
import com.qb.analytics.repository.CassandraServingRepository;
import com.qb.analytics.repository.RedisCacheRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ForecastWriteServiceTest {

    private CassandraServingRepository cassandra;
    private RedisCacheRepository redis;
    private ForecastWriteService forecastWriteService;

    @BeforeEach
    void setUp() {
        cassandra = new CassandraServingRepository();
        redis = new RedisCacheRepository();
        forecastWriteService = new ForecastWriteService(cassandra, redis, new ObjectMapper());
    }

    @Test
    void cacheKey_returnsExpectedFormat() {
        String key = ForecastWriteService.cacheKey("t1", "m1", "cat1", 7);
        assertThat(key).isEqualTo("forecast:t1:m1:cat1:7");
    }

    @Test
    void write_persistsToCassandraAndCache() {
        List<ForecastPoint> points = List.of(
                new ForecastPoint("2026-02-19", 100.0, 80.0, 120.0),
                new ForecastPoint("2026-02-20", 100.0, 80.0, 120.0)
        );
        forecastWriteService.write("tenant1", "merchant1", "electronics", 7, points);

        assertThat(cassandra.fetchForecast("tenant1", "merchant1", "electronics", 7)).hasSize(2);
        String cached = redis.get(ForecastWriteService.cacheKey("tenant1", "merchant1", "electronics", 7));
        assertThat(cached).isNotBlank().contains("tenant1").contains("merchant1").contains("electronics");
    }

    @Test
    void write_emptyPoints_doesNothing() {
        forecastWriteService.write("tenant1", "m1", "cat1", 7, List.of());
        assertThat(cassandra.fetchForecast("tenant1", "m1", "cat1", 7)).isNull();
    }
}
