package com.qb.analytics.repository;

import com.qb.analytics.model.TransactionEvent;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PostgresLedgerRepositoryTest {

    private final PostgresLedgerRepository postgres = new PostgresLedgerRepository();

    @Test
    void upsertIdempotent_firstInsert_returnsTrue() {
        TransactionEvent e = new TransactionEvent("tx1", "m1", "cat1", 100.0, "2026-02-17T00:00:00Z");
        boolean inserted = postgres.upsertIdempotent("tenant1", e);
        assertThat(inserted).isTrue();
    }

    @Test
    void upsertIdempotent_duplicateSameTenantMerchantAndTxnId_returnsFalse() {
        TransactionEvent e = new TransactionEvent("tx2", "m1", "cat1", 100.0, "2026-02-17T00:00:00Z");
        postgres.upsertIdempotent("tenant1", e);
        boolean second = postgres.upsertIdempotent("tenant1", e);
        assertThat(second).isFalse();
    }

    @Test
    void upsertIdempotent_sameTxnIdDifferentMerchant_bothInserted() {
        TransactionEvent e1 = new TransactionEvent("tx-same", "m1", "cat1", 100.0, "2026-02-17T00:00:00Z");
        TransactionEvent e2 = new TransactionEvent("tx-same", "m2", "cat1", 200.0, "2026-02-17T00:00:00Z");
        boolean first = postgres.upsertIdempotent("tenant1", e1);
        boolean second = postgres.upsertIdempotent("tenant1", e2);
        assertThat(first).isTrue();
        assertThat(second).isTrue();
        assertThat(postgres.snapshot()).hasSize(2);
    }

    @Test
    void snapshot_returnsCopyIndependentOfLedger() {
        TransactionEvent e = new TransactionEvent("tx3", "m1", "cat1", 50.0, "2026-02-17T00:00:00Z");
        postgres.upsertIdempotent("tenant1", e);
        Map<String, TransactionEvent> snap = postgres.snapshot();
        assertThat(snap).containsKey("tenant1:m1:tx3");
        snap.clear();
        assertThat(postgres.snapshot()).containsKey("tenant1:m1:tx3");
    }
}
