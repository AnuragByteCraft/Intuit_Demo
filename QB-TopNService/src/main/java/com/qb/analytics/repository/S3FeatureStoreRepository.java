package com.qb.analytics.repository;

import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** S3 feature store simulation: feature blobs keyed by (tenant, merchant, category). */
@Repository
public class S3FeatureStoreRepository {

    private final Map<String, String> featureBlobs = new ConcurrentHashMap<>();

    private String key(String tenantId, String merchantId, String categoryId) {
        return tenantId + ":" + merchantId + ":" + categoryId;
    }

    private static final String HISTORY_SUFFIX = ":history";

    public void putFeatures(String tenantId, String merchantId, String categoryId, String jsonBlob) {
        featureBlobs.put(key(tenantId, merchantId, categoryId), jsonBlob);
    }

    public String getFeatures(String tenantId, String merchantId, String categoryId) {
        return featureBlobs.get(key(tenantId, merchantId, categoryId));
    }

    /** Prophet history JSON stored when building features. */
    public void putHistory(String tenantId, String merchantId, String categoryId, String historyJson) {
        featureBlobs.put(key(tenantId, merchantId, categoryId) + HISTORY_SUFFIX, historyJson);
    }

    public String getHistory(String tenantId, String merchantId, String categoryId) {
        return featureBlobs.get(key(tenantId, merchantId, categoryId) + HISTORY_SUFFIX);
    }
}
