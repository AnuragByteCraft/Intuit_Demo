package com.qb.analytics.repository;

import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal S3 Feature Store simulation:
 * - Stores "feature blobs" keyed by (tenant, merchant, category)
 * - In reality: Parquet files partitioned by tenant/merchant/date
 */
@Repository
public class S3FeatureStoreRepository {

    private final Map<String, String> featureBlobs = new ConcurrentHashMap<>();

    private String key(String tenantId, String merchantId, String categoryId) {
        return tenantId + ":" + merchantId + ":" + categoryId;
    }

    public void putFeatures(String tenantId, String merchantId, String categoryId, String jsonBlob) {
        featureBlobs.put(key(tenantId, merchantId, categoryId), jsonBlob);
    }

    public String getFeatures(String tenantId, String merchantId, String categoryId) {
        return featureBlobs.get(key(tenantId, merchantId, categoryId));
    }
}
