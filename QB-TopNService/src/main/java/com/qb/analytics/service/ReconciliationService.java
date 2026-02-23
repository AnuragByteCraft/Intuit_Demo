package com.qb.analytics.service;

import com.qb.analytics.repository.CassandraServingRepository;
import com.qb.analytics.repository.PostgresLedgerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/** Sync/check Postgres ledger vs Cassandra aggregates on a schedule; logs for demo. */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
    private final PostgresLedgerRepository postgres;
    private final CassandraServingRepository cassandra;

    public ReconciliationService(PostgresLedgerRepository postgres, CassandraServingRepository cassandra) {
        this.postgres = postgres;
        this.cassandra = cassandra;
    }

    @Scheduled(fixedDelayString = "${demo.schedules.reconciliationDelayMs:60000}")
    public void reconcile() {
        String day = LocalDate.now().minusDays(1).toString();
        log.info("Reconciliation ran for day={} (demo)", day);
    }
}
