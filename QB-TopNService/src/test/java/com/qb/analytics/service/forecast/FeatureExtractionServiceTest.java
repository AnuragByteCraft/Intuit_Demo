package com.qb.analytics.service.forecast;

import com.qb.analytics.model.AggregateRecord;
import com.qb.analytics.repository.CassandraServingRepository;
import com.qb.analytics.repository.S3FeatureStoreRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Basic tests: getHistoryJson returns from S3 when present; buildAndStoreFeatures stores history.
 */
class FeatureExtractionServiceTest {

    private CassandraServingRepository cassandra;
    private S3FeatureStoreRepository s3;
    private FeatureExtractionService service;

    @BeforeEach
    void setUp() {
        cassandra = new CassandraServingRepository();
        s3 = new S3FeatureStoreRepository();
        service = new FeatureExtractionService(cassandra, s3, new ObjectMapper());
    }

    @Test
    void getHistoryJson_whenStoredInS3_returnsCachedHistory() {
        String historyJson = "[{\"date\":\"2026-02-17\",\"value\":100.0},{\"date\":\"2026-02-18\",\"value\":120.0}]";
        s3.putHistory("t1", "m1", "cat1", historyJson);
        String result = service.getHistoryJson("t1", "m1", "cat1", 90);
        assertThat(result).isEqualTo(historyJson);
    }

    @Test
    void getHistoryJson_whenNotInS3_fetchesFromCassandraAndReturnsJson() {
        String day1 = java.time.LocalDate.now().minusDays(2).toString();
        String day2 = java.time.LocalDate.now().minusDays(1).toString();
        AggregateRecord r1 = new AggregateRecord("t1", "m1", "cat1", day1, 50.0, 1L);
        AggregateRecord r2 = new AggregateRecord("t1", "m1", "cat1", day2, 60.0, 1L);
        cassandra.batchMergeDailyAggregates(List.of(r1, r2));
        String result = service.getHistoryJson("t1", "m1", "cat1", 90);
        assertThat(result).contains(day1).contains(day2).contains("50").contains("60");
    }

    @Test
    void buildAndStoreFeatures_storesHistoryInS3SoGetHistoryJsonCanReadIt() {
        String today = java.time.LocalDate.now().toString();
        String yesterday = java.time.LocalDate.now().minusDays(1).toString();
        AggregateRecord r1 = new AggregateRecord("t1", "m1", "cat1", yesterday, 100.0, 1L);
        AggregateRecord r2 = new AggregateRecord("t1", "m1", "cat1", today, 110.0, 1L);
        cassandra.batchMergeDailyAggregates(List.of(r1, r2));
        service.buildAndStoreFeatures("t1", "m1", "cat1", 90);
        String history = service.getHistoryJson("t1", "m1", "cat1", 90);
        assertThat(history).contains(yesterday).contains(today).contains("100").contains("110");
    }
}
