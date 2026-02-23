package com.qb.analytics.repository;

import com.qb.analytics.model.TransactionEvent;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Ledger simulation: primary key (tenantId, transactionId), idempotent insert. */
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

/** Snapshot returns shallow copy so aggregation has point-in-time view. */
    public Map<String, TransactionEvent> snapshot() {
        return new HashMap<>(ledger);
    }
}
