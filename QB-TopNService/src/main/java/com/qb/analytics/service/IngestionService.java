package com.qb.analytics.service;

import com.qb.analytics.infra.InMemoryEventBus;
import com.qb.analytics.model.TransactionEvent;
import com.qb.analytics.repository.RedisCacheRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** Idempotency at ingress (Redis); publishes to event bus. Heavy processing is async after publish. */
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
        String requestId = UUID.randomUUID().toString();
        // Atomic claim: only one concurrent request with same transactionId can succeed
        if (!redis.putIfAbsent(idemKey, requestId, 24 * 3600)) {
            String existing = redis.get(idemKey);
            log.info("Ingestion idempotency hit tenantId={} transactionId={} returning existing requestId={}", tenantId, event.getTransactionId(), existing);
            return existing != null ? existing : requestId;
        }
        bus.publish(tenantId, event);
        log.info("Ingestion published to event bus tenantId={} transactionId={} requestId={}", tenantId, event.getTransactionId(), requestId);
        return requestId;
    }
}
