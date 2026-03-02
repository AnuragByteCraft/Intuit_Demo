package com.qb.analytics.repository;

import com.qb.analytics.model.TransactionEvent;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class PostgresLedgerRepository {

    private final Map<String, TransactionEvent> ledger = new ConcurrentHashMap<>();

    private String key(String tenantId, String merchantId, String txnId) {
        return tenantId + ":" + merchantId + ":" + txnId;
    }

    public boolean upsertIdempotent(String tenantId, TransactionEvent e) {
        String k = key(tenantId, e.getMerchantId(), e.getTransactionId());
        return ledger.putIfAbsent(k, e) == null;
    }

    public Map<String, TransactionEvent> snapshot() {
        return new HashMap<>(ledger);
    }
}
