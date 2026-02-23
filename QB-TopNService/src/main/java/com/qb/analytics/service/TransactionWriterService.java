package com.qb.analytics.service;

import com.qb.analytics.infra.InMemoryEventBus;
import com.qb.analytics.model.TransactionEvent;
import com.qb.analytics.repository.PostgresLedgerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.util.concurrent.TimeUnit;

/** Background consumer: writes events to ledger with idempotent upsert. */
@Service
public class TransactionWriterService {

    private static final Logger log = LoggerFactory.getLogger(TransactionWriterService.class);
    private static final int SHUTDOWN_JOIN_SECONDS = 10;

    private final InMemoryEventBus bus;
    private final PostgresLedgerRepository postgres;
    private volatile Thread consumerThread;

    public TransactionWriterService(InMemoryEventBus bus, PostgresLedgerRepository postgres) {
        this.bus = bus;
        this.postgres = postgres;
    }

    @PostConstruct
    public void start() {
        Thread t = new Thread(this::consumeLoop, "transaction-writer");
        t.setDaemon(false);
        this.consumerThread = t;
        t.start();
        log.info("TransactionWriter consumer thread started (polling event bus for ledger writes)");
    }

    @PreDestroy
    public void shutdown() {
        Thread t = consumerThread;
        if (t != null && t.isAlive()) {
            log.info("TransactionWriter shutting down: interrupting consumer thread");
            t.interrupt();
            try {
                t.join(TimeUnit.SECONDS.toMillis(SHUTDOWN_JOIN_SECONDS));
                if (t.isAlive()) {
                    log.warn("TransactionWriter consumer did not exit within {}s", SHUTDOWN_JOIN_SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting for TransactionWriter consumer", e);
            }
        }
    }

    private static final long POLL_IDLE_SECONDS = 30;

    public void consumeLoop() {
        while (true) {
            try {
                InMemoryEventBus.Envelope env = bus.poll(POLL_IDLE_SECONDS, TimeUnit.SECONDS);
                if (env == null) {
                    log.info("TransactionWriter listening for messages (no message in last {}s)", POLL_IDLE_SECONDS);
                    continue;
                }
                String tenantId = env.tenantId;
                TransactionEvent event = env.event;

                boolean inserted = postgres.upsertIdempotent(tenantId, event);
                if (inserted) {
                    log.info("Writer ledger insert OK tenantId={} transactionId={} merchantId={} categoryId={}", tenantId, event.getTransactionId(), event.getMerchantId(), event.getCategoryId());
                } else {
                    log.info("Writer ledger duplicate ignored tenantId={} transactionId={}", tenantId, event.getTransactionId());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("TransactionWriter interrupted", e);
                break;
            } catch (Exception e) {
                log.warn("Writer error consuming event: {}", e.getMessage());
            }
        }
    }
}
