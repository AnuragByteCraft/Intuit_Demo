package com.qb.analytics.controller;

import com.qb.analytics.infra.TenantContext;
import com.qb.analytics.model.TransactionEvent;
import com.qb.analytics.service.IngestionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/** Entry point for merchant platforms (Shopify/POS) to send transactions. */
@RestController
@RequestMapping("/api/v1/webhooks")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);
    private final IngestionService ingestionService;

    public WebhookController(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping("/transactions")
    public ResponseEntity<Map<String, Object>> ingest(@Valid @RequestBody TransactionEvent event) {
        String tenantId = TenantContext.getTenantId();
        log.info("Webhook received transaction tenantId={} transactionId={} merchantId={} categoryId={} amount={}",
                tenantId, event.getTransactionId(), event.getMerchantId(), event.getCategoryId(), event.getAmount());
        String requestId = ingestionService.ingest(tenantId, event);

        Map<String, Object> resp = new HashMap<>();
        resp.put("status", "ACCEPTED");
        resp.put("requestId", requestId);
        log.info("Webhook accepted transactionId={} requestId={}", event.getTransactionId(), requestId);
        return ResponseEntity.accepted().body(resp);
    }
}
