package com.qb.analytics.model;

import java.util.ArrayList;
import java.util.List;

public class TopNResponse {

    public static class Item {
        private int rank;
        private String categoryId;
        private double value;

        public Item() {}

        public Item(int rank, String categoryId, double value) {
            this.rank = rank;
            this.categoryId = categoryId;
            this.value = value;
        }

        public int getRank() { return rank; }
        public void setRank(int rank) { this.rank = rank; }

        public String getCategoryId() { return categoryId; }
        public void setCategoryId(String categoryId) { this.categoryId = categoryId; }

        public double getValue() { return value; }
        public void setValue(double value) { this.value = value; }
    }

    private String tenantId;
    private String merchantId;
    private String timeframe;
    /** For materialized views (WEEKLY/MONTHLY/YEARLY): start day of the bucket (yyyy-MM-dd). */
    private String bucketStart;
    private String metric;
    private int n;
    private String generatedAt;
    private List<Item> items = new ArrayList<>();

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String merchantId) { this.merchantId = merchantId; }

    public String getTimeframe() { return timeframe; }
    public void setTimeframe(String timeframe) { this.timeframe = timeframe; }

    public String getBucketStart() { return bucketStart; }
    public void setBucketStart(String bucketStart) { this.bucketStart = bucketStart; }

    public String getMetric() { return metric; }
    public void setMetric(String metric) { this.metric = metric; }

    public int getN() { return n; }
    public void setN(int n) { this.n = n; }

    public String getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(String generatedAt) { this.generatedAt = generatedAt; }

    public List<Item> getItems() { return items; }
    public void setItems(List<Item> items) { this.items = items; }
}
