package com.qb.analytics.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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

    @JsonIgnore
    public List<ForecastPoint> getData() { return points; }

    public static ForecastResponse of(String tenantId, String merchantId, String categoryId, int horizonDays, List<ForecastPoint> points) {
        return of(tenantId, merchantId, categoryId, horizonDays, points, MODEL_USED);
    }

    public static ForecastResponse of(String tenantId, String merchantId, String categoryId, int horizonDays, List<ForecastPoint> points, String modelUsed) {
        ForecastResponse r = new ForecastResponse();
        r.setTenantId(tenantId);
        r.setMerchantId(merchantId);
        r.setCategoryId(categoryId);
        r.setHorizonDays(horizonDays);
        r.setModelUsed(modelUsed != null ? modelUsed : MODEL_USED);
        r.setGeneratedAt(OffsetDateTime.now().toString());
        if (points != null) {
            Set<String> seen = new LinkedHashSet<>();
            List<ForecastPoint> unique = new ArrayList<>();
            for (ForecastPoint p : points) {
                if (p != null && p.getDate() != null && seen.add(p.getDate())) unique.add(p);
            }
            r.setPoints(unique);
        }
        return r;
    }
}
