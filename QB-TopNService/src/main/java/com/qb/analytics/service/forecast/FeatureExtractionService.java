package com.qb.analytics.service.forecast;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qb.analytics.config.DemoConfig;
import com.qb.analytics.model.AggregateRecord;
import com.qb.analytics.repository.CassandraServingRepository;
import com.qb.analytics.repository.S3FeatureStoreRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class FeatureExtractionService {

    private static final Logger log = LoggerFactory.getLogger(FeatureExtractionService.class);
    private final CassandraServingRepository cassandra;
    private final S3FeatureStoreRepository s3;
    private final ObjectMapper objectMapper;
    private final DemoConfig demoConfig;

    public FeatureExtractionService(CassandraServingRepository cassandra, S3FeatureStoreRepository s3, ObjectMapper objectMapper, DemoConfig demoConfig) {
        this.cassandra = cassandra;
        this.s3 = s3;
        this.objectMapper = objectMapper;
        this.demoConfig = demoConfig;
    }

    public void buildAndStoreFeatures(String tenantId, String merchantId, String categoryId, int historyDays) {
        LocalDate endDate = demoConfig.today();
        String latestDay = cassandra.getLatestAggregateDay(tenantId, merchantId);
        if (latestDay != null) {
            LocalDate latest = LocalDate.parse(latestDay);
            if (latest.isAfter(endDate)) endDate = latest;
        }
        String end = endDate.toString();
        String start = endDate.minusDays(historyDays).toString();

        List<AggregateRecord> rows = cassandra.fetchDailyAggregates(tenantId, merchantId, start, end);

        List<AggregateRecord> forCategory = new ArrayList<>();
        for (AggregateRecord r : rows) {
            if (categoryId.equals(r.getCategoryId())) {
                forCategory.add(r);
            }
        }
        forCategory.sort(Comparator.comparing(AggregateRecord::getDay));

        List<Double> series = new ArrayList<>();
        for (AggregateRecord r : forCategory) {
            series.add(r.getRevenue());
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
        String historyJson = toHistoryJson(forCategory);
        s3.putHistory(tenantId, merchantId, categoryId, historyJson);
        log.info("FeatureExtraction built and stored tenantId={} merchantId={} categoryId={} dataPoints={} avgSales={} last7Avg={}", tenantId, merchantId, categoryId, series.size(), avg, last7);
    }

    private String toHistoryJson(List<AggregateRecord> forCategory) {
        List<Map<String, Object>> history = forCategory.stream()
                .map(r -> Map.<String, Object>of("date", r.getDay(), "value", r.getRevenue()))
                .collect(Collectors.toList());
        try {
            return objectMapper.writeValueAsString(history);
        } catch (JsonProcessingException e) {
            log.warn("FeatureExtraction toHistoryJson failed", e);
            return "[]";
        }
    }

    public int getPointsCountFromStoredFeatures(String tenantId, String merchantId, String categoryId) {
        String featuresJson = s3.getFeatures(tenantId, merchantId, categoryId);
        if (featuresJson == null) return 0;
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(featuresJson);
            com.fasterxml.jackson.databind.JsonNode p = root.get("points");
            return p != null && p.isNumber() ? p.asInt() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public String getHistoryJson(String tenantId, String merchantId, String categoryId, int historyDays) {
        String cached = s3.getHistory(tenantId, merchantId, categoryId);
        if (cached != null && !cached.isEmpty()) return cached;

        LocalDate today = demoConfig.today();
        String end = today.toString();
        String start = today.minusDays(historyDays).toString();
        List<AggregateRecord> rows = cassandra.fetchDailyAggregates(tenantId, merchantId, start, end);
        List<AggregateRecord> forCategory = new ArrayList<>();
        for (AggregateRecord r : rows) {
            if (categoryId.equals(r.getCategoryId())) forCategory.add(r);
        }
        forCategory.sort(Comparator.comparing(AggregateRecord::getDay));
        return toHistoryJson(forCategory);
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
