package com.qb.analytics.service.forecast;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qb.analytics.model.AggregateRecord;
import com.qb.analytics.repository.CassandraServingRepository;
import com.qb.analytics.repository.S3FeatureStoreRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

/**
 * FeatureExtractionService:
 *
 * "Features" in simple words:
 * - small numbers we compute from history that help the model predict the future
 * Examples:
 * - avgSales: average daily sales in last 90 days
 * - last7Avg: average of last 7 days
 * - volatility: how much sales fluctuate (stable vs noisy)
 *
 * We store computed features in S3 Feature Store (simulated).
 */
@Service
public class FeatureExtractionService {

    private static final Logger log = LoggerFactory.getLogger(FeatureExtractionService.class);
    private final CassandraServingRepository cassandra;
    private final S3FeatureStoreRepository s3;
    private final ObjectMapper objectMapper;

    public FeatureExtractionService(CassandraServingRepository cassandra, S3FeatureStoreRepository s3, ObjectMapper objectMapper) {
        this.cassandra = cassandra;
        this.s3 = s3;
        this.objectMapper = objectMapper;
    }

    public void buildAndStoreFeatures(String tenantId, String merchantId, String categoryId, int historyDays) {

        String end = LocalDate.now().toString();
        String start = LocalDate.now().minusDays(historyDays).toString();

        List<AggregateRecord> rows = cassandra.fetchDailyAggregates(tenantId, merchantId, start, end);

        // filter to category
        List<Double> series = new ArrayList<>();
        for (AggregateRecord r : rows) {
            if (categoryId.equals(r.getCategoryId())) {
                series.add(r.getRevenue());
            }
        }

        double avg = average(series);
        double last7 = average(lastN(series, 7));
        double vol = stddev(series);

        Map<String, Object> features = new LinkedHashMap<>();
        features.put("avgSales", avg);
        features.put("last7Avg", last7);
        features.put("volatility", vol);
        features.put("points", series.size());
        String blob;
        try {
            blob = objectMapper.writeValueAsString(features);
        } catch (JsonProcessingException e) {
            log.warn("FeatureExtraction failed to serialize features", e);
            return;
        }
        s3.putFeatures(tenantId, merchantId, categoryId, blob);
        log.info("FeatureExtraction built and stored tenantId={} merchantId={} categoryId={} dataPoints={} avgSales={} last7Avg={}", tenantId, merchantId, categoryId, series.size(), avg, last7);
    }

    private static List<Double> lastN(List<Double> series, int n) {
        if (series == null || series.isEmpty()) return Collections.emptyList();
        int start = Math.max(0, series.size() - n);
        return series.subList(start, series.size());
    }

    private static double average(List<Double> v) {
        if (v == null || v.isEmpty()) return 0;
        double s = 0;
        for (double x : v) s += x;
        return s / v.size();
    }

    private static double stddev(List<Double> v) {
        if (v == null || v.size() < 2) return 0;
        double mean = average(v);
        double sum = 0;
        for (double x : v) {
            double d = x - mean;
            sum += d * d;
        }
        return Math.sqrt(sum / (v.size() - 1));
    }
}
