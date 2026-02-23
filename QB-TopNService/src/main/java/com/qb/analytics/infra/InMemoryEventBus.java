package com.qb.analytics.infra;

import com.qb.analytics.model.TransactionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** In-memory Kafka-like bus: publish and poll. */
@Component
public class InMemoryEventBus {

    private static final Logger log = LoggerFactory.getLogger(InMemoryEventBus.class);
    private final BlockingQueue<Envelope> queue = new LinkedBlockingQueue<>();

    public static class Envelope {
        public final String tenantId;
        public final TransactionEvent event;
        public Envelope(String tenantId, TransactionEvent event) {
            this.tenantId = tenantId;
            this.event = event;
        }
    }

    public void publish(String tenantId, TransactionEvent event) {
        queue.offer(new Envelope(tenantId, event));
        log.debug("EventBus publish tenantId={} transactionId={} queueSize={}", tenantId, event.getTransactionId(), queue.size());
    }

    /** Blocks until an envelope is available. */
    public Envelope poll() throws InterruptedException {
        return queue.take();
    }

    /** Poll with timeout; returns null if no envelope (lets consumers log when idle). */
    public Envelope poll(long timeout, TimeUnit unit) throws InterruptedException {
        return queue.poll(timeout, unit);
    }
}
