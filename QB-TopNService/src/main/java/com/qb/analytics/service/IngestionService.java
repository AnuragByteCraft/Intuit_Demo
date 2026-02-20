package com.qb.analytics.service;

import com.qb.analytics.infra.InMemoryEventBus;
import com.qb.analytics.model.TransactionEvent;
import com.qb.analytics.repository.RedisCacheRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * IngestionService:
 * - validates payload (controller triggers validation)
 * - idempotency at ingress (Redis)
 * - publishes to "Kafka" (in-memory queue) quickly
 *
 * NOTE: This is still synchronous request handling at API,
 * but the heavy processing happens async after publish.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);
    private final InMemoryEventBus bus;
    private final RedisCacheRepository redis;

    public IngestionService(InMemoryEventBus bus, RedisCacheRepository redis) {
        this.bus = bus;
        this.redis = redis;
    }

    public String ingest(String tenantId, TransactionEvent event) {
        String idemKey = "idem:webhook:" + tenantId + ":" + event.getTransactionId();
        String already = redis.get(idemKey);
        if (already != null) {
            log.info("Ingestion idempotency hit tenantId={} transactionId={} returning existing requestId={}", tenantId, event.getTransactionId(), already);
            return already;
        }

        String requestId = UUID.randomUUID().toString();
        bus.publish(tenantId, event);
        log.info("Ingestion published to event bus tenantId={} transactionId={} requestId={}", tenantId, event.getTransactionId(), requestId);
        redis.put(idemKey, requestId, 24 * 3600);
        return requestId;
    }
}
