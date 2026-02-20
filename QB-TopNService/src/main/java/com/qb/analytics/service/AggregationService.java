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

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * AggregationService:
 * - scheduled micro-batch job
 * - reads ledger snapshot (simulates consuming from Kafka into OLAP)
 * - writes daily aggregates into Cassandra
 * - materializes Top-N into Cassandra + hot cache
 *
 * In real system: this would be Kafka consumer group + stateful aggregation.
 * For demo: we keep it simple and deterministic.
 */
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
        int processed = 0;

        // Build daily aggregates from ledger (idempotent because we only add deltas if we tracked offsets; demo rebuilds)
        // For demo simplicity: clear & rebuild is avoided because it would require clear operations; instead we do additive merge
        // using a "seen" key to ensure we don't double-count.
        // We'll store a marker in Redis: aggSeen:<tenant>:<txnId>
        for (Map.Entry<String, com.qb.analytics.model.TransactionEvent> entry : snapshot.entrySet()) {

            String tenantAndTxn = entry.getKey(); // tenant:txnId
            String[] parts = tenantAndTxn.split(":", 2);
            if (parts.length < 2) continue;

            String tenantId = parts[0];
            String txnId = parts[1];

            String seenKey = "aggSeen:" + tenantId + ":" + txnId;
            if (redis.get(seenKey) != null) continue;

            com.qb.analytics.model.TransactionEvent e = entry.getValue();

            String day = toDay(e.getEventTime());

            // 1 unit per transaction for demo
            AggregateRecord ar = new AggregateRecord(tenantId, e.getMerchantId(), e.getCategoryId(), day, e.getAmount(), 1L);
            cassandra.upsertDailyAggregate(ar);

            // mark as aggregated
            redis.put(seenKey, "1", 7 * 24 * 3600);

            // refresh materialized weekly topN for defaultN (keeps it simple)
            TopNResponse weekly = computeTopNFromDaily(tenantId, e.getMerchantId(), "WEEKLY", "REVENUE", defaultTopN, null, null);
            cassandra.saveMaterializedTopN(weekly);

            // also cache it
            String cacheKey = cacheKey(tenantId, e.getMerchantId(), "WEEKLY", "REVENUE", defaultTopN, null, null);
            redis.put(cacheKey, toJson(weekly), 60); // 60s TTL for demo

            log.info("Aggregation updated daily aggregate + materialized weekly topN={} tenantId={} merchantId={} categoryId={}", defaultTopN, tenantId, e.getMerchantId(), e.getCategoryId());
            processed++;
        }
        if (processed == 0) {
            log.info("Aggregation run finished no new transactions to aggregate");
        }
    }

    public TopNResponse getMaterializedTopN(String tenantId, String merchantId, String timeframe, String metric, int n) {
        if (n <= 0) n = defaultTopN;
        if (n > maxTopN) n = maxTopN;

        String cacheKey = cacheKey(tenantId, merchantId, timeframe, metric, n, null, null);
        String cached = redis.get(cacheKey);
        if (cached != null) {
            TopNResponse parsed = fromJsonTopN(cached);
            if (parsed != null) {
                log.info("Aggregation getMaterializedTopN source=cache tenantId={} merchantId={} timeframe={}", tenantId, merchantId, timeframe);
                return parsed;
            }
        }
        TopNResponse stored = cassandra.fetchMaterializedTopN(tenantId, merchantId, timeframe, metric, n);
        if (stored != null) {
            redis.put(cacheKey, toJson(stored), 60);
            log.info("Aggregation getMaterializedTopN source=cassandra tenantId={} merchantId={} timeframe={}", tenantId, merchantId, timeframe);
            return stored;
        }
        TopNResponse computed = computeTopNFromDaily(tenantId, merchantId, timeframe, metric, n, null, null);
        cassandra.saveMaterializedTopN(computed);
        redis.put(cacheKey, toJson(computed), 60);
        log.info("Aggregation getMaterializedTopN source=computed tenantId={} merchantId={} timeframe={}", tenantId, merchantId, timeframe);
        return computed;
    }

    public TopNResponse getTopNForCustomRange(String tenantId, String merchantId, String metric, int n, String startDay, String endDay) {

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

        Map<String, Double> revenueByCategory = new HashMap<>();
        for (AggregateRecord r : daily) {
            revenueByCategory.merge(r.getCategoryId(), r.getRevenue(), Double::sum);
        }

        List<Map.Entry<String, Double>> sorted = new ArrayList<>(revenueByCategory.entrySet());
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

    private static String toDay(String isoTime) {
        // isoTime expected like 2026-02-17T00:00:00Z
        OffsetDateTime odt = OffsetDateTime.parse(isoTime);
        LocalDate d = odt.toLocalDate();
        return d.format(DateTimeFormatter.ISO_LOCAL_DATE);
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
