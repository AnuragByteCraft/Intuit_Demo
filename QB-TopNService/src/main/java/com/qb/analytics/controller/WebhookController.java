package com.qb.analytics.controller;

import com.qb.analytics.infra.TenantContext;
import com.qb.analytics.model.IngestResult;
import com.qb.analytics.model.TransactionEvent;
import com.qb.analytics.service.IngestionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * WebhookController:
 * Entry point for merchant platforms (Shopify/POS) to send transactions.
 */
@RestController
@RequestMapping("/api/v1/webhooks")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);
    private final IngestionService ingestionService;

    public WebhookController(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping("/transactions")
    public ResponseEntity<?> ingest(@RequestHeader(value = "X-Merchant-Id", required = false) String merchantIdHeader,
                                    @Valid @RequestBody TransactionEvent event) {
        String tenantId = TenantContext.getTenantId();
        if (merchantIdHeader == null || merchantIdHeader.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "Missing required header: X-Merchant-Id"));
        }
        event.setMerchantId(merchantIdHeader.trim());
        log.info("Webhook received transaction tenantId={} merchantId={} transactionId={} categoryId={} amount={}",
                tenantId, event.getMerchantId(), event.getTransactionId(), event.getCategoryId(), event.getAmount());
        var result = ingestionService.ingest(tenantId, event);

        Map<String, Object> resp = new HashMap<>();
        resp.put("status", result.getStatus());
        resp.put("requestId", result.getRequestId());
        if (IngestResult.STATUS_ALREADY_PROCESSED.equals(result.getStatus())) {
            log.info("Webhook duplicate transactionId={} requestId={} status=ALREADY_PROCESSED", event.getTransactionId(), result.getRequestId());
            return ResponseEntity.ok(resp);
        }
        log.info("Webhook accepted transactionId={} requestId={}", event.getTransactionId(), result.getRequestId());
        return ResponseEntity.accepted().body(resp);
    }
}
