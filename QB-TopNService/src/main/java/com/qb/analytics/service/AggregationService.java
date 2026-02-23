package com.qb.analytics.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qb.analytics.model.AggregateRecord;
import com.qb.analytics.model.TopNResponse;
import com.qb.analytics.repository.CassandraServingRepository;
import com.qb.analytics.repository.PostgresLedgerRepository;
import com.qb.analytics.repository.RedisCacheRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/** Scheduled micro-batch: ledger snapshot → daily aggregates → materialized Top-N (Cassandra + cache). */
@Service
public class AggregationService {

    private static final Logger log = LoggerFactory.getLogger(AggregationService.class);
    private final PostgresLedgerRepository postgres;
    private final CassandraServingRepository cassandra;
    private final RedisCacheRepository redis;
    private final ObjectMapper objectMapper;

    @Value("${demo.topn.defaultN:10}")
    private int defaultTopN;

    @Value("${demo.topn.maxN:50}")
    private int maxTopN;

    public AggregationService(PostgresLedgerRepository postgres,
                              CassandraServingRepository cassandra,
                              RedisCacheRepository redis,
                              ObjectMapper objectMapper) {
        this.postgres = postgres;
        this.cassandra = cassandra;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${demo.schedules.aggregationDelayMs:15000}") // configurable for demo
    public void runAggregation() {
        Map<String, com.qb.analytics.model.TransactionEvent> snapshot = postgres.snapshot();
        log.info("Aggregation run started ledgerSnapshotSize={}", snapshot.size());

        // Phase 1: aggregate in memory by (tenant, merchant, day, category), then batch write (fewer DB writes than per-txn).
        Set<String> affectedTenantMerchant = new HashSet<>();
        Map<String, AggregateRecord> deltas = new HashMap<>();
        for (Map.Entry<String, com.qb.analytics.model.TransactionEvent> entry : snapshot.entrySet()) {
            String tenantAndTxn = entry.getKey();
            String[] parts = tenantAndTxn.split(":", 2);
            if (parts.length < 2) continue;

            String tenantId = parts[0];
            String txnId = parts[1];
            String seenKey = "aggSeen:" + tenantId + ":" + txnId;
            if (!redis.putIfAbsent(seenKey, "1", 7 * 24 * 3600)) continue;

            com.qb.analytics.model.TransactionEvent e = entry.getValue();
            String day = toDay(e.getEventTime());
            String key = dailyAggKey(tenantId, e.getMerchantId(), day, e.getCategoryId());
            AggregateRecord ar = new AggregateRecord(tenantId, e.getMerchantId(), e.getCategoryId(), day, e.getAmount(), 1L);
            deltas.merge(key, ar, (old, newV) -> {
                old.setRevenue(old.getRevenue() + newV.getRevenue());
                old.setUnits(old.getUnits() + newV.getUnits());
                return old;
            });
            affectedTenantMerchant.add(tenantId + ":" + e.getMerchantId());
        }
        if (!deltas.isEmpty()) {
            cassandra.batchMergeDailyAggregates(deltas.values());
        }

        // Phase 2: compute materialized Top-N once per affected (tenant, merchant) for current DAILY, WEEKLY, MONTHLY, YEARLY.
        LocalDate asOf = LocalDate.now();
        for (String tenantMerchant : affectedTenantMerchant) {
            String[] tm = tenantMerchant.split(":", 2);
            if (tm.length < 2) continue;
            String tenantId = tm[0];
            String merchantId = tm[1];
            for (String timeframe : List.of("DAILY", "WEEKLY", "MONTHLY", "YEARLY")) {
                BucketRange range = bucketRange(timeframe, asOf);
                TopNResponse topN = computeTopNFromDaily(tenantId, merchantId, timeframe, "REVENUE", defaultTopN, range.startDay, range.endDay);
                topN.setBucketStart(range.bucketStart);
                cassandra.saveMaterializedTopN(topN);
                String cacheKey = materializedCacheKey(tenantId, merchantId, timeframe, range.bucketStart, "REVENUE", defaultTopN);
                redis.put(cacheKey, toJson(topN), 60);
            }
            log.info("Aggregation materialized Top-N for tenantId={} merchantId={} (DAILY/WEEKLY/MONTHLY/YEARLY)", tenantId, merchantId);
        }
        if (affectedTenantMerchant.isEmpty()) {
            log.info("Aggregation run finished no new transactions to aggregate");
        }
    }

    public TopNResponse getMaterializedTopN(String tenantId, String merchantId, String timeframe, String metric, int n) {
        if (n <= 0) n = defaultTopN;
        if (n > maxTopN) n = maxTopN;

        BucketRange range = bucketRange(timeframe, LocalDate.now());
        String cacheKey = materializedCacheKey(tenantId, merchantId, timeframe, range.bucketStart, metric, n);
        String cached = redis.get(cacheKey);
        if (cached != null) {
            TopNResponse parsed = fromJsonTopN(cached);
            if (parsed != null) {
                log.info("Aggregation getMaterializedTopN source=cache tenantId={} merchantId={} timeframe={} bucket={}", tenantId, merchantId, timeframe, range.bucketStart);
                return parsed;
            }
        }
        TopNResponse stored = cassandra.fetchMaterializedTopN(tenantId, merchantId, timeframe, range.bucketStart, metric, n);
        if (stored != null) {
            redis.put(cacheKey, toJson(stored), 60);
            log.info("Aggregation getMaterializedTopN source=cassandra tenantId={} merchantId={} timeframe={} bucket={}", tenantId, merchantId, timeframe, range.bucketStart);
            return stored;
        }
        TopNResponse computed = computeTopNFromDaily(tenantId, merchantId, timeframe, metric, n, range.startDay, range.endDay);
        computed.setBucketStart(range.bucketStart);
        cassandra.saveMaterializedTopN(computed);
        redis.put(cacheKey, toJson(computed), 60);
        log.info("Aggregation getMaterializedTopN source=computed tenantId={} merchantId={} timeframe={} bucket={}", tenantId, merchantId, timeframe, range.bucketStart);
        return computed;
    }

    private static final int MAX_CUSTOM_RANGE_DAYS = 90;

    public TopNResponse getTopNForCustomRange(String tenantId, String merchantId, String metric, int n, String startDay, String endDay) {

        validateCustomRange(startDay, endDay);

        if (n <= 0) n = defaultTopN;
        if (n > maxTopN) n = maxTopN;

        // For custom date range, we compute from daily aggregates (Cassandra) and cache result.
        String cacheKey = cacheKey(tenantId, merchantId, "CUSTOM", metric, n, startDay, endDay);
        String cached = redis.get(cacheKey);
        if (cached != null) {
            TopNResponse parsed = fromJsonTopN(cached);
            if (parsed != null) return parsed;
        }
        TopNResponse resp = computeTopNFromDaily(tenantId, merchantId, "CUSTOM", metric, n, startDay, endDay);
        redis.put(cacheKey, toJson(resp), 120); // 2 min TTL
        return resp;
    }

    private TopNResponse computeTopNFromDaily(String tenantId, String merchantId, String timeframe, String metric, int n, String startDay, String endDay) {

        List<AggregateRecord> daily = cassandra.fetchDailyAggregates(tenantId, merchantId, startDay, endDay);

        boolean useUnits = "UNITS".equalsIgnoreCase(metric);
        Map<String, Double> valueByCategory = new HashMap<>();
        for (AggregateRecord r : daily) {
            double value = useUnits ? (double) r.getUnits() : r.getRevenue();
            valueByCategory.merge(r.getCategoryId(), value, Double::sum);
        }

        List<Map.Entry<String, Double>> sorted = new ArrayList<>(valueByCategory.entrySet());
        sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        TopNResponse resp = new TopNResponse();
        resp.setTenantId(tenantId);
        resp.setMerchantId(merchantId);
        resp.setTimeframe(timeframe);
        resp.setMetric(metric);
        resp.setN(n);
        resp.setGeneratedAt(java.time.OffsetDateTime.now().toString());

        int rank = 1;
        for (Map.Entry<String, Double> e : sorted) {
            if (rank > n) break;
            resp.getItems().add(new TopNResponse.Item(rank, e.getKey(), e.getValue()));
            rank++;
        }
        return resp;
    }

    private static void validateCustomRange(String startDay, String endDay) {
        if (startDay == null || startDay.isBlank() || endDay == null || endDay.isBlank()) {
            throw new IllegalArgumentException("Custom range requires both start and end (yyyy-MM-dd)");
        }
        LocalDate start;
        LocalDate end;
        try {
            start = LocalDate.parse(startDay);
            end = LocalDate.parse(endDay);
        } catch (Exception e) {
            throw new IllegalArgumentException("start and end must be valid dates in yyyy-MM-dd format");
        }
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("start must be before or equal to end");
        }
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        if (days > MAX_CUSTOM_RANGE_DAYS) {
            throw new IllegalArgumentException("Custom range must not exceed " + MAX_CUSTOM_RANGE_DAYS + " days");
        }
    }

/** Resolves date range for materialized Top-N: DAILY (today), WEEKLY (Mon–Sun), MONTHLY, YEARLY. */
    public static BucketRange bucketRange(String timeframe, LocalDate asOf) {
        if (asOf == null) asOf = LocalDate.now();
        String bucketStart;
        String startDay;
        String endDay;
        if ("DAILY".equalsIgnoreCase(timeframe)) {
            bucketStart = asOf.format(DateTimeFormatter.ISO_LOCAL_DATE);
            startDay = bucketStart;
            endDay = bucketStart;
        } else if ("WEEKLY".equalsIgnoreCase(timeframe)) {
            LocalDate monday = asOf.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            LocalDate sunday = monday.plusDays(6);
            bucketStart = monday.format(DateTimeFormatter.ISO_LOCAL_DATE);
            startDay = bucketStart;
            endDay = sunday.format(DateTimeFormatter.ISO_LOCAL_DATE);
        } else if ("MONTHLY".equalsIgnoreCase(timeframe)) {
            LocalDate first = asOf.withDayOfMonth(1);
            LocalDate last = first.plusMonths(1).minusDays(1);
            bucketStart = first.format(DateTimeFormatter.ISO_LOCAL_DATE);
            startDay = bucketStart;
            endDay = last.format(DateTimeFormatter.ISO_LOCAL_DATE);
        } else if ("YEARLY".equalsIgnoreCase(timeframe)) {
            LocalDate first = asOf.withDayOfYear(1);
            LocalDate last = first.plusYears(1).minusDays(1);
            bucketStart = first.format(DateTimeFormatter.ISO_LOCAL_DATE);
            startDay = bucketStart;
            endDay = last.format(DateTimeFormatter.ISO_LOCAL_DATE);
        } else {
            throw new IllegalArgumentException("Unsupported materialized timeframe: " + timeframe + " (use DAILY, WEEKLY, MONTHLY, or YEARLY)");
        }
        return new BucketRange(bucketStart, startDay, endDay);
    }

    public static final class BucketRange {
        public final String bucketStart;
        public final String startDay;
        public final String endDay;

        public BucketRange(String bucketStart, String startDay, String endDay) {
            this.bucketStart = bucketStart;
            this.startDay = startDay;
            this.endDay = endDay;
        }
    }

    private static String dailyAggKey(String tenantId, String merchantId, String day, String categoryId) {
        return tenantId + ":" + merchantId + ":" + day + ":" + categoryId;
    }

    private static String toDay(String isoTime) {
        // isoTime expected like 2026-02-17T00:00:00Z
        OffsetDateTime odt = OffsetDateTime.parse(isoTime);
        LocalDate d = odt.toLocalDate();
        return d.format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    private static String materializedCacheKey(String tenantId, String merchantId, String timeframe, String bucketStart, String metric, int n) {
        return "topn:" + tenantId + ":" + merchantId + ":" + timeframe + ":" + bucketStart + ":" + metric + ":" + n;
    }

    private static String cacheKey(String tenantId, String merchantId, String timeframe, String metric, int n, String start, String end) {
        String k = "topn:" + tenantId + ":" + merchantId + ":" + timeframe + ":" + metric + ":" + n;
        if (start != null) k += ":" + start;
        if (end != null) k += ":" + end;
        return k;
    }

    private String toJson(TopNResponse r) {
        try {
            return objectMapper.writeValueAsString(r);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize TopNResponse", e);
            return "{}";
        }
    }

    private TopNResponse fromJsonTopN(String json) {
        try {
            return objectMapper.readValue(json, TopNResponse.class);
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse cached Top-N", e);
            return null;
        }
    }
}
