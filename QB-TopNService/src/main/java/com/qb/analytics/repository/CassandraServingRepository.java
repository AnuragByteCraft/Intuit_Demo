package com.qb.analytics.repository;

import com.qb.analytics.model.AggregateRecord;
import com.qb.analytics.model.ForecastPoint;
import com.qb.analytics.model.TopNResponse;
import org.springframework.stereotype.Repository;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class CassandraServingRepository {

    private final Map<String, AggregateRecord> dailyAgg = new ConcurrentHashMap<>();
    private final Map<String, TopNResponse> topnStore = new ConcurrentHashMap<>();
    private final Map<String, List<ForecastPoint>> forecastStore = new ConcurrentHashMap<>();

    private String dailyKey(String tenant, String merchant, String day, String category) {
        return tenant + ":" + merchant + ":" + day + ":" + category;
    }

    private String topnKey(String tenant, String merchant, String timeframe, String bucketStart, String metric, int n) {
        return tenant + ":" + merchant + ":" + timeframe + ":" + bucketStart + ":" + metric + ":" + n;
    }

    private String forecastKey(String tenant, String merchant, String category, int horizonDays) {
        return tenant + ":" + merchant + ":" + category + ":" + horizonDays;
    }

    public void upsertDailyAggregate(AggregateRecord r) {
        String k = dailyKey(r.getTenantId(), r.getMerchantId(), r.getDay(), r.getCategoryId());
        dailyAgg.merge(k, r, (oldV, newV) -> {
            oldV.setRevenue(oldV.getRevenue() + newV.getRevenue());
            oldV.setUnits(oldV.getUnits() + newV.getUnits());
            return oldV;
        });
    }

    public void batchMergeDailyAggregates(Collection<AggregateRecord> records) {
        for (AggregateRecord r : records) {
            upsertDailyAggregate(r);
        }
    }

    public List<AggregateRecord> fetchDailyAggregates(String tenantId, String merchantId, String startDayInclusive, String endDayInclusive) {
        List<AggregateRecord> out = new ArrayList<>();
        for (AggregateRecord r : dailyAgg.values()) {
            if (!tenantId.equals(r.getTenantId())) continue;
            if (!merchantId.equals(r.getMerchantId())) continue;

            if (startDayInclusive != null && r.getDay().compareTo(startDayInclusive) < 0) continue;
            if (endDayInclusive != null && r.getDay().compareTo(endDayInclusive) > 0) continue;

            out.add(r);
        }
        return out;
    }

    public void saveMaterializedTopN(TopNResponse resp) {
        String bucket = resp.getBucketStart() != null ? resp.getBucketStart() : "";
        topnStore.put(topnKey(resp.getTenantId(), resp.getMerchantId(), resp.getTimeframe(), bucket, resp.getMetric(), resp.getN()), resp);
    }

    public TopNResponse fetchMaterializedTopN(String tenantId, String merchantId, String timeframe, String bucketStart, String metric, int n) {
        return topnStore.get(topnKey(tenantId, merchantId, timeframe, bucketStart != null ? bucketStart : "", metric, n));
    }

    public void saveForecast(String tenantId, String merchantId, String categoryId, int horizonDays, List<ForecastPoint> points) {
        forecastStore.put(forecastKey(tenantId, merchantId, categoryId, horizonDays), points);
    }

    public List<ForecastPoint> fetchForecast(String tenantId, String merchantId, String categoryId, int horizonDays) {
        return forecastStore.get(forecastKey(tenantId, merchantId, categoryId, horizonDays));
    }

    public Set<String> listMerchants(String tenantId) {
        Set<String> merchants = new HashSet<>();
        for (AggregateRecord r : dailyAgg.values()) {
            if (tenantId.equals(r.getTenantId())) merchants.add(r.getMerchantId());
        }
        return merchants;
    }

    public Set<String> listCategoriesForMerchant(String tenantId, String merchantId) {
        Set<String> cats = new HashSet<>();
        for (AggregateRecord r : dailyAgg.values()) {
            if (tenantId.equals(r.getTenantId()) && merchantId.equals(r.getMerchantId())) {
                cats.add(r.getCategoryId());
            }
        }
        return cats;
    }

    public String getLatestAggregateDay(String tenantId, String merchantId) {
        String latest = null;
        for (AggregateRecord r : dailyAgg.values()) {
            if (!tenantId.equals(r.getTenantId()) || !merchantId.equals(r.getMerchantId())) continue;
            String day = r.getDay();
            if (latest == null || day.compareTo(latest) > 0) latest = day;
        }
        return latest;
    }

    public Set<String> listTenantIds() {
        Set<String> tenants = new HashSet<>();
        for (AggregateRecord r : dailyAgg.values()) {
            if (r.getTenantId() != null && !r.getTenantId().isBlank()) {
                tenants.add(r.getTenantId());
            }
        }
        return tenants;
    }
}
