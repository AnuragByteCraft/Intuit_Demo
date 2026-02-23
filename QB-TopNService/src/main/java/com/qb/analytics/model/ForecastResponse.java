package com.qb.analytics.model;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

public class ForecastResponse {
    public static final String MODEL_USED_BASELINE = "MOVING_AVERAGE_BASELINE";
    public static final String MODEL_USED_PROPHET = "PROPHET";
    private static final String MODEL_USED = MODEL_USED_BASELINE;

    private String tenantId;
    private String merchantId;
    private String categoryId;
    private int horizonDays;
    private String modelUsed;
    private String generatedAt;
    private List<ForecastPoint> points = new ArrayList<>();

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String merchantId) { this.merchantId = merchantId; }

    public String getCategoryId() { return categoryId; }
    public void setCategoryId(String categoryId) { this.categoryId = categoryId; }

    public int getHorizonDays() { return horizonDays; }
    public void setHorizonDays(int horizonDays) { this.horizonDays = horizonDays; }

    public String getModelUsed() { return modelUsed; }
    public void setModelUsed(String modelUsed) { this.modelUsed = modelUsed; }

    public String getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(String generatedAt) { this.generatedAt = generatedAt; }

    public List<ForecastPoint> getPoints() { return points; }
    public void setPoints(List<ForecastPoint> points) { this.points = points; }

    /** Alias for UI: same as points so normalizeArray(payload.data) works. */
    public List<ForecastPoint> getData() { return points; }

    /** Single place to build a forecast response (used by read path and inference write/cache). */
    public static ForecastResponse of(String tenantId, String merchantId, String categoryId, int horizonDays, List<ForecastPoint> points) {
        return of(tenantId, merchantId, categoryId, horizonDays, points, MODEL_USED);
    }

    /** Overload with explicit modelUsed (e.g. PROPHET). */
    public static ForecastResponse of(String tenantId, String merchantId, String categoryId, int horizonDays, List<ForecastPoint> points, String modelUsed) {
        ForecastResponse r = new ForecastResponse();
        r.setTenantId(tenantId);
        r.setMerchantId(merchantId);
        r.setCategoryId(categoryId);
        r.setHorizonDays(horizonDays);
        r.setModelUsed(modelUsed != null ? modelUsed : MODEL_USED);
        r.setGeneratedAt(OffsetDateTime.now().toString());
        if (points != null) r.setPoints(points);
        return r;
    }
}
