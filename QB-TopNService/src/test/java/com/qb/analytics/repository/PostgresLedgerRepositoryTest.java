package com.qb.analytics.repository;

import com.qb.analytics.model.TransactionEvent;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Basic tests: idempotent upsert, snapshot is a copy. */
class PostgresLedgerRepositoryTest {

    private final PostgresLedgerRepository postgres = new PostgresLedgerRepository();

    @Test
    void upsertIdempotent_firstInsert_returnsTrue() {
        TransactionEvent e = new TransactionEvent("tx1", "m1", "cat1", 100.0, "2026-02-17T00:00:00Z");
        boolean inserted = postgres.upsertIdempotent("tenant1", e);
        assertThat(inserted).isTrue();
    }

    @Test
    void upsertIdempotent_duplicateSameTenantAndTxnId_returnsFalse() {
        TransactionEvent e = new TransactionEvent("tx2", "m1", "cat1", 100.0, "2026-02-17T00:00:00Z");
        postgres.upsertIdempotent("tenant1", e);
        boolean second = postgres.upsertIdempotent("tenant1", e);
        assertThat(second).isFalse();
    }

    @Test
    void snapshot_returnsCopyIndependentOfLedger() {
        TransactionEvent e = new TransactionEvent("tx3", "m1", "cat1", 50.0, "2026-02-17T00:00:00Z");
        postgres.upsertIdempotent("tenant1", e);
        Map<String, TransactionEvent> snap = postgres.snapshot();
        assertThat(snap).containsKey("tenant1:tx3");
        snap.clear();
        assertThat(postgres.snapshot()).containsKey("tenant1:tx3");
    }
}
