package com.qb.analytics.service;

import com.qb.analytics.infra.InMemoryEventBus;
import com.qb.analytics.model.TransactionEvent;
import com.qb.analytics.repository.RedisCacheRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Basic tests: idempotency (first publish, duplicate returns same requestId). */
class IngestionServiceTest {

    private InMemoryEventBus bus;
    private RedisCacheRepository redis;
    private IngestionService ingestionService;

    @BeforeEach
    void setUp() {
        bus = new InMemoryEventBus();
        redis = new RedisCacheRepository();
        ingestionService = new IngestionService(bus, redis);
    }

    @Test
    void ingest_firstCall_publishesAndReturnsRequestId() throws InterruptedException {
        TransactionEvent e = new TransactionEvent("t1", "m1", "cat1", 100.0, "2026-02-17T00:00:00Z");
        String requestId = ingestionService.ingest("tenantA", e);
        assertThat(requestId).isNotBlank();
        assertThat(bus.poll()).isNotNull();
    }

    @Test
    void ingest_duplicateCall_returnsSameRequestIdAndDoesNotPublishAgain() throws InterruptedException {
        TransactionEvent e = new TransactionEvent("t2", "m1", "cat1", 100.0, "2026-02-17T00:00:00Z");
        String first = ingestionService.ingest("tenantA", e);
        String second = ingestionService.ingest("tenantA", e);
        assertThat(second).isEqualTo(first);
        assertThat(bus.poll()).isNotNull();
        assertThat(bus.poll(100, TimeUnit.MILLISECONDS)).isNull();
    }
}
