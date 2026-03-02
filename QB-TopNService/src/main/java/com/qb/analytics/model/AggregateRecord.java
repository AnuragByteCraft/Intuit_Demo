package com.qb.analytics.model;

public class AggregateRecord {
    private String tenantId;
    private String merchantId;
    private String categoryId;
    private String day;
    private double revenue;
    private long units;

    public AggregateRecord() {}

    public AggregateRecord(String tenantId, String merchantId, String categoryId, String day, double revenue, long units) {
        this.tenantId = tenantId;
        this.merchantId = merchantId;
        this.categoryId = categoryId;
        this.day = day;
        this.revenue = revenue;
        this.units = units;
    }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String merchantId) { this.merchantId = merchantId; }

    public String getCategoryId() { return categoryId; }
    public void setCategoryId(String categoryId) { this.categoryId = categoryId; }

    public String getDay() { return day; }
    public void setDay(String day) { this.day = day; }

    public double getRevenue() { return revenue; }
    public void setRevenue(double revenue) { this.revenue = revenue; }

    public long getUnits() { return units; }
    public void setUnits(long units) { this.units = units; }
}
