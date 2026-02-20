package com.qb.analytics.repository;

import com.qb.analytics.model.TransactionEvent;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal PostgreSQL Ledger simulation:
 * - Primary key = (tenantId, transactionId)
 * - Idempotent insert (ignore duplicates)
 */
@Repository
public class PostgresLedgerRepository {

    private final Map<String, TransactionEvent> ledger = new ConcurrentHashMap<>();

    private String key(String tenantId, String txnId) {
        return tenantId + ":" + txnId;
    }

    public boolean upsertIdempotent(String tenantId, TransactionEvent e) {
        String k = key(tenantId, e.getTransactionId());
        return ledger.putIfAbsent(k, e) == null; // true if inserted new
    }

    public Map<String, TransactionEvent> snapshot() {
        return ledger;
    }
}
