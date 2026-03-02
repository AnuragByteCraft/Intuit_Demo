package com.qb.analytics.service;

import com.qb.analytics.infra.InMemoryEventBus;
import com.qb.analytics.model.IngestResult;
import com.qb.analytics.model.TransactionEvent;
import com.qb.analytics.repository.RedisCacheRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionServiceTest {

    private InMemoryEventBus bus;
    private RedisCacheRepository redis;
    private IngestionService service;

    @BeforeEach
    void setUp() {
        bus = new InMemoryEventBus();
        redis = new RedisCacheRepository();
        service = new IngestionService(bus, redis);
    }

    @Test
    void firstIngest_returnsAcceptedAndPublishesToBus() throws InterruptedException {
        TransactionEvent event = event("t1", "store1", "electronics", 100.0, "2026-03-03T00:00:00Z");
        IngestResult result = service.ingest("shopify", event);

        assertThat(result.getStatus()).isEqualTo(IngestResult.STATUS_ACCEPTED);
        assertThat(result.getRequestId()).isNotBlank();

        InMemoryEventBus.Envelope envelope = bus.poll();
        assertThat(envelope).isNotNull();
        assertThat(envelope.tenantId).isEqualTo("shopify");
        assertThat(envelope.event.getTransactionId()).isEqualTo("t1");
        assertThat(envelope.event.getAmount()).isEqualTo(100.0);
    }

    @Test
    void duplicateIngest_returnsAlreadyProcessedWithSameRequestId() {
        TransactionEvent event = event("t1", "store1", "electronics", 100.0, "2026-03-03T00:00:00Z");
        IngestResult first = service.ingest("shopify", event);
        IngestResult second = service.ingest("shopify", event);

        assertThat(first.getStatus()).isEqualTo(IngestResult.STATUS_ACCEPTED);
        assertThat(second.getStatus()).isEqualTo(IngestResult.STATUS_ALREADY_PROCESSED);
        assertThat(second.getRequestId()).isEqualTo(first.getRequestId());
    }

    @Test
    void differentTransactionId_bothReturnAccepted() {
        IngestResult r1 = service.ingest("shopify", event("t1", "store1", "electronics", 100.0, "2026-03-03T00:00:00Z"));
        IngestResult r2 = service.ingest("shopify", event("t2", "store1", "clothing", 50.0, "2026-03-03T00:05:00Z"));

        assertThat(r1.getStatus()).isEqualTo(IngestResult.STATUS_ACCEPTED);
        assertThat(r2.getStatus()).isEqualTo(IngestResult.STATUS_ACCEPTED);
        assertThat(r2.getRequestId()).isNotEqualTo(r1.getRequestId());
    }

    private static TransactionEvent event(String transactionId, String merchantId, String categoryId, double amount, String eventTime) {
        TransactionEvent e = new TransactionEvent();
        e.setTransactionId(transactionId);
        e.setMerchantId(merchantId);
        e.setCategoryId(categoryId);
        e.setAmount(amount);
        e.setEventTime(eventTime);
        return e;
    }
}
