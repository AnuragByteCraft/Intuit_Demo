package com.qb.analytics.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public class TransactionEvent {

    @NotBlank
    private String transactionId;

    /** Set from X-Merchant-Id header in controller; not required in body. */
    private String merchantId;

    @NotBlank
    private String categoryId;

    @NotNull
    @Positive
    private Double amount;

    // ISO-8601 string to keep demo simple
    @NotBlank
    private String eventTime;

    public TransactionEvent() {}

    public TransactionEvent(String transactionId, String merchantId, String categoryId, Double amount, String eventTime) {
        this.transactionId = transactionId;
        this.merchantId = merchantId;
        this.categoryId = categoryId;
        this.amount = amount;
        this.eventTime = eventTime;
    }

    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }

    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String merchantId) { this.merchantId = merchantId; }

    public String getCategoryId() { return categoryId; }
    public void setCategoryId(String categoryId) { this.categoryId = categoryId; }

    public Double getAmount() { return amount; }
    public void setAmount(Double amount) { this.amount = amount; }

    public String getEventTime() { return eventTime; }
    public void setEventTime(String eventTime) { this.eventTime = eventTime; }
}
