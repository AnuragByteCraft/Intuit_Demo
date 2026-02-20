package com.qb.analytics.service.forecast;

/**
 * Shared key format for forecast cache (read path and inference write must use the same key).
 */
final class ForecastKeys {
    private static final String PREFIX = "forecast:";

    static String cacheKey(String tenantId, String merchantId, String categoryId, int horizonDays) {
        return PREFIX + tenantId + ":" + merchantId + ":" + categoryId + ":" + horizonDays;
    }

    private ForecastKeys() {}
}
